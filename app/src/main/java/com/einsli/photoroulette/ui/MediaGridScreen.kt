package com.einsli.photoroulette.ui

// MediaGridScreen：回收站（RecycleBin）与回忆时光机（MemoryViewer）共用的
// 「4 列密铺宫格 + 悬浮渐变头部 + 全屏 shared-element 预览」页面骨架。
// 两页约 85% 的结构完全一致——预览状态机（会话号/两段式打开/返回飞行/落定）、
// SpringPull 弹性宫格、视口窗口预载、状态栏白图标、头部触控吸收、选择模式 UI——
// 全部收敛在这里；页面差异通过参数（配色/留白/缩略图解码比例/滚动条）与插槽
// （普通模式头部内容、预览自定义头部）注入，选择模式由 [MediaGridSelection] 一并启用。
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.size.Size as CoilSize
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.einsli.photoroulette.model.PhotoItem

/**
 * 选择模式配置：非 null 时为宫格页启用长按多选 + 右下角选择圈 + 头部选择条
 * （X / 已选择N项 / 全选）+ 底部动作胶囊，并给全屏预览挂上单张动作胶囊
 * （同一套动作）。
 *
 * [onDelete] 在用户确认动作时由页面调用，两个页面的「删除」语义不同但都从这一路走：
 * 回收站 = 彻底删除，回忆时光机 = 移入系统回收站（URI 请求由实现方自理的异步流程处理）。
 *
 * [onRestore] 只在有「恢复」语义的页面给（回收站）：为 null 时动作胶囊只显示删除
 * （回忆时光机没有恢复可言），样式与回收站完全一致，只是少一颗按钮。
 *
 * 异步由实现方自理——ViewModel 内部自启 coroutine，页面不再额外包装。
 */
internal class MediaGridSelection(
    val onDelete: (List<Long>) -> Unit,
    val onRestore: ((List<Long>) -> Unit)? = null,
)

/** 宫格选择模式的选中标识色（设计图用 MIUI 蓝）。 */
private val MediaSelectionBlue = Color(0xFF3478F6)

/** 宫格缝隙（设计图实测：1200px 全分辨率下缝宽 4px、列宽 297px、屏幕两缘齐边无缝隙，
 *  横向纵向同宽）→ 4px / 3x 密度 = 1.33dp。回收站与回忆时光机宫格共用。 */
internal val GridGap = (4f / 3f).dp

