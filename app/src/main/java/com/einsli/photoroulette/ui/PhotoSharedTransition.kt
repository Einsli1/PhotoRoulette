package com.einsli.photoroulette.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
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

/**
 * 进程内照片宽高比(w/h)缓存:任何缩略图解码时(宫格滚动/预载/预览)写入。它存活于 shared-layout
 * 的重 key 与页面切换,让预览在打开瞬间就能同步拿到 Crop↔Fit 的缩放比——而不是等一次解码
 * (否则首帧会闪一帧 Fit 渲染,正是本次要消除的突变)。
 */
object PhotoAspectCache {
    private val map = HashMap<Long, Float>()
    fun put(mediaId: Long, aspect: Float) {
        if (aspect.isFinite() && aspect > 0f) map[mediaId] = aspect
    }
    fun get(mediaId: Long): Float? = map[mediaId]
}

/** 在**方形宫格**里,ContentScale.Crop 比 ContentScale.Fit 放大多少倍。对 w×h 的图
 *  (aspect = w/h)放入 1:1 的盒子:Crop 缩放 = Fit 缩放 × max(aspect, 1/aspect)。 */
internal fun cropToFitRatio(aspect: Float): Float = maxOf(aspect, 1f / aspect)

/** Duration and easing shared by the bounds animation, the content zoom and the corner radius. */
internal const val PhotoTransitionMillis = 250

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
 * Grid-side shared element, composed in the always-composed page layer (AnimatedVisibility
 * (visible=true) only provides structure, never a running transition).
 *
 * The shared element system here is driven by **caller-managed visibility** — see
 * [SharedTransitionScope.sharedElementWithCallerManagedVisibility]: the cell's [sharedVisible]
 * must flip to false while the preview is open (the caller passes `!previewOpen`), so the cell
 * exits the "becoming visible" race. Exactly ONE side (the preview while opening, this cell
 * while returning) has `target == true` at a time; otherwise the measure-order race between the
 * cell and the overlay would repeatedly re-target the flight and the photo gets stuck at the
 * source bounds (the failure mode of the previous attempt, commit 36063d0's predecessor).
 *
 * The corner radius is applied as a child of the shared element so it is part of the recorded
 * overlay layer during the flight: the returning photo keeps the cell's rounded corners as it
 * lands.
 *
 * During a shared transition only the *incoming* shared element is rendered in the overlay — the
 * cell on return — animating from the other side's bounds (the full-screen preview) down to this
 * cell. To avoid the full-screen Crop flash it therefore renders the full-screen Fit copy (matching
 * the preview at the start) and zooms it IN to Crop (scale = [cropToFitRatio]) as the photo lands —
 * the exact reverse of the open flight, no crossfade. [fitOnEnter] gates the per-frame subscription
 * to the transition state to just the returning cell, so the other cells stay static during the
 * flight.
 *
 * The resting cell loads a small Crop thumbnail. The full-screen Fit copy is composed only
 * while a flight targeting this cell is running and reuses the preview's screen-size cache
 * entry, so the grid stays cheap to scroll and the return has no decode flash.
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
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    gridSize: CoilSize? = null,
    /** 覆盖本 cell 的 shared key。默认 = 自身 mediaId;关闭流程中调用方会把「该起飞的 cell」
     *  顶成 base key、其余 cell 换成唯一哑 key,保证返回飞行只配对到当前 cell。 */
    sharedKey: String = photoSharedKey(photo.mediaId),
    /** Only the cell a photo is flying back to needs the full-screen Fit copy while the return
     *  transition runs (it is the incoming side, so its layer is what the overlay draws at the
     *  animated bounds); the other cells just keep their Crop thumbnails. Rendering a
     *  screen-size AsyncImage for EVERY visible cell made the return's first frame stall
     *  (~100ms), and subscribing every cell to the per-frame transition state recomposed the
     *  whole grid each flight frame. */
    fitOnEnter: Boolean = true,
    /** Must be `!previewOpen`: while the preview is open the cell must NOT be a "becoming
     *  visible" shared element — only the preview side may claim the flight target. */
    sharedVisible: Boolean = true,
) {
    val state = rememberSharedContentState(sharedKey)
    val context = LocalContext.current
    val previewSize = rememberScreenPixelSize()
    // A flight targeting this cell: a match exists (preview open on this photo) AND a shared
    // transition is running. Reading these states is what subscribes this cell to the
    // per-frame animation — gated by fitOnEnter so only the returning cell recomposes each
    // flight frame (a 60ms+ frame budget on the trash grid otherwise).
    val flightActive = fitOnEnter && state.isMatchFound && isTransitionActive
    // Fit → Crop zoom progress: rests at 0, animates 0→1 in step with the flight (same
    // duration/easing). At 0 the Fit copy renders at scale 1 (identical to the preview at
    // full-screen), at 1 it has zoomed to scale cropRatio (Crop). When the flight is over,
    // morph snaps back to 0.
    val morph = remember { Animatable(0f) }
    LaunchedEffect(flightActive) {
        if (flightActive) {
            // 返回方向必须与边框同速:内容「回裁」是放大,若比边框缩小快,返回一开始
            // 会先快速放大裁回一下再缩小,读作一次「跳跃」。
            morph.animateTo(1f, tween(PhotoTransitionMillis, easing = FastOutSlowInEasing))
        } else {
            morph.snapTo(0f)
        }
    }
    // 返回飞行的 Crop→Fit 缩放比(方形宫格)。从宽高比缓存同步读取(缩略图解码时已写入),
    // 不存在首帧解码竞态。
    val aspect = remember(photo.mediaId) { PhotoAspectCache.get(photo.mediaId) }
    val cropRatio = aspect?.let { cropToFitRatio(it) } ?: 1f
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
            // 缩略图解码即写入宽高比:预览打开瞬间即可拿到 Crop↔Fit 缩放比(缩略图与原图
            // 等比例,aspect 一致)。
            val d = state.result.drawable
            if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                PhotoAspectCache.put(photo.mediaId, d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat())
            }
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
            .sharedElementWithCallerManagedVisibility(
                state,
                visible = sharedVisible,
                boundsTransform = PhotoBoundsTransform,
            )
            .clip(RoundedCornerShape(animatedRadius))
    ) {
        // 占位底色：仅在缩略图未就绪时绘制。已加载的格子不再多画一层背景，减少过度绘制。
        if (!thumbReady) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
        // Fit copy: only composed for the returning cell while its return flight runs
        // (flightActive && morph < 1). It uses the same fixed screen-size request as the
        // preview, so it hits the preview's memory-cache entry immediately instead of
        // re-decoding. It starts as Fit (scale 1, identical to the preview at full-screen) and
        // zooms IN to Crop (scale = cropRatio) as the photo lands — the exact reverse of the
        // open flight, no crossfade.
        if (flightActive && morph.value < 1f) {
            AsyncImage(
                model = remember(photo.uri, previewSize) {
                    ImageRequest.Builder(context).data(photo.uri).size(previewSize).apply {
                        if (photo.mimeType.startsWith("video/")) videoFrameMillis(1000)
                    }.build()
                },
                contentDescription = photo.displayName,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val s = 1f + (cropRatio - 1f) * morph.value
                    scaleX = s
                    scaleY = s
                },
                contentScale = ContentScale.Fit,
            )
        }
        // Crop copy: the resting thumbnail (cell size). Hidden while the Fit copy is up — its
        // transparent letterbox areas would otherwise reveal the Crop image beneath mid-flight —
        // and revealed the instant the flight ends (the Fit copy has zoomed to Crop by then).
        androidx.compose.foundation.Image(
            painter = thumbPainter,
            contentDescription = photo.displayName,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (flightActive && morph.value < 1f) 0f else thumbAlpha),
            contentScale = contentScale,
        )
    }
}

