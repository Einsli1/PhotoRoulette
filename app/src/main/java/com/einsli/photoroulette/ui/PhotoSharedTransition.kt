package com.einsli.photoroulette.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Size as CoilSize
import com.einsli.photoroulette.data.PhotoEntity
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Shared element key: stable per photo, never index-based. */
@OptIn(ExperimentalSharedTransitionApi::class)
fun photoSharedKey(mediaId: Long): String = "photo-$mediaId"

/** Duration and easing shared by the bounds animation and the corner-radius animation. */
internal const val PhotoTransitionMillis = 300

@OptIn(ExperimentalSharedTransitionApi::class)
internal val PhotoBoundsTransform: BoundsTransform = BoundsTransform { _, _ ->
    tween(PhotoTransitionMillis, easing = FastOutSlowInEasing)
}

/** Screen size in pixels, used as the fixed request size for preview images and their preloads. */
@Composable
internal fun rememberScreenPixelSize(): CoilSize {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    return remember(configuration, density) {
        CoilSize(
            (configuration.screenWidthDp * density).roundToInt(),
            (configuration.screenHeightDp * density).roundToInt(),
        )
    }
}

/**
 * The thumbnail request shared by a photo's grid/card cell, the preview's source-scale copy and
 * the preview's placeholder. Data + size + parameters are IDENTICAL in all three places so they
 * hit the SAME memory-cache entry: the first preview open then shows the already-loaded cell
 * bitmap instead of a blank gap while the full-screen copy decodes. Videos decode a
 * representative frame (1s in) instead of the often-black first frame.
 */
internal fun photoThumbRequest(context: android.content.Context, photo: PhotoEntity, size: CoilSize? = null): ImageRequest =
    ImageRequest.Builder(context).data(photo.uri).apply {
        if (size != null) size(size)
        if (photo.mimeType.startsWith("video/")) videoFrameMillis(1000)
    }.build()

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoSharedTransitionLayout(
    modifier: Modifier = Modifier,
    content: @Composable SharedTransitionScope.() -> Unit,
) {
    SharedTransitionLayout(modifier = modifier, content = content)
}