/**
 * 4 列密铺宫格 + 悬浮渐变头部 + 全屏 shared-element 预览的共享页面骨架
 * （回收站/回忆时光机；选择模式由 [selection] 启用，见 [MediaGridSelection]）。
 *
 * 页面差异通过参数注入：
 *  - 外观：[pageBackground]、[emptyText]、[emptyTextColor]、[gridBottomPadding]、
 *    [headerBottomPadding]、[thumbDecodeScale]、[dummyKeyPrefix]、[scrollBar]；
 *  - 头部：[headerContent]（普通模式内容，参数 = 进入选择模式回调；未启用选择模式时为
 *    null）；选择模式头部由本组件内置。
 *  - 预览：[previewHeader] 自定义头部；底部「恢复/删除」胶囊在选择模式启用时自动挂载。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun MediaGridScreen(
    photos: List<PhotoItem>,
    onBack: () -> Unit,
    /** 页面底色：回收站 = 设计图 MIUI 纯黑，回忆时光机 = 主题 pageBg。 */
    pageBackground: Color,
    /** 空态文案与文字色。 */
    emptyText: String,
    emptyTextColor: Color,
    /** 宫格内容底部留白（导航栏高度之外）：回收站给底部选择胶囊留 96dp，回忆 16dp。 */
    gridBottomPadding: Dp,
    /** 悬浮头部渐变向下延伸距离：回收站 56dp（设计图加高加浓），回忆 48dp。 */
    headerBottomPadding: Dp,
    /** shared element 哑 key 前缀（每页唯一，回收站 trash / 回忆 memory）：关闭帧给
     *  非飞行 cell 换的唯一 key，防止 cell 互相配对 / 与预览串扰。 */
    dummyKeyPrefix: String,
    /** 缩略图解码尺寸 = 宫格显示像素 × 该系数（回收站 0.7：解码快、单张内存省一半，
     *  配合扩容后的内存缓存滑回来直接命中；回忆 1.0）。 */
    thumbDecodeScale: Float = 1f,
    /** 是否显示右缘滚动条（回收站 true；选择模式下自动改为非交互）。 */
    scrollBar: Boolean = false,
    /** 选择模式配置，null = 不启用。回收站（恢复/彻底删除）与回忆时光机（移入回收站）共用。 */
    selection: MediaGridSelection? = null,
    /** 普通模式头部内容。参数 = 进入选择模式的回调（未启用选择模式时为 null）。
     *  选择模式下的头部（X/已选择N项/全选）由本组件内置，与普通模式头部互斥。 */
    headerContent: @Composable (enterSelection: (() -> Unit)?) -> Unit,
    /** 预览自定义头部（回收站的日期头部）：参数 = 当前照片 + requestClose。null = 默认头部
     *  （关闭/文件名/页码）。 */
    previewHeader: (@Composable (current: PhotoItem, requestClose: () -> Unit) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val selectionCfg = selection
    // 选择模式：右上垃圾桶按钮或长按照片进入，X 退出。只要有选中项也视为选择中
    // （沿用旧的「有选中即显示圆圈」行为）。
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    // selectionCfg == null 时 selectMode/selected 恒为 false/空，selectionActive 恒为 false。
    val selectionActive = selectMode || selected.isNotEmpty()
    val selecting = selectionCfg != null && selectionActive
    var previewIndex by remember { mutableIntStateOf(-1) }
    val previewOpen = previewIndex in photos.indices
    // 预览会话号：每次打开 +1。用它给预览内容做 key —— 关闭时 key 不变，预览内容在
    // AnimatedVisibility 退出期间保持合成（shared element 才能连续飞回宫格）；再次打开时
    // key 变化，预览以「本次点击的照片」为初始页全新合成。恢复/删除按钮 +1 则直接销毁
    // 预览（不走飞行回位，照片即将从列表消失）。
    var previewSession by remember { mutableIntStateOf(0) }
    // 预览 overlay 的可见性比 previewOpen 晚一帧翻转（两段式打开）：每次打开都用
    // previewSession 重 key 整个 SharedTransitionLayout（全新 scope，currentBounds 清零），
    // 第一帧先让宫格 cell 在新 scope 里 measure（currentBounds 刷新到当前位置——否则滚动后
    // 的 cell 会用首次合成位置起飞），第二帧再合成预览——此时 currentBounds 已就位，
    // 预览的 owned transition 才能 false→true 跑起来触发飞行。
    var previewVisible by remember { mutableStateOf(false) }
    LaunchedEffect(previewOpen) { previewVisible = previewOpen }
    // 返回飞行要「起飞」的照片:打开时=点开的那张;关闭流程启动后=屏幕上当前那张
    // (onCloseStarted 回调刷新)。只有它对应的 cell 渲染全屏 Fit 拷贝(fitOnEnter)。
    var flyingMediaId by remember { mutableLongStateOf(-1L) }
    // 本次预览打开的那张(base):预览侧 shared key 钉在它身上;关闭帧「该起飞的 cell」
    // 的 sharedKey 也要顶成它,才能和预览配对。
    var openedMediaId by remember { mutableLongStateOf(-1L) }
    // 关闭流程进行中(requestClose 已触发、visible 尚未翻转):此窗口内把「该起飞的
    // cell」的 sharedKey 顶成 base key、其余 cell 换成唯一哑 key——翻转瞬间配对双方 =
    // 预览(base) ↔ 当前 cell(base),只有一次飞行,内容与落点都是当前照片。
    // 不重排的话,同帧「key 换手 + visible 翻转」会把翻转配到旧 base key 上,base cell
    // 会跟着起飞(画面变成点开的那张),当前 cell 只能拿到退化匹配在自己格子里 morph。
    var closing by remember { mutableStateOf(false) }
    // 打开飞行结束(预览已稳定、不再飞行):把 cell 换成唯一哑 key → foundMatch=false → 预览从
    // shared-transition overlay 落回原位(chrome 不再被照片盖住)。关闭时 closing 分支优先,
    // 重新配对返回飞行。
    var previewSettled by remember { mutableStateOf(false) }
    // 预览内恢复/删除：照片即将从列表消失，不飞行回位——bump 会话号直接销毁预览。
    fun destroyPreview() {
        previewVisible = false
        previewSession++
        previewIndex = -1
    }
    // Page-level back returns to the parent page. While the preview is open, SharedPhotoPreview's
    // own BackHandler (composed later) wins and closes the preview first.
    BackHandler(onBack = onBack)
    // 选择模式下系统返回先退出选择（预览打开时预览自己的 BackHandler 在更后面组合、优先生效）。
    if (selectionCfg != null) {
        BackHandler(enabled = selecting && !previewOpen) {
            selectMode = false
            selected = emptySet()
        }
    }
    val gridState = rememberLazyGridState()
    // 悬浮头部的实测高度（= statusBarTop + 头部内容 + headerBottomPadding，也就是头部那条
    // 「整层吞触碰」的吸收层高度）：滚动条轨道上端从它下沿开始，否则药丸滑到最顶上时整颗
    // 埋在头部渐变最深处、又落在吸收层里——看得见抓不到（真机反馈）。实测而不是按
    // statusBarTop + 56dp + headerBottomPadding 算：头部内容是页面插槽，高度由页面决定。
    var headerHeightPx by remember { mutableIntStateOf(0) }
    // 页面头部悬浮+顶部渐变后内容延伸到状态栏之下：状态栏图标强制白色（无论 App 主题），
    // 离开页面恢复。
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { previous?.let { prev -> controller?.isAppearanceLightStatusBars = prev } }
    }
    // Cell-sized decode target for grid thumbnails: 4 columns with GridGap seams, so
    // ~(screenWidth - 3 gaps)/4 px. Fixing the request size keeps every cell's memory-cache
    // entry identical and small, so fast scrolling re-shows already-loaded photos instantly
    // instead of re-decoding.
    val gridCellPx = with(LocalDensity.current) {
        ((LocalConfiguration.current.screenWidthDp.dp - GridGap * 3).toPx() / 4f).roundToInt()
    }
    val thumbPx = remember(gridCellPx, thumbDecodeScale) { (gridCellPx * thumbDecodeScale).roundToInt() }
    val gridThumbSize = remember(thumbPx) { CoilSize(thumbPx, thumbPx) }
    // One grid row = cell + 缝隙（1.33dp 密铺细缝）; approximates the scroll offset from the
    // first visible item's index, used by the spring pull's limit detection.
    val density = LocalDensity.current
    val gridRowPx = remember(gridCellPx, density) {
        gridCellPx + with(density) { GridGap.toPx() }.roundToInt()
    }
    // 视口居中的固定窗口预载：只在滚动稳定停止后铺「可见区 ± 30 张」，快速甩动与滚动条
    // 拖拽经过的中间位置完全不进队列（详见 [GridWindowedThumbnailPreload]）。
    GridWindowedThumbnailPreload(gridState, photos, gridThumbSize)
    // 每次打开重 key 整个共享转场布局：全新 scope 让所有 shared element 的 currentBounds
    // 清零，打开帧宫格 measure 时刷新到当前位置——否则「滚动后再点」的飞行会从 cell 首次
    // 合成的位置起飞（见 previewVisible 的注释）。
    key(previewSession) {
    PhotoSharedTransitionLayout {
        // 打开飞行结束(预览已可见、isTransitionActive 回 false)后置 previewSettled:所有 cell
        // 换成唯一哑 key → foundMatch=false → 预览从 shared-transition overlay 落回原位,
        // 标题/按钮才浮在照片上层(不再被照片盖住)。
        LaunchedEffect(previewOpen, previewVisible, isTransitionActive, closing) {
            previewSettled = previewOpen && previewVisible && !isTransitionActive && !closing
        }
        Box(Modifier.fillMaxSize().background(pageBackground)) {
            // ── 页面层：常驻组合（AnimatedVisibility(visible=true) 只提供 shared-element scope，
            //    永不进出组合）── 预览开关不再重组合页面：下滑返回时缩略图/滚动原位保留，
            //    没有「重新组合灰格 + 交叉淡化」的整页闪烁。
            AnimatedVisibility(
                visible = true,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
            ) {
                val statusBarTop = rememberStatusBarTop()
                // 宫格铺满整屏（4 列密铺顶到状态栏），头部悬浮在宫格上层并带顶部黑色渐变——
                // 滚动时照片从头部下面穿过、头部内容透出来。
                Box(Modifier.fillMaxSize()) {
                    val gridTopPad = statusBarTop + 56.dp
                    val gridBottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + gridBottomPadding
                    // 整页弹性（宫格铺满整屏，头部是 overlay 不占布局），pull 语义两页一致。
                    // clipToBounds 让弹出的宫格只在页面边界内移动。
                    SpringPullBox(
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                        pullAtTop = { (gridState.firstVisibleItemIndex * gridRowPx + gridState.firstVisibleItemScrollOffset).toFloat().coerceAtLeast(0f) },
                        pullAtBottom = {
                            val info = gridState.layoutInfo
                            val last = info.visibleItemsInfo.lastOrNull()
                            if (last == null || last.index < info.totalItemsCount - 1) {
                                // More content below the viewport: still scrollable, no bottom pull.
                                Float.MAX_VALUE
                            } else {
                                val contentEnd = (last.offset.y + last.size.height + info.afterContentPadding).toFloat()
                                (contentEnd - info.viewportEndOffset.toFloat()).coerceAtLeast(0f)
                            }
                        },
                    ) {
                        if (photos.isEmpty()) {
                            // 空态：头部之下、导航栏之上居中（两页统一）。
                            Column(
                                Modifier.fillMaxSize().navigationBarsPadding().padding(top = gridTopPad),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) { Text(emptyText, color = emptyTextColor) }
                        } else {
                            Box(Modifier.fillMaxSize()) {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(4),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(GridGap),
                                    verticalArrangement = Arrangement.spacedBy(GridGap),
                                    contentPadding = PaddingValues(top = gridTopPad, bottom = gridBottomPad),
                                    flingBehavior = rememberGentleFlingBehavior()
                                ) {
                                    itemsIndexed(
                                        photos,
                                        key = { _, photo -> photo.mediaId },
                                        contentType = { _, photo -> if (photo.mimeType.startsWith("video/")) "video" else "image" },
                                    ) { index, photo ->
                                        val checked = selected.contains(photo.mediaId)
                                        // Live copy of `checked`: pointerInput does NOT restart when
                                        // selection changes, so the long-press handler must read the
                                        // latest value through a live state.
                                        val liveChecked by rememberUpdatedState(checked)
                                        Box(
                                            Modifier
                                                .aspectRatio(1f)
                                                .pointerInput(photo.mediaId) {
                                                    detectTapGestures(
                                                        onTap = {
                                                            previewSession++
                                                            flyingMediaId = photo.mediaId
                                                            openedMediaId = photo.mediaId
                                                            closing = false
                                                            previewSettled = false
                                                            previewIndex = index
                                                        },
                                                        onLongPress = if (selectionCfg != null) {
                                                            {
                                                                selected = if (liveChecked) selected - photo.mediaId else selected + photo.mediaId
                                                            }
                                                        } else {
                                                            null
                                                        },
                                                    )
                                                }
                                        ) {
                                            // 关闭帧的 sharedKey 重排:该起飞的 cell 顶上 base key
                                            // (和预览配对),其余 cell 换成唯一哑 key(防 cell 互配)。
                                            val cellSharedKey = when {
                                                closing -> if (photo.mediaId == flyingMediaId) {
                                                    photoSharedKey(openedMediaId)
                                                } else {
                                                    "$dummyKeyPrefix" + "Dummy" + photo.mediaId
                                                }
                                                // 打开飞行结束后:预览不再需要和 cell 配对,cell 换唯一哑 key
                                                // → foundMatch=false → 预览从 overlay 落回原位。
                                                previewSettled -> "$dummyKeyPrefix" + "Settled" + photo.mediaId
                                                else -> photoSharedKey(photo.mediaId)
                                            }
                                            SharedGridImage(
                                                photo, 0.dp, Modifier.fillMaxSize(),
                                                gridSize = gridThumbSize,
                                                sharedKey = cellSharedKey,
                                                // 只有正在飞回的那张 cell 订阅转场状态并渲染全屏 Fit 拷贝。
                                                fitOnEnter = photo.mediaId == flyingMediaId,
                                                // 预览打开期间 cell 退出「目标态」竞争：飞行目标只能有一个。
                                                sharedVisible = !previewOpen,
                                            )
                                            // 设计图视频角标：左下角小播放三角 + 时长（无居中大播放钮）。
                                            GridVideoBadge(photo, Modifier.fillMaxSize())
                                            // 右下角圆形选择圈：选择模式下所有格子都显示（未选=半透明
                                            // 白描边空心圈、已选=实心蓝勾），点圆圈切换选中（不触发
                                            // 预览），照片本身不变色。
                                            if (selectionActive) {
                                                Box(
                                                    Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .clickable(
                                                            interactionSource = remember { MutableInteractionSource() },
                                                            indication = null,
                                                        ) {
                                                            selected = if (checked) selected - photo.mediaId else selected + photo.mediaId
                                                        },
                                                ) {
                                                    MediaSelectionBadge(checked)
                                                }
                                            }
                                        }
                                    }
                                }
                                // 选择模式 interactive=false：滚动条滚动手势不挂载，右缘让给
                                // 每格右下角的圆形选择圈（透明条挂着手势层会挡住整列命中）。
                                if (scrollBar) {
                                    GridScrollBar(
                                        gridState, photos.size, gridRowPx,
                                        interactive = !selecting,
                                        // 轨道上端 = 悬浮头部「吸收层」的下沿 = 第一排宫格顶边
                                        // （+ 8dp 余量让药丸不贴着照片边）。
                                        // 必须减掉 headerBottomPadding：headerHeightPx 实测的是
                                        // 整个头部 Box（含 headerBottomPadding —— 那是渐变向下
                                        // 延伸的距离，只是画在宫格上的一层透明渐变，不是头部实际
                                        // 占位）；吸收层是 .padding() 之后那个 pointerInput 节点，
                                        // 高度 = headerHeightPx − headerBottomPadding，下沿正好落在
                                        // 宫格第一排顶边。不减的话药丸最上位停在第一排顶边往下
                                        // 56dp 处（真机实测：药丸顶 483px vs 第一排顶 310px）。
                                        // coerceAtLeast(0.dp) 不能省：首帧 headerHeightPx 还是 0，
                                        // 0 − 56dp + 8dp 会算出负 padding，
                                        // `.padding()` 直接抛 IllegalArgumentException 崩页
                                        // （2026-09-11 真机：进回收站闪退）。
                                        trackTopInset = (with(density) { headerHeightPx.toDp() } -
                                            headerBottomPadding + 8.dp).coerceAtLeast(0.dp),
                                        modifier = Modifier.align(Alignment.CenterEnd),
                                    )
                                }
                            }
                        }
                    }
                    // ── 悬浮头部：普通模式 = 调用方内容（headerContent）；选择模式 = X(退出) +
                    //    「已选择N项」 + 全选。渐变底按设计图加高加浓：顶部最深、向下长距离
                    //    淡出——照片从头下穿过时有清晰的压暗层次，白字始终可读。且吸收落在
                    //    头部空白处的触碰（不误开下面的照片）。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // 头部实测高度 → 滚动条轨道上端（见 headerHeightPx 的注释）。
                            .onSizeChanged { headerHeightPx = it.height }
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.9f),
                                    0.45f to Color.Black.copy(alpha = 0.6f),
                                    1f to Color.Transparent,
                                )
                            )
                            .padding(bottom = headerBottomPadding)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) { awaitPointerEvent().changes.forEach { it.consume() } }
                                }
                            },
                    ) {
                        Column(Modifier.padding(top = statusBarTop)) {
                            if (selectionCfg != null && selectionActive) {
                                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { selectMode = false; selected = emptySet() }) {
                                        Icon(Icons.Default.Close, "退出选择", tint = Color.White)
                                    }
                                    Text(
                                        "已选择${selected.size}项",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // 设计图此处是排序图标；无排序功能，映射为全选（设计映射）。
                                    IconButton(onClick = {
                                        val allIds = photos.map { it.mediaId }.toSet()
                                        selected = if (selected == allIds) emptySet() else allIds
                                    }) { Icon(Icons.Default.DoneAll, "全选", tint = Color.White) }
                                }
                            } else {
                                val enterSelection: (() -> Unit)? = if (selectionCfg != null) {
                                    { selectMode = true }
                                } else {
                                    null
                                }
                                headerContent(enterSelection)
                            }
                        }
                    }
                    // ── 选择模式的底部居中悬浮深色胶囊：恢复 / 删除（图标上、文字下）。
                    //    没有恢复语义的页面（回忆时光机）只出删除一颗，样式不变。
                    if (selectionCfg != null && selectionActive) {
                        val ids = selected.toList()
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 20.dp),
                        ) {
                            MediaActionPill(
                                restoreEnabled = selected.isNotEmpty(),
                                deleteEnabled = selected.isNotEmpty(),
                                onRestore = selectionCfg.onRestore?.let { restore ->
                                    {
                                        selectMode = false
                                        selected = emptySet()
                                        restore(ids)
                                    }
                                },
                                onDelete = {
                                    selectMode = false
                                    selected = emptySet()
                                    selectionCfg.onDelete(ids)
                                },
                            )
                        }
                    }
                }
            }
            // ── 预览层：overlay（AnimatedVisibility 单一常驻实例，不随开关销毁重建——
            //    快速「关闭再点开」只是 visible 翻转，shared-element state 不会反复
            //    add/remove，避免飞行卡死在源 bounds / isTransitionActive 悬挂的卡死）──
            // visible 用 previewVisible（比 previewOpen 晚一帧）：让新 scope 的宫格先
            // measure 刷新 currentBounds，预览合成时飞行才能从正确的 cell 起飞。
            // 关闭时 previewSession 不变，预览内容在退出期间保持合成，shared element
            // 才能连续飞回宫格。
            AnimatedVisibility(
                visible = previewVisible,
                enter = fadeIn(tween(PhotoTransitionMillis)),
                exit = fadeOut(tween(PhotoTransitionMillis)),
                label = "${dummyKeyPrefix}Preview",
            ) {
                if (previewSession > 0) {
                    // 选择模式启用时给预览挂单张动作胶囊（设计图底部动作）：照片即将从列表
                    // 消失，先 bump 会话号直接销毁预览（不飞行回位）再执行动作。没有恢复语义
                    // 的页面（回忆时光机）只出删除一颗。
                    val previewActions: (@Composable (current: PhotoItem) -> Unit)? =
                        if (selectionCfg != null) {
                            { current ->
                                Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                                    MediaActionPill(
                                        restoreEnabled = true,
                                        deleteEnabled = true,
                                        onRestore = selectionCfg.onRestore?.let { restore ->
                                            { destroyPreview(); restore(listOf(current.mediaId)) }
                                        },
                                        onDelete = { destroyPreview(); selectionCfg.onDelete(listOf(current.mediaId)) },
                                    )
                                }
                            }
                        } else {
                            null
                        }
                    SharedPhotoPreview(
                        photos = photos,
                        initialIndex = previewIndex,
                        swipeDownToClose = true,
                        sourceThumbSize = gridThumbSize,
                        fullScreenPhotoArea = true,
                        tapToToggleChrome = true,
                        doubleTapToZoom = true,
                        // 宫格是 4 列密铺直角格子，飞行起点圆角必须与 cell 一致（坑 9 两侧一致）。
                        cellCornerRadius = 0.dp,
                        customHeader = previewHeader,
                        active = previewOpen,
                        onCloseStarted = { currentMediaId ->
                            // 关闭流程启动(缩放回位之前):记住要起飞的当前照片,并把 cell 的
                            // sharedKey 重排好(当前 cell 顶 base key、其余下线)——必须赶在
                            // visible 翻转之前完成。
                            flyingMediaId = currentMediaId
                            closing = true
                        },
                        onClose = { current, viaSwipeDown ->
                            scope.launch {
                                // 正常关闭（侧滑/系统返回/关闭按钮）：先记录目标照片并让它的
                                // cell 进入 viewport（若在屏幕外），返回飞行才能从全屏连续缩回
                                // 正确的宫格位置；下滑划走式关闭照片已滑出屏幕，直接关 overlay。
                                if (!viaSwipeDown) {
                                    flyingMediaId = current.mediaId
                                    val idx = photos.indexOfFirst { it.mediaId == current.mediaId }
                                    if (idx >= 0) revealGridItemIfOffscreen(gridState, idx)
                                }
                                previewIndex = -1
                            }
                        },
                        bottomControls = previewActions,
                    )
                }
            }
        }
    }
    }
}

