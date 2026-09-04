package com.einsli.photoroulette.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutLinearInEasing
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Size as CoilSize
import com.einsli.photoroulette.data.PhotoEntity
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
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
    // thumbSnap: 内存缓存命中的位图直接全显、不做淡入。快速滑动时预加载让绝大多数新格子
    // 命中缓存，若每格都跑 180ms 的 alpha 淡入（附带离屏合成层），逐帧重组+渲染开销是
    // 滑动掉帧的主因之一；只有真正需要解码的格子才淡入。
    var thumbReady by remember(photo.uri, gridSize) { mutableStateOf(false) }
    var thumbSnap by remember(photo.uri, gridSize) { mutableStateOf(false) }
    val thumbPainter = rememberAsyncImagePainter(
        thumbRequest,
        onSuccess = { state ->
            thumbReady = true
            thumbSnap = state.result.dataSource == DataSource.MEMORY_CACHE
        },
        contentScale = contentScale,
    )
    val thumbAlpha = if (thumbSnap) 1f else animateFloatAsState(
        targetValue = if (thumbReady) 1f else 0f,
        animationSpec = tween(180),
        label = "thumbAlpha",
    ).value
    Box(
        modifier
            .sharedElement(
                state,
                animatedVisibilityScope,
                boundsTransform = PhotoBoundsTransform,
            )
            .clip(RoundedCornerShape(animatedRadius))
    ) {
        // 占位底色：仅在缩略图未就绪时绘制。已加载的格子不再多画一层背景，减少过度绘制。
        if (!thumbReady) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
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
 * Full-screen preview overlay, composed inside the caller's AnimatedVisibility overlay branch
 * and layered ABOVE the caller's always-composed page.
 *
 * NOTE: this overlay does NOT participate in the shared-element system. The page layer's cells
 * stay in their own AnimatedVisibility(visible=true) scope; the flying photo would need the
 * shared-transition machinery to start a bounds animation between two scopes, but in Compose
 * 1.7.6 that flight never starts reliably when the source scope never exits (BoundsAnimation.
 * animate() only fires while isTransitionActive is true, which is driven by running scope
 * transitions — the always-composed page has none). Photos therefore appear full-screen directly
 * (fade in with the overlay) instead of being stuck at the source cell bounds.
 *
 * The black scrim covers the real page beneath and fades as the photo is dragged down — the
 * page behind brightens from dark.
 *
 * [fullScreenPhotoArea] (回收站/回忆时光机): the photo fills the ENTIRE screen (including under
 * the system bars) and the header/buttons float on top of it. [tapToToggleChrome] lets a single
 * tap on a photo hide/show the header and buttons, and [doubleTapToZoom] makes a double tap zoom
 * 1x↔3x (only zooms back out when already zoomed in).
 *
 * Opening: the header/buttons stay hidden while the overlay fades in (~300ms) and fade in
 * afterwards, so they never overlap the photo awkwardly.
 *
 * Closing first snaps any pinch-zoom back to 1x, then invokes [onClose] with the current photo
 * and whether the close came from a downward swipe:
 * - 下滑提交(拖过阈值): 照片从松手位置顺势下滑出屏,页面原样露出。
 * - 侧滑/系统返回/关闭按钮: 直接关闭(overlay 淡出)。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedPhotoPreview(
    photos: List<PhotoEntity>,
    initialIndex: Int,
    onClose: (PhotoEntity, viaSwipeDown: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    swipeDownToClose: Boolean = false,
    /** 预览 overlay 是否处于打开态（调用方用 AnimatedVisibility(visible=...) 驱动时传入）。
     *  实例常驻时（快速关闭再打开不销毁重建），active 变 true 重置本次会话状态。 */
    active: Boolean = true,
    bottomControls: (@Composable (current: PhotoEntity) -> Unit)? = null,
    sourceThumbSize: CoilSize? = null,
    /** 照片铺满整块屏幕（含系统栏之下），标题和按钮浮在照片上层（回收站/回忆时光机）。 */
    fullScreenPhotoArea: Boolean = false,
    /** 单击照片（仅照片，非视频）隐藏/显示标题和按钮。 */
    tapToToggleChrome: Boolean = false,
    /** 双击照片在 1x ↔ 3x 间缩放；仅在已放大时允许缩小回 1x。 */
    doubleTapToZoom: Boolean = false,
) {
    // Capture the list for this preview session: an in-preview restore/delete (which changes the
    // page's list) never yanks the pager out from under the exit animation. Re-key on the list
    // identity so a reopen after the list changed (restore/delete) shows the fresh list.
    val openPhotos = remember(photos) { photos }
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (openPhotos.size - 1).coerceAtLeast(0)),
    ) { openPhotos.size.coerceAtLeast(1) }
    val currentPhoto = openPhotos.getOrNull(pagerState.currentPage) ?: return
    val context = LocalContext.current
    var dragY by remember { mutableFloatStateOf(0f) }
    // 下滑提交式关闭:照片从松手位置顺势滑出屏幕底部。
    var swipeOut by remember { mutableStateOf(false) }
    var zoomResetTick by remember { mutableIntStateOf(0) }
    var closePending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 状态栏高度在进入预览时固定捕获：状态栏隐藏时 chrome/时间戳不会跳位。
    val statusBarTop = rememberStatusBarTop()
    val dismissThreshold = with(density) { 96.dp.toPx() }
    val effectiveDrag = dragY
    // The dark→bright reveal is deliberately SLOWER than the photo: the page behind reaches full
    // brightness only after dragging ~2x the dismiss threshold, so it trails the photo.
    val revealDistance = dismissThreshold * 2f
    val revealProgress =
        if (swipeDownToClose) (effectiveDrag / revealDistance).coerceIn(0f, 1f) else 0f
    val scrimAlpha = 1f - revealProgress
    // 单击照片隐藏/显示标题与按钮（仅照片，非视频；视频点击仍是播放/暂停）。
    var chromeHidden by remember { mutableStateOf(false) }
    // 打开预览时：标题/按钮先隐藏，等 overlay 淡入结束后再淡入——出现即固定为
    // 「按钮浮在照片上层」，不会在过渡期间与照片互相盖压（bug 3）。
    var chromeRevealed by remember { mutableStateOf(false) }
    // 实例常驻（AnimatedVisibility(visible=...) 驱动）时，快速关闭再打开不销毁重建本实例：
    // active 翻转为 true 时重置本次会话状态（否则会带着上次的 dragY/swipeOut/缩放残留），
    // 并把 pager 定位到新打开的初始照片。
    LaunchedEffect(active) {
        if (active) {
            dragY = 0f
            swipeOut = false
            closePending = false
            chromeHidden = false
            chromeRevealed = false
            zoomResetTick++ // 强制当前照片缩回 1x（新打开的照片从 1x 起步）
            pagerState.scrollToPage(initialIndex.coerceIn(0, (openPhotos.size - 1).coerceAtLeast(0)))
            delay(PhotoTransitionMillis + 30L)
            chromeRevealed = true
        }
    }
    val chromeRevealAlpha by animateFloatAsState(
        targetValue = if (chromeRevealed) 1f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "chromeReveal",
    )
    // Title / buttons are NOT tied to the photo's drag distance: any downward drag (>0px) flies
    // them out of the screen immediately; they fly back as soon as the photo returns to rest.
    // 单击隐藏时同样飞出屏幕，再单击飞回。
    val chromeOut = (dragY > 0f) || closePending || (tapToToggleChrome && chromeHidden)
    val chromeProgress by animateFloatAsState(
        targetValue = if (chromeOut) 1f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "chromeExit",
    )
    val chromeExitPx = with(density) { 140.dp.toPx() }
    // 状态栏只跟随「单击隐藏」：拖拽/关闭时不藏状态栏，避免返回宫格时 insets 变化导致页面跳位（闪一下）。
    if (fullScreenPhotoArea) SyncStatusBarWithChrome(tapToToggleChrome && chromeHidden)
    // 全屏模式下视频控制条悬浮在底部按钮上方（回收站有按钮：按钮行高~72dp + 间距12dp；
    // 回忆时光机没有按钮：只让出导航栏+16dp）。整理页预览不悬浮。
    val videoBarBottomInset: Dp = if (fullScreenPhotoArea) {
        val aboveBottom = if (bottomControls != null) 84.dp else 16.dp
        with(density) {
            (WindowInsets.navigationBars.getBottom(density) + aboveBottom.toPx()).toDp()
        }
    } else 0.dp

    fun requestClose() {
        if (closePending) return
        closePending = true
        // Bump the tick: the current page's ZoomablePhoto animates back to 1x and then calls
        // onResetDone → onClose(currentPhoto, swipeOut).
        zoomResetTick++
    }

    // 退出中（active=false）关闭 BackHandler：让返回键直接落到页面层，不吞掉关闭动画。
    BackHandler(enabled = active) { requestClose() }

    Box(
        modifier
            .fillMaxSize()
    ) {
        // 页面层由调用方常驻组合在本预览 overlay 的下层：scrim 不透明时盖住真实页面，
        // 拖拽变透明时直接露出它。先放一层 touch-absorbing 层，让预览的透明区域永远到不了
        // （真实）页面，再放黑色 scrim —— 照片被拖下时「页面从暗变亮」。
        // closePending 后（照片已滑出，overlay 即将消失）不再吸收点击，网格立即可点——
        // 修「下滑返回后快速点击」被旧预览 absorber 吞掉的卡死。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(closePending) {
                    if (!closePending) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                }
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))

        // ── 照片区域（全屏，无 shared element）──
        // Layout-level offset (not graphicsLayer): the photo follows the drag with the layout.
        val dragOffset = Modifier.offset { IntOffset(0, dragY.roundToInt()) }
        val swipeDownModifier: Modifier =
            if (swipeDownToClose) {
                Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { },
                        onVerticalDrag = { change, amount ->
                            // Downward drags pull the photo down; upward ones snap back.
                            if (amount > 0f || dragY > 0f) {
                                dragY += amount
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (dragY > dismissThreshold) {
                                // 下滑提交:从松手位置继续把布局 offset 推到屏幕底之外(与拖拽
                                // 同一条通路),滑出后再走正常关闭;scrim 跟随 dragY 同步变亮。
                                swipeOut = true
                                scope.launch {
                                    val start = dragY
                                    val travel = (size.height.toFloat() - start).coerceAtLeast(0f)
                                    animate(0f, 1f, animationSpec = tween(300, easing = FastOutLinearInEasing)) { p, _ ->
                                        dragY = start + travel * p
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

        val photoContent: @Composable () -> Unit = {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val p = openPhotos[page]
                // The preview's placeholder is the source cell thumbnail (same key as the grid
                // side), so the first open shows it instantly while the full-screen copy decodes.
                val placeholder = remember(p.mediaId, sourceThumbSize) {
                    sourceThumbSize?.let { photoThumbRequest(context, p, it) }
                }
                if (p.mimeType.startsWith("video/")) {
                    VideoPhoto(
                        photo = p,
                        active = pagerState.currentPage == page,
                        resetTick = zoomResetTick,
                        onResetDone = { if (closePending) onClose(currentPhoto, swipeOut) },
                        placeholderRequest = placeholder,
                        bottomInset = videoBarBottomInset,
                        chromeProgress = chromeProgress,
                        chromeExitPx = chromeExitPx,
                        onTap = if (tapToToggleChrome) ({ chromeHidden = !chromeHidden }) else null,
                    )
                } else {
                    ZoomablePhoto(
                        photo = p,
                        resetTick = zoomResetTick,
                        onResetDone = { if (closePending) onClose(currentPhoto, swipeOut) },
                        placeholderRequest = placeholder,
                        onTap = { if (tapToToggleChrome) chromeHidden = !chromeHidden },
                        doubleTapZoom = doubleTapToZoom,
                    )
                }
            }
        }

        // ── 标题区（关闭 / 文件名 / 页码）──
        val headerRow: @Composable () -> Unit = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
        }

        if (fullScreenPhotoArea) {
            // ── 全屏照片分支（回收站 / 回忆时光机）──
            // 照片铺满整块屏幕（含状态栏/导航栏之下）；标题和按钮浮在照片上层，拖动退出时
            // 照片下滑、标题上滑、按钮下滑（不加渐变底，避免在照片上出现阴影）。
            Box(
                Modifier
                    .fillMaxSize()
                    .then(dragOffset)
                    .then(swipeDownModifier)
            ) {
                photoContent()
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = statusBarTop)
                    .navigationBarsPadding()
                    // 打开淡入期间 chrome 先隐藏，淡入结束后再淡入：避免「图片和按钮的
                    // 上下位置关系」在过渡期间来回变化（bug 3）。
                    .alpha(chromeRevealAlpha)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = -chromeProgress * chromeExitPx }
                ) {
                    headerRow()
                }
                Spacer(Modifier.weight(1f))
                bottomControls?.let { controls ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { translationY = chromeProgress * chromeExitPx }
                    ) {
                        controls(currentPhoto)
                    }
                }
            }
        } else {
            // ── 常规分支（整理页）──
            // Photo area between the header and the controls so long photos never extend under
            // the buttons. On swipe-down dismiss the photo slides down while the header slides up
            // and the controls slide down (both driven by effectiveDrag); the controls are drawn
            // after (on top), so the sliding photo passes UNDER them and never blocks the buttons.
            Column(Modifier.fillMaxSize().systemBarsPadding().alpha(chromeRevealAlpha)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = -chromeProgress * chromeExitPx }
                ) {
                    headerRow()
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .then(dragOffset)
                        .then(swipeDownModifier)
                ) {
                    photoContent()
                }
                bottomControls?.let { controls ->
                    Box(Modifier.graphicsLayer { translationY = chromeProgress * chromeExitPx }) {
                        controls(currentPhoto)
                    }
                }
            }
        }
    }
}