/**
 * Grid-side shared element, composed inside the AnimatedContent's grid branch. The corner
 * radius is animated by the branch transition (see [photoBranchRadius]) and the photo is matched
 * to the preview by [photoSharedKey].
 *
 * During a shared transition only the *incoming* shared element is rendered (the grid on return),
 * animating from the other side's bounds (the full-screen preview) down to this cell. To avoid the
 * full-screen Crop flash it therefore crossfades from Fit (matching the preview at the start) to
 * [contentScale] (Crop by default, the cell's resting look) as the grid branch enters.
 *
 * The resting cell loads a small Crop thumbnail. The full-screen Fit copy is composed only
 * while the return transition is running and reuses the preview's screen-size cache entry, so the
 * grid stays cheap to scroll and the return has no decode flash.
 *
 * [gridSize] fixes the decode size for the resting thumbnail (in pixels). Passing the cell size
 * makes grid scrolling decode small bitmaps only — fast to load and one stable memory-cache entry
 * per photo — and the thumbnail fades in once decoded instead of flashing the placeholder.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.SharedGridImage(
    photo: PhotoEntity,
    animatedRadius: Dp,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    gridSize: CoilSize? = null,
    /** When the caller keeps the grid always composed (RecycleBin / MemoryViewer, where the page
     *  is revealed behind the preview), it passes its own Fit→Crop morph here so the return
     *  flight still starts Fit. Null keeps the branch-transition-driven morph. */
    morphOverride: Float? = null,
    /** Only the cell the closed photo is flying back to needs the full-screen Fit copy while the
     *  branch enters; the other cells just fade in their Crop thumbnails. Rendering a screen-size
     *  AsyncImage for EVERY visible cell made the return's first frame stall (~100ms). */
    fitOnEnter: Boolean = true,
) {
    val state = rememberSharedContentState(photoSharedKey(photo.mediaId))
    val context = LocalContext.current
    val previewSize = rememberScreenPixelSize()
    // While the grid branch is entering (the return transition) this goes 0 → 1 in step with the
    // shared-element bounds animation. The shared element is the ONLY thing rendered during the
    // transition and it animates from the preview's full-screen bounds down to this cell, so it
    // must render Fit at the start (to match the preview) and Crop at the end (to match the cell).
    val transitionMorph by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = { tween(PhotoTransitionMillis, easing = FastOutSlowInEasing) },
        label = "gridMorph",
    ) { s -> if (s == EnterExitState.Visible) 1f else 0f }
    // Only the returning cell reads the transition (recomposing every frame); the other cells
    // stay static at morph=1. Reading the delegated state is what subscribes a cell to the
    // per-frame animation — without this gate every visible cell recomposed each flight frame
    // (a 60ms+ frame budget on the trash grid).
    val morph = if (fitOnEnter) (morphOverride ?: transitionMorph) else 1f
    // Resting thumbnail: fixed cell-size request (when [gridSize] is provided) so grid scrolling
    // decodes only the small bitmap and hits a stable memory-cache entry. Fades in on success.
    val thumbRequest = remember(photo.uri, gridSize) { photoThumbRequest(context, photo, gridSize) }
    var thumbReady by remember(photo.uri, gridSize) { mutableStateOf(false) }
    val thumbPainter = rememberAsyncImagePainter(
        thumbRequest,
        onSuccess = { thumbReady = true },
        contentScale = contentScale,
    )
    val thumbAlpha by animateFloatAsState(
        targetValue = if (thumbReady) 1f else 0f,
        animationSpec = tween(180),
        label = "thumbAlpha",
    )
    Box(
        modifier
            .sharedElement(
                state,
                animatedVisibilityScope,
                boundsTransform = PhotoBoundsTransform,
            )
            .clip(RoundedCornerShape(animatedRadius))
    ) {
        // Fit copy: only composed for the returning cell while the return transition runs
        // (morph < 1). It uses the same fixed screen-size request as the preview, so it hits the
        // preview's memory-cache entry immediately instead of re-decoding. At rest it is not
        // composed, so fast grid scrolling only decodes the small Crop thumbnail below.
        if (morph < 1f && fitOnEnter) {
            AsyncImage(
                model = remember(photo.uri, previewSize) {
                    ImageRequest.Builder(context).data(photo.uri).size(previewSize).apply {
                        if (photo.mimeType.startsWith("video/")) videoFrameMillis(1000)
                    }.build()
                },
                contentDescription = photo.displayName,
                modifier = Modifier.fillMaxSize().alpha(1f - morph),
                contentScale = ContentScale.Fit,
            )
        }
        // Crop copy: the resting thumbnail (cell size), fades in as the photo lands.
        androidx.compose.foundation.Image(
            painter = thumbPainter,
            contentDescription = photo.displayName,
            modifier = Modifier.fillMaxSize().alpha(morph * thumbAlpha),
            contentScale = contentScale,
        )
    }
}

/**
 * Corner radius for one AnimatedContent branch, driven by the branch's enter/exit transition so
 * it stays in sync with the shared-element bounds animation:
 * - grid side: cell radius at rest / while entering, 0 while exiting (the cell opens up);
 * - preview side: 0 at rest / while entering, cell radius while exiting (corners return).
 */
@Composable
internal fun AnimatedVisibilityScope.photoBranchRadius(
    gridCornerRadius: Dp,
    gridSide: Boolean,
): Dp {
    val radius by transition.animateDp(
        transitionSpec = { tween(PhotoTransitionMillis, easing = FastOutSlowInEasing) },
        label = if (gridSide) "gridCorner" else "previewCorner",
    ) { state ->
        if (gridSide) {
            if (state == EnterExitState.Visible) gridCornerRadius else 0.dp
        } else {
            if (state == EnterExitState.Visible) 0.dp else gridCornerRadius
        }
    }
    return radius
}