/** 宫格选择模式（设计图：回收站选择.jpg）的选中标识：未选中=半透明白描边空心圈，
 *  已选中=实心蓝底 + 白色对勾（设计图用 MIUI 蓝）。 */
@Composable
private fun MediaSelectionBadge(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(6.dp) // 距 cell 边缘的间距
            .padding(2.dp) // 圆圈外圈留白（也扩大了可点击范围）
            .then(
                if (checked) {
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MediaSelectionBlue)
                } else {
                    Modifier
                        .size(22.dp)
                        .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Default.Check,
                contentDescription = "已选中",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 4 列密铺宫格的视频角标（设计图：左下角小播放三角 + 时长，白字，无底色）。
 *  视频格才渲染；必须放在 shared element 的兄弟层，不随转场缩放。 */
@Composable
private fun GridVideoBadge(photo: PhotoItem, modifier: Modifier = Modifier) {
    if (!photo.mimeType.startsWith("video/")) return
    Box(modifier) {
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "视频",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            if (photo.duration > 0) {
                Text(
                    formatDuration(photo.duration),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 1.dp),
                )
            }
        }
    }
}

/** 设计图同款深色胶囊动作条（半透明黑、大圆角）：图标在上、文字在下。
 *  宫格选择模式的底部悬浮条与全屏预览的底部动作共用。
 *  [onRestore] 为 null 时只渲染删除一颗（回忆时光机没有恢复语义），胶囊样式不变。 */
@Composable
private fun MediaActionPill(
    restoreEnabled: Boolean,
    deleteEnabled: Boolean,
    onRestore: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 36.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(44.dp),
    ) {
        if (onRestore != null) {
            MediaPillAction(Icons.AutoMirrored.Filled.Redo, "恢复", restoreEnabled, onRestore)
        }
        MediaPillAction(Icons.Outlined.Delete, "删除", deleteEnabled, onDelete)
    }
}

@Composable
private fun MediaPillAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = alpha), modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White.copy(alpha = alpha), fontSize = 12.sp)
    }
}