/**
 * 让系统状态栏跟随全屏预览的 chrome 一起隐藏/显示：单击照片隐藏标题/按钮时状态栏一并收起，
 * 再单击（或关闭预览/离开页面）时恢复。组合销毁时强制恢复，避免状态栏卡在隐藏状态。
 * （系统状态栏的收起动画由系统控制，Android 公开 API 无法关闭，这里只保证时机同步。）
 */
@Composable
internal fun SyncStatusBarWithChrome(hidden: Boolean) {
    val activity = LocalContext.current as? Activity
    LaunchedEffect(hidden) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = window.insetsController ?: return@LaunchedEffect
        if (hidden) {
            controller.hide(android.view.WindowInsets.Type.statusBars())
        } else {
            controller.show(android.view.WindowInsets.Type.statusBars())
        }
    }
    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            window.insetsController?.show(android.view.WindowInsets.Type.statusBars())
        }
    }
}

/**
 * 进入页面/预览时**固定捕获**的状态栏高度（dp）。用固定值做 padding 后，状态栏隐藏/显示不会
 * 改变任何布局——返回宫格时页面不会因 insets 变化而跳位（闪一下）。不要用 systemBarsPadding()。
 */
@Composable
internal fun rememberStatusBarTop(): Dp {
    val density = LocalDensity.current
    val insets = WindowInsets.statusBars
    return remember { with(density) { insets.getTop(density).toDp() } }
}
