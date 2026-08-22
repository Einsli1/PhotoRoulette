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
) {
    val state = rememberSharedContentState(photoSharedKey(photo.mediaId))
    val context = LocalContext.current
    val previewSize = rememberScreenPixelSize()
    // While the grid branch is entering (the return transition) this goes 0 → 1 in step with the
    // shared-element bounds animation. The shared element is the ONLY thing rendered during the
    // transition and it animates from the preview's full-screen bounds down to this cell, so it
    // must render Fit at the start (to match the preview) and Crop at the end (to match the cell).
    val morph by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = { tween(PhotoTransitionMillis, easing = FastOutSlowInEasing) },
        label = "gridMorph",
    ) { s -> if (s == EnterExitState.Visible) 1f else 0f }
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
        // Fit copy: only composed while the return transition runs (morph < 1). It uses the same
        // fixed screen-size request as the preview, so it hits the preview's memory-cache entry
        // immediately instead of re-decoding. At rest it is not composed, so fast grid scrolling
        // only decodes the small Crop thumbnail below.
        if (morph < 1f) {
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
    var zoomResetTick by remember { mutableIntStateOf(0) }
    var closePending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismissThreshold = with(LocalDensity.current) { 160.dp.toPx() }

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
            .graphicsLayer { translationY = dragY }
            .then(
                if (swipeDownToClose) {
                    Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                // Downward drags pull the preview down; upward ones snap back.
                                if (amount > 0f || dragY > 0f) {
                                    dragY += amount
                                    change.consume()
                                }
                            },
                            onDragEnd = {
                                if (dragY > dismissThreshold) {
                                    // Settle back to center first so the shared element return
                                    // never starts from a displaced position.
                                    scope.launch {
                                        val start = dragY
                                        animate(0f, 1f, animationSpec = tween(160)) { p, _ ->
                                            dragY = start * (1f - p)
                                        }
                                        requestClose()
                                    }
                                } else {
                                    scope.launch {
                                        val start = dragY
                                        animate(0f, 1f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { p, _ ->
                                            dragY = start * (1f - p)
                                        }
                                    }
                                }
                            },
                            onDragCancel = { dragY = 0f },
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // Black background (fades with the branch transition).
        Box(Modifier.fillMaxSize().background(Color.Black))
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
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
            // controls so long photos never extend under the buttons.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
            bottomControls?.invoke(currentPhoto)
        }
    }
}