/**
 * Full-screen preview overlay, composed inside the caller's AnimatedVisibility overlay branch
 * and layered ABOVE the caller's always-composed page.
 *
 * The photo area participates in the shared-element system via
 * [SharedTransitionScope.sharedElementWithCallerManagedVisibility] with the CURRENT pager
 * page's photo as the key, so swiping pages only swaps the key without retriggering a
 * transition and closing returns the photo that is actually on screen back to its own grid cell.
 * The key is the stable mediaId ([photoSharedKey]), never the pager index — paging or deleting
 * mid-preview would otherwise mis-match cells (pit 12).
 *
 * Opening: the overlay (scrim/chrome) fades in while the photo continuously grows from the
 * clicked cell's bounds to full-screen (300ms, [PhotoBoundsTransform]) — the user only ever
 * sees one photo, starting exactly at the cell it was tapped in. The header/buttons stay
 * hidden until the flight finishes, then fade in. Closing: first any pinch-zoom snaps back to
 * 1x, then the overlay fades out while the photo continuously shrinks back and lands exactly
 * in the source cell (the caller scrolls the grid so the cell is composed before the exit
 * starts — [revealGridItemIfOffscreen] in App.kt).
 *
 * Two close styles:
 * - 下滑提交(拖过阈值): 照片从松手位置顺势下滑出屏,页面原样露出。预览侧摘掉
 *   sharedElement —— 返回转场没有匹配,照片不缩回宫格,而是由 dragY 驱动滑出屏幕。
 * - 侧滑/系统返回/关闭按钮: 照片经 shared element 连续缩放回位到宫格 cell。
 *
 * The black scrim covers the real page beneath and fades as the photo is dragged down — the
 * page behind brightens from dark.
 *
 * [fullScreenPhotoArea] (回收站/回忆时光机): the photo fills the ENTIRE screen (including under
 * the system bars) and the header/buttons float on top of it. [tapToToggleChrome] lets a single
 * tap on a photo hide/show the header and buttons, and [doubleTapToZoom] makes a double tap zoom
 * 1x↔3x (only zooms back out when already zoomed in). [cellCornerRadius] is the grid cell's
 * corner radius: the photo starts from it when opening and converges to 0 (full-screen).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.SharedPhotoPreview(
    photos: List<PhotoEntity>,
    initialIndex: Int,
    onClose: (PhotoEntity, viaSwipeDown: Boolean) -> Unit,
    /** 关闭流程刚启动(requestClose,缩放回位之前)回调,携带当前照片 mediaId。调用方用它
     *  把「当前 cell 的 sharedKey 顶成 base key、其余 cell 下线」,让返回飞行的配对双方
     *  为 预览(base) ↔ 当前 cell(base)。必须在 visible 翻转之前至少一帧完成。 */
    onCloseStarted: (Long) -> Unit = {},
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
    /** 宫格 cell 的圆角：打开飞行从该圆角收敛到 0。 */
    cellCornerRadius: Dp = 8.dp,
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
    // Shared element key 的两条规则(实测踩坑:见下方 deferred 说明):
    // 1) 预览打开期间 key 钉死在「本次打开的那张照片」上——翻页绝不换 key。若 key 跟着
    //    currentPage 在 settle 中途换成新照片,新 key 会以「可见」注册并匹配到宫格 cell,
    //    触发一次毫无意义的 become-visible 飞行;该飞行把包着 pager 的容器按动画尺寸反复
    //    重新测量,pager 视口随之缩放、snap 重算,于是每次翻页都多跳一页(切两张+放大动画)。
    // 2) 关闭(active→false)那一帧才把 key 换成屏幕上当前的照片——与 visible→false 同帧,
    //    新 key 以「不可见」注册,不会触发飞行;此刻 cell 侧 sharedVisible 翻 true,由 cell
    //    触发正常的返回飞行,精准落回当前照片的格子。
    // During a swipe-out the modifier is removed entirely so the photo keeps sliding out via
    // dragY instead of being yanked into a return flight.
    // 会话基准照片:整个预览生命周期(含关闭)shared key 永远钉在它身上,绝不换 key。
    // remember 不带 key:关闭时 initialIndex 会被调用方置 -1,带 key 会让基准在关闭帧
    // 重算成 items[0] 造成 key 跳变。实例本身随 previewSession 每次打开全新创建。
    val sessionBasePhoto = remember {
        openPhotos.getOrNull(initialIndex.coerceIn(0, (openPhotos.size - 1).coerceAtLeast(0)))
            ?: openPhotos.firstOrNull()
            ?: currentPhoto
    }
    // 关闭时也不把 key 换成当前照片:同帧「key 换手 + visible 翻转」会被系统把翻转配对到
    // 旧 key 上,base cell 会跟着起飞(画面变成点开的那张)。正确的目标配对由调用方在
    // [onCloseStarted] 时把「当前 cell 的 sharedKey 顶成 base key、其余 cell 下线」来完成,
    // 翻转瞬间配对双方 = 预览(base) ↔ 当前 cell(base),唯一一次飞行、内容与落点都正确。
    val sharedState = rememberSharedContentState(photoSharedKey(sessionBasePhoto.mediaId))
    // 打开飞行期间圆角从 cell 半径收敛到 0；关闭飞行由 cell 自己的 clip 负责，预览侧不再渲染。
    val flightVisible = active && !swipeOut
    // 打开方向的内容缩放/圆角 morph:0=Crop(圆角=cell 半径)、1=Fit(圆角=0)。预览一合成就
    // snap 到 0(首帧与宫格完全一致,消除 Crop→Fit 突变 + 直角盖圆角),随后与 bounds 飞行
    // 同步地连续缩放到 Fit、圆角收敛到 0。关闭时 snap 回 1,返回方向的 Crop 化/圆角由宫格侧
    // SharedGridImage 的 morph 与 clip 承担(预览作为退场元素淡出即可)。
    val contentMorph = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            contentMorph.snapTo(0f)
            contentMorph.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        } else {
            contentMorph.snapTo(1f)
        }
    }
    val sharedModifier: Modifier =
        if (swipeOut) {
            Modifier
        } else {
            Modifier
                .sharedElementWithCallerManagedVisibility(
                    sharedState,
                    visible = flightVisible,
                    boundsTransform = PhotoBoundsTransform,
                )
                .clip(RoundedCornerShape(cellCornerRadius * (1f - contentMorph.value)))
        }
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
    // 视频静态帧飞行：打开飞行期间与关闭起飞前，视频页用宫格同款 1s 静态帧参与 shared
    // element（同 cache key、同 Crop↔Fit morph，机制与图片完全同源），飞行落定后才挂载
    // 播放器；关闭时在 requestClose（起飞前）先把播放器换回静态帧——VideoView 未 prepared
    // 的 surface 是纯黑、播放中内容也与宫格缩略图不同源，直接参与飞行会黑块/跳变。
    // 图片路径不受影响；整理页不用本组件的视频分支（无 shared element）。
    var videoStaticFlight by remember { mutableStateOf(true) }
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
            videoStaticFlight = true // 打开飞行用静态帧起跑，落定后再挂播放器
            zoomResetTick++ // 强制当前照片缩回 1x（新打开的照片从 1x 起步）
            pagerState.scrollToPage(initialIndex.coerceIn(0, (openPhotos.size - 1).coerceAtLeast(0)))
            // 飞行落定瞬间标题/按钮立刻开始出现动画（动画时长/曲线不变，只去掉多余的额外延迟）。
            delay(PhotoTransitionMillis.toLong())
            chromeRevealed = true
            // 播放器仍稍晚一拍挂载：等 bounds 动画彻底收尾（静态帧一直盖着，无视觉差异）。
            delay(30L)
            videoStaticFlight = false
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
        // 关闭起飞前先把播放器摘下、换回宫格同款静态帧：返回飞行的画面与宫格 cell 同源，
        // 也顺带停掉播放（VideoView 在换帧的同一帧被移出组合并 stopPlayback）。
        videoStaticFlight = true
        // 先让调用方重排 cell 的 sharedKey(当前 cell 顶上 base key),必须发生在
        // zoomReset → onClose → visible 翻转之前,配对才落到当前 cell 上。
        onCloseStarted(currentPhoto.mediaId)
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

        // ── 照片区域（shared element）──
        // Layout-level offset (not graphicsLayer): the photo follows the drag with the layout.
        // 转场期间（flightVisible）dragY 恒为 0，offset 不影响飞行；下滑提交后摘掉
        // sharedElement，照片改由 offset 滑出屏幕。
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
                        // 飞行期（打开/关闭）用宫格同款静态帧参与转场，落定后挂播放器；
                        // 播放器挂载后静态帧继续盖到 prepared（无缝交棒，不闪黑）。
                        staticFrame = videoStaticFlight,
                        coverWithFrame = true,
                        cropFitProgress = contentMorph.value,
                        bottomInset = videoBarBottomInset,
                        chromeProgress = chromeProgress,
                        chromeExitPx = chromeExitPx,
                        onTap = if (tapToToggleChrome) ({ chromeHidden = !chromeHidden }) else null,
                    )
                } else {
                    ZoomablePhoto(
                        photo = p,
                        enabled = !isTransitionActive,
                        resetTick = zoomResetTick,
                        onResetDone = { if (closePending) onClose(currentPhoto, swipeOut) },
                        placeholderRequest = placeholder,
                        onTap = { if (tapToToggleChrome) chromeHidden = !chromeHidden },
                        doubleTapZoom = doubleTapToZoom,
                        cropFitProgress = contentMorph.value,
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
            // sharedElement 在最外层：飞行期间它把动画尺寸约束交给子内容，子内容铺满。
            Box(
                Modifier
                    .then(sharedModifier)
                    .fillMaxSize()
                    .then(dragOffset)
                    .then(swipeDownModifier)
            ) {
                photoContent()
            }
            // 顶部渐变蒙层：亮色照片上保证标题白字可读（黑色向下淡出，不显眼）。
            // 跟随 chrome 显隐通路：出现时随 chromeRevealAlpha 淡入、隐藏/拖拽/关闭时随
            // chromeProgress 与标题一起飞出；只存在于全屏预览分支（整理页不加渐变底，坑 21）。
            // 画在照片之上、标题行之下；无 pointer 输入，不挡手势（预览根部已有吸收层）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(statusBarTop + 72.dp)
                    // graphicsLayer 必须在 background 之前:background 画在它所在位置,
                    // 放在后面的话 alpha/translation 只作用于 children(空),蒙层就会常驻不隐。
                    .graphicsLayer {
                        alpha = chromeRevealAlpha
                        translationY = -chromeProgress * chromeExitPx
                    }
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.4f),
                            1f to Color.Transparent,
                        )
                    )
            )
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
                        .then(sharedModifier)
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
