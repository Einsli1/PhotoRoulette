package com.einsli.photoroulette.ui

// 回收站页（RecycleBin）：4 列密铺宫格 + 多选/恢复/彻底删除 + 全屏预览，MIUI 黑底设计稿实现。
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import coil.size.Size as CoilSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.einsli.photoroulette.model.PhotoItem

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable internal fun RecycleBin(items: List<PhotoItem>, trashBytes: Long, viewModel: com.einsli.photoroulette.PhotoViewModel, onRestore: (List<Long>) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    // 选择模式：右上垃圾桶按钮或长按照片进入，X 退出。只要有选中项也视为选择中
    // （沿用旧的「有选中即显示圆圈」行为）。
    var selectMode by remember { mutableStateOf(false) }
    val selecting = selectMode || selected.isNotEmpty()
    var previewIndex by remember { mutableIntStateOf(-1) }
    val previewOpen = previewIndex in items.indices
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
    // Page-level back returns to Settings. While the preview is open, SharedPhotoPreview's own
    // BackHandler (composed later) wins and closes the preview first.
    BackHandler(onBack = onBack)
    // 选择模式下系统返回先退出选择（预览打开时预览自己的 BackHandler 在更后面组合、优先生效）。
    BackHandler(enabled = selecting && !previewOpen) {
        selectMode = false
        selected = emptySet()
    }
    val gridState = rememberLazyGridState()
    // 页面是设计图的 MIUI 黑底：状态栏图标强制白色（无论 App 主题），离开页面恢复。
    val trashActivity = LocalContext.current as? android.app.Activity
    DisposableEffect(trashActivity) {
        val window = trashActivity?.window
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
    // 缩略图解码尺寸 = 显示像素的 70%：解码快 ~2 倍、单张内存省一半，
    // 配合扩容后的内存缓存（见 PhotoRouletteApp），窗口内载过的缩略图滑回来直接命中。
    val thumbPx = remember(gridCellPx) { (gridCellPx * 0.7f).roundToInt() }
    val gridThumbSize = remember(thumbPx) { CoilSize(thumbPx, thumbPx) }
    // One grid row = cell + 缝隙（1.33dp 密铺细缝）; approximates the scroll offset from the
    // first visible item's index, used by the spring pull's limit detection.
    val trashDensity = LocalDensity.current
    val gridRowPx = remember(gridCellPx, trashDensity) {
        gridCellPx + with(trashDensity) { GridGap.toPx() }.roundToInt()
    }
    // 视口居中的固定窗口预载：只在滚动稳定停止后铺「可见区 ± 30 张」，快速甩动与滚动条
    // 拖拽经过的中间位置完全不进队列（详见 [GridWindowedThumbnailPreload]）。
    GridWindowedThumbnailPreload(gridState, items, gridThumbSize)
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
        // 回收站页面 = 设计图的 MIUI 纯黑底（设计图：回收站顶部.jpg）。
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // ── 页面层：常驻组合（AnimatedVisibility(visible=true) 只提供 shared-element scope，
            //    永不进出组合）── 预览开关不再重组合页面：下滑返回时缩略图/滚动原位保留，
            //    没有「重新组合灰格 + 交叉淡化」的整页闪烁。
            AnimatedVisibility(
                visible = true,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
            ) {
                val statusBarTop = rememberStatusBarTop()
                // 设计图（回收站顶部/中间.jpg）：黑底、4 列密铺宫格顶到状态栏，头部悬浮在
                // 宫格上层并带顶部黑色渐变——滚动时照片从头部下面穿过、头部内容透出来。
                Box(Modifier.fillMaxSize()) {
                    val gridTopPad = statusBarTop + 56.dp
                    val gridBottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp
                    // 整页弹性（宫格铺满整屏，头部是 overlay 不占布局），pull 语义与原来一致。
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
                        if (items.isEmpty()) {
                            Column(
                                Modifier.fillMaxSize().navigationBarsPadding(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) { Text("回收站为空", color = Color.White.copy(alpha = 0.6f)) }
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
                                        items,
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
                                                        onLongPress = { selected = if (liveChecked) selected - photo.mediaId else selected + photo.mediaId }
                                                    )
                                                }
                                        ) {
                                            // 关闭帧的 sharedKey 重排:该起飞的 cell 顶上 base key
                                            // (和预览配对),其余 cell 换成唯一哑 key(防 cell 互配)。
                                            val cellSharedKey = when {
                                                closing -> if (photo.mediaId == flyingMediaId) {
                                                    photoSharedKey(openedMediaId)
                                                } else {
                                                    "trashDummy" + photo.mediaId
                                                }
                                                // 打开飞行结束后:预览不再需要和 cell 配对,cell 换唯一哑 key
                                                // → foundMatch=false → 预览从 overlay 落回原位。
                                                previewSettled -> "trashSettled" + photo.mediaId
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
                                            if (selecting) {
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
                                                    TrashSelectionBadge(checked)
                                                }
                                            }
                                        }
                                    }
                                }
                                // 选择模式 interactive=false：滚动条滚动手势不挂载，右缘让给
                                // 每格右下角的圆形选择圈（透明条挂着手势层会挡住整列命中）。
                                GridScrollBar(gridState, items.size, gridRowPx, interactive = !selecting, modifier = Modifier.align(Alignment.CenterEnd))
                            }
                        }
                    }
                    // ── 悬浮头部：普通模式 = 返回‹ + 「回收站/总容量」 + 垃圾桶(进选择模式)；
                    //    选择模式 = X(退出) + 「已选择N项」 + 全选。渐变底按设计图（回收站中间.jpg）
                    //    加高加浓：顶部最深、向下长距离淡出——照片从头下穿过时有清晰的压暗层次，
                    //    白字始终可读。且吸收落在头部空白处的触碰（不误开下面的照片）。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.9f),
                                    0.45f to Color.Black.copy(alpha = 0.6f),
                                    1f to Color.Transparent,
                                )
                            )
                            .padding(bottom = 56.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) { awaitPointerEvent().changes.forEach { it.consume() } }
                                }
                            },
                    ) {
                        Column(Modifier.padding(top = statusBarTop)) {
                            if (selecting) {
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
                                        val allIds = items.map { it.mediaId }.toSet()
                                        selected = if (selected == allIds) emptySet() else allIds
                                    }) { Icon(Icons.Default.DoneAll, "全选", tint = Color.White) }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = onBack) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回", tint = Color.White, modifier = Modifier.size(30.dp))
                                    }
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("回收站", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                        Text(formatCapacity(trashBytes), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                    }
                                    IconButton(onClick = { selectMode = true }) {
                                        Icon(Icons.Outlined.Delete, "选择照片", tint = Color.White)
                                    }
                                }
                            }
                        }
                    }
                    // ── 选择模式的底部居中悬浮深色胶囊：恢复 / 删除（图标上、文字下）。
                    if (selecting) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 20.dp),
                        ) {
                            TrashActionPill(
                                restoreEnabled = selected.isNotEmpty(),
                                deleteEnabled = selected.isNotEmpty(),
                                onRestore = {
                                    val ids = selected.toList()
                                    selected = emptySet()
                                    selectMode = false
                                    onRestore(ids)
                                },
                                onDelete = {
                                    val ids = selected.toList()
                                    selected = emptySet()
                                    selectMode = false
                                    scope.launch { viewModel.deleteFromTrash(ids) }
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
                label = "trashPreview",
            ) {
                if (previewSession > 0) {
                    SharedPhotoPreview(
                        photos = items,
                        initialIndex = previewIndex,
                        swipeDownToClose = true,
                        sourceThumbSize = gridThumbSize,
                        fullScreenPhotoArea = true,
                        tapToToggleChrome = true,
                        doubleTapToZoom = true,
                        cellCornerRadius = 0.dp,
                        // 设计图（照片/视频预览页.jpg）：头部 = 返回‹ + 日期粗体 + 时间小字，
                        // 取 dateTaken；替换默认头部（关闭/文件名/页码）。回忆时光机不传不受影响。
                        customHeader = { current, requestClose ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { requestClose() }) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回", tint = Color.White, modifier = Modifier.size(30.dp))
                                }
                                if (formatTrashDate(current.dateTaken).isEmpty()) {
                                    Text(current.displayName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    Column(Modifier.padding(start = 4.dp)) {
                                        Text(formatTrashDate(current.dateTaken), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text(formatTrashTime(current.dateTaken), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                    }
                                }
                            }
                        },
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
                                    val idx = items.indexOfFirst { it.mediaId == current.mediaId }
                                    if (idx >= 0) revealGridItemIfOffscreen(gridState, idx)
                                }
                                previewIndex = -1
                            }
                        },
                        bottomControls = { current ->
                            // 设计图底部动作：居中深色胶囊（恢复/删除，图标上文字下），替换
                            // 原来的两颗文字按钮。照片即将从列表消失，不飞行回位——bump 会话号
                            // 直接销毁预览。
                            Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                                TrashActionPill(
                                    restoreEnabled = true,
                                    deleteEnabled = true,
                                    onRestore = {
                                        previewVisible = false
                                        previewSession++
                                        previewIndex = -1
                                        onRestore(listOf(current.mediaId))
                                    },
                                    onDelete = {
                                        previewVisible = false
                                        previewSession++
                                        previewIndex = -1
                                        scope.launch { viewModel.deleteFromTrash(listOf(current.mediaId)) }
                                    },
                                )
                            }
                        }
                    )
                }
            }
        }
    }
    }
}