/**
 * Full-screen preview branch, composed inside the AnimatedContent's preview branch.
 *
 * Only the photo area is a shared element (key = the CURRENT pager page's photo, so swiping
 * only swaps the key without retriggering a transition and closing returns the photo that is
 * actually on screen back to its own grid cell). The black background, the header and the
 * optional bottom controls are plain content of the branch and fade with the branch transition.
 * The photo area sits between the header and the controls so long photos never extend under the
 * buttons.
 *
 * Closing first snaps any pinch-zoom back to 1x, then invokes [onClose] with the current photo
 * so the caller can make its grid cell visible before the shared element returns.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.SharedPhotoPreview(
    photos: List<PhotoEntity>,
    initialIndex: Int,
    animatedRadius: Dp,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClose: (PhotoEntity) -> Unit,
    modifier: Modifier = Modifier,
    swipeDownToClose: Boolean = false,
    /** Whole-page backdrop drawn BEHIND the black scrim and revealed as the photo is dragged
     *  down to dismiss (the caller passes a mirror of its page so the reveal shows the full
     *  page — title, buttons and grid — brightening from dark). */
    revealContent: (@Composable () -> Unit)? = null,
    bottomControls: (@Composable (current: PhotoEntity) -> Unit)? = null,
    sourceContentScale: ContentScale = ContentScale.Crop,
    sourceThumbSize: CoilSize? = null,
) {
    // Capture the list for this preview session: an in-preview restore/delete (which changes the
    // page's list) never yanks the pager out from under the exit animation.
    val openPhotos = remember { photos }
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (openPhotos.size - 1).coerceAtLeast(0)),
    ) { openPhotos.size.coerceAtLeast(1) }
    val currentPhoto = openPhotos.getOrNull(pagerState.currentPage) ?: return
    val context = LocalContext.current
    val state = rememberSharedContentState(photoSharedKey(currentPhoto.mediaId))
    // While a shared transition is running the image is controlled by the transition; disable
    // pinch/pan so the two never fight over the same photo.
    val transitionActive = isTransitionActive
    // While the preview branch is entering (the open transition) this goes 0 → 1 in step with the
    // shared-element bounds animation. The shared element animates from the grid cell up to
    // full-screen, so it must render the source scale at the start and Fit at the end.
    val morph by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = { tween(PhotoTransitionMillis, easing = FastOutSlowInEasing) },
        label = "previewMorph",
    ) { s -> if (s == EnterExitState.Visible) 1f else 0f }
    var dragY by remember { mutableFloatStateOf(0f) }
    // Pinned to the drag position when a dismiss is COMMITTED: the photo keeps flying via the
    // shared transition while dragY springs back to 0, but the scrim must stay at the released
    // position so the grid stays revealed for the whole exit.
    var releasedDragY by remember { mutableFloatStateOf(0f) }
    var zoomResetTick by remember { mutableIntStateOf(0) }
    var closePending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 96.dp.toPx() }
    val effectiveDrag = maxOf(dragY, releasedDragY)
    // The dark→bright reveal is deliberately SLOWER than the photo: the page behind reaches full
    // brightness only after dragging ~2x the dismiss threshold, so it trails the photo.
    val revealDistance = dismissThreshold * 2f
    val revealProgress =
        if (swipeDownToClose && revealContent != null) (effectiveDrag / revealDistance).coerceIn(0f, 1f) else 0f
    val scrimAlpha = 1f - revealProgress
    // Title / buttons are NOT tied to the photo's drag distance: any downward drag (>0px) flies
    // them out of the screen immediately; they fly back as soon as the photo returns to rest.
    val chromeOut = (dragY > 0f) || closePending
    val chromeProgress by animateFloatAsState(
        targetValue = if (chromeOut) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "chromeExit",
    )
    val chromeExitPx = with(density) { 140.dp.toPx() }

    fun requestClose() {
        if (closePending) return
        closePending = true
        // Bump the tick: the current page's ZoomablePhoto animates back to 1x and then calls
        // onResetDone → onClose(currentPhoto).
        zoomResetTick++
    }

    BackHandler { requestClose() }

    Box(
        modifier
            .fillMaxSize()
    ) {
        // Whole-page backdrop (the caller's page mirror), then a touch-absorbing layer so the
        // preview's transparent areas never reach the (real) page beneath, then the black scrim
        // that fades out as the photo is dragged down — "the page behind brightens from dark".
        revealContent?.invoke()
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                }
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .graphicsLayer { translationY = -chromeProgress * chromeExitPx },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { requestClose() }) {
                    Icon(Icons.Default.Close, "关闭", tint = Color.White)
                }
                Text(
                    currentPhoto.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    "${pagerState.currentPage + 1}/${openPhotos.size}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            // Photo area — the shared element (only the photo). Between the header and the
            // controls so long photos never extend under the buttons. On swipe-down dismiss the
            // photo slides down while the header slides up and the controls slide down (both
            // driven by effectiveDrag); the controls are drawn after (on top), so the sliding
            // photo passes UNDER them and never blocks the buttons.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // Layout-level offset (not graphicsLayer): the shared-element flight reads
                    // LAYOUT bounds, so starting from the dragged position requires the drag to
                    // move the layout — otherwise the return flight snaps to center first.
                    .offset { IntOffset(0, dragY.roundToInt()) }
                    .then(
                        if (swipeDownToClose) {
                            Modifier.pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragStart = { releasedDragY = 0f },
                                    onVerticalDrag = { change, amount ->
                                        // Downward drags pull the photo down; upward ones snap back.
                                        if (amount > 0f || dragY > 0f) {
                                            dragY += amount
                                            change.consume()
                                        }
                                    },
                                    onDragEnd = {
                                        if (dragY > dismissThreshold) {
                                            // Start the return immediately FROM the released
                                            // position: requestClose() kicks off the shared-
                                            // element flight back to the grid cell while dragY
                                            // animates to 0 over the same duration and easing, so
                                            // the photo keeps moving from where it was released
                                            // instead of snapping back to center first.
                                            // Pin the photo where it was released: the flight takes
                                            // over from the released position (the offset moves the
                                            // layout, so the shared-element start bounds include it).
                                            // No dragY spring-back here — it would fight the flight.
                                            releasedDragY = dragY
                                            requestClose()
                                        } else {
                                            scope.launch {
                                                val start = dragY
                                                animate(0f, 1f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { p, _ ->
                                                    dragY = start * (1f - p)
                                                }
                                            }
                                        }
                                    },
                                    onDragCancel = { dragY = 0f; releasedDragY = 0f },
                                )
                            }
                        } else {
                            Modifier
                        }
                    )
                    .sharedElement(
                        state,
                        animatedVisibilityScope,
                        boundsTransform = PhotoBoundsTransform,
                    )
                    .clip(RoundedCornerShape(animatedRadius))
            ) {
                // Copy of the current photo in the source scale (Crop for the grids, Fit for the
                // review card): matches the cell/card at the start of the open transition, then
                // fades out as the photo expands to full-screen. Only composed while the open
                // transition runs, so the resting preview does not waste a full-screen decode.
                if (morph < 1f) {
                    // Same request key as the source cell/card thumbnail (data + size + frame
                    // param), so this copy is an instant cache hit and the open transition starts
                    // from the already-loaded thumbnail instead of a blank area.
                    val copyRequest = remember(currentPhoto.uri, sourceThumbSize) {
                        photoThumbRequest(context, currentPhoto, sourceThumbSize)
                    }
                    AsyncImage(
                        copyRequest,
                        currentPhoto.displayName,
                        Modifier.fillMaxSize().alpha(1f - morph),
                        contentScale = sourceContentScale,
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().alpha(morph),
                ) { page ->
                    val p = openPhotos[page]
                    // The preview's placeholder is the source cell/card thumbnail (same key as
                    // the grid side), so the first open shows it instantly while the full-screen
                    // copy decodes in the background.
                    val placeholder = remember(p.mediaId, sourceThumbSize) {
                        sourceThumbSize?.let { photoThumbRequest(context, p, it) }
                    }
                    if (p.mimeType.startsWith("video/")) {
                        VideoPhoto(
                            photo = p,
                            active = pagerState.currentPage == page,
                            resetTick = zoomResetTick,
                            onResetDone = { if (closePending) onClose(currentPhoto) },
                            placeholderRequest = placeholder,
                        )
                    } else {
                        ZoomablePhoto(
                            photo = p,
                            enabled = !transitionActive,
                            resetTick = zoomResetTick,
                            onResetDone = { if (closePending) onClose(currentPhoto) },
                            placeholderRequest = placeholder,
                        )
                    }
                }
            }
            bottomControls?.let { controls ->
                Box(Modifier.graphicsLayer { translationY = chromeProgress * chromeExitPx }) {
                    controls(currentPhoto)
                }
            }
        }
    }
}