/** 回收站选择模式（设计图：回收站选择.jpg）的选中标识：未选中=半透明白描边空心圈，
 *  已选中=实心蓝底 + 白色对勾（设计图用 MIUI 蓝）。 */
private val TrashSelectionBlue = Color(0xFF3478F6)

/** 宫格缝隙（设计图实测：1200px 全分辨率下缝宽 4px、列宽 297px、屏幕两缘齐边无缝隙，
 *  横向纵向同宽）→ 4px / 3x 密度 = 1.33dp。回收站与回忆时光机宫格共用。 */
internal val GridGap = (4f / 3f).dp

@Composable
private fun TrashSelectionBadge(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(6.dp) // 距 cell 边缘的间距
            .padding(2.dp) // 圆圈外圈留白（也扩大了可点击范围）
            .then(
                if (checked) {
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(TrashSelectionBlue)
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
internal fun GridVideoBadge(photo: PhotoItem, modifier: Modifier = Modifier) {
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
 *  回收站选择模式的底部悬浮条与回收站预览的底部动作共用。 */
@Composable
private fun TrashActionPill(
    restoreEnabled: Boolean,
    deleteEnabled: Boolean,
    onRestore: () -> Unit,
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
        TrashPillAction(Icons.AutoMirrored.Filled.Redo, "恢复", restoreEnabled, onRestore)
        TrashPillAction(Icons.Outlined.Delete, "删除", deleteEnabled, onDelete)
    }
}

@Composable
private fun TrashPillAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
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

/** 回收站预览头部的日期（2022年1月27日）/ 时间（23:51）两行，取 dateTaken。 */
private fun formatTrashDate(taken: Long): String =
    if (taken <= 0L) "" else SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(taken))

private fun formatTrashTime(taken: Long): String =
    if (taken <= 0L) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(taken))
