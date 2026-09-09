package com.einsli.photoroulette.ui

// 回忆时光机（MemoryViewer）：「N年前的今天」照片宫格 + 全屏预览，与回收站共用宫格/预览结构。
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import coil.size.Size as CoilSize
import kotlin.math.roundToInt
import com.einsli.photoroulette.MemoryInfo

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun MemoryViewer(memory: MemoryInfo?, onBack: () -> Unit) {
    val dc = designColors()
    val photos = memory?.photos ?: emptyList()
    var previewIndex by remember { mutableIntStateOf(-1) }
    val previewOpen = previewIndex in photos.indices
    // 预览会话号（同回收站）：每次打开重 key 整个共享转场布局（新 scope 刷新
    // currentBounds）；关闭时 key 不变，预览在退出期间保持合成让照片飞回宫格。
    var previewSession by remember { mutableIntStateOf(0) }
    // 两段式打开（同回收站）：overlay 比 previewOpen 晚一帧显示，让宫格先 measure。
    var previewVisible by remember { mutableStateOf(false) }
    LaunchedEffect(previewOpen) { previewVisible = previewOpen }
    // 上一次打开/关闭的照片：只有它对应的 cell 在返回飞行期间渲染全屏 Fit 拷贝（同回收站）。
    var flyingMediaId by remember { mutableLongStateOf(-1L) }
    var openedMediaId by remember { mutableLongStateOf(-1L) }
    // 关闭流程进行中（同回收站）：把「该起飞的 cell」的 sharedKey 顶成 base key、其余下线。
    var closing by remember { mutableStateOf(false) }
    // 打开飞行结束（同回收站）：cell 换哑 key → foundMatch=false → 预览从 overlay 落回原位。
    var previewSettled by remember { mutableStateOf(false) }
    // Cell-sized decode target for the 4-column memory grid (same trick as RecycleBin).
    val gridCellPx = with(LocalDensity.current) {
        ((LocalConfiguration.current.screenWidthDp.dp - GridGap * 3).toPx() / 4f).roundToInt()
    }
    val gridThumbSize = remember(gridCellPx) { CoilSize(gridCellPx, gridCellPx) }
    // One grid row = cell + 缝隙（与回收站宫格同排版，GridGap 细缝）; approximates the scroll
    // offset from the first visible item's index, used by the spring pull's limit detection.
    val memoryDensity = LocalDensity.current
    val gridRowPx = remember(gridCellPx, memoryDensity) {
        gridCellPx + with(memoryDensity) { GridGap.toPx() }.roundToInt()
    }
    val scope = rememberCoroutineScope()
    // System back (including the edge-swipe gesture) returns to the home screen. While the
    // preview is open, SharedPhotoPreview's own BackHandler (composed later) closes it first.
    BackHandler(onBack = onBack)
    // 头部悬浮+顶部渐变后内容延伸到状态栏之下：状态栏图标强制白色（同回收站），离开页面恢复。
    val memoryActivity = LocalContext.current as? android.app.Activity
    DisposableEffect(memoryActivity) {
        val window = memoryActivity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { previous?.let { prev -> controller?.isAppearanceLightStatusBars = prev } }
    }
    val gridState = rememberLazyGridState()
    // 视口居中的固定窗口预载（同回收站）：甩动经过的中间位置不进队列。
    GridWindowedThumbnailPreload(gridState, photos, gridThumbSize)
    // 每次打开重 key 整个共享转场布局：新 scope 让所有 shared element 的 currentBounds
    // 清零，打开帧宫格 measure 时刷新到当前位置（同回收站）。
    key(previewSession) {
    PhotoSharedTransitionLayout {
        // 打开飞行结束后置 previewSettled（同回收站）：预览从 overlay 落回原位,chrome 不再被盖。
        LaunchedEffect(previewOpen, previewVisible, isTransitionActive, closing) {
            previewSettled = previewOpen && previewVisible && !isTransitionActive && !closing
        }
        Box(Modifier.fillMaxSize().background(dc.pageBg)) {
            // ── 页面层：常驻组合（同回收站：AnimatedVisibility(visible=true) 只提供 scope）──
            AnimatedVisibility(
                visible = true,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
            ) {
                val statusBarTop = rememberStatusBarTop()
                // 宫格铺满全屏（同回收站）：顶到状态栏、内容从悬浮头部下方穿过；SpringPull
                // 弹性语义与 clipToBounds（坑 17）保持原样，只换了外层布局。
                Box(Modifier.fillMaxSize()) {
                    val gridTopPad = statusBarTop + 56.dp
                    val gridBottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
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
                            Column(
                                Modifier.fillMaxSize().padding(top = gridTopPad),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) { Text("暂无回忆", color = dc.slate) }
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
                                    itemsIndexed(photos) { index, photo ->
                                        Box(
                                            Modifier
                                                .aspectRatio(1f)
                                                .clickable {
                                                    previewSession++
                                                    flyingMediaId = photo.mediaId; openedMediaId = photo.mediaId; closing = false
                                                    previewSettled = false
                                                    previewIndex = index
                                                }
                                        ) {
                                            SharedGridImage(
                                                photo, 0.dp, Modifier.fillMaxSize(),
                                                gridSize = gridThumbSize,
                                                // 关闭帧的 sharedKey 重排(同回收站):该起飞的 cell
                                                // 顶上 base key,其余 cell 换成唯一哑 key。
                                                sharedKey = when {
                                                    closing -> if (photo.mediaId == flyingMediaId) photoSharedKey(openedMediaId)
                                                        else "memoryDummy" + photo.mediaId
                                                    previewSettled -> "memorySettled" + photo.mediaId
                                                    else -> photoSharedKey(photo.mediaId)
                                                },
                                                fitOnEnter = photo.mediaId == flyingMediaId,
                                                sharedVisible = !previewOpen,
                                            )
                                            // 与回收站宫格同款视频角标（左下角播放三角 + 时长）。
                                            GridVideoBadge(photo, Modifier.fillMaxSize())
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // ── 悬浮头部（同回收站）：关闭 X + 标题/副标题改白字浮在宫格上层，
                    //    顶部渐变阴影让穿过的照片有压暗层次、白字始终可读；头部空白吸收触碰。
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
                            .padding(bottom = 48.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) { awaitPointerEvent().changes.forEach { it.consume() } }
                                }
                            },
                    ) {
                        Column(Modifier.padding(top = statusBarTop)) {
                            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onBack) { Icon(Icons.Default.Close, "返回", tint = Color.White) }
                                Column(Modifier.weight(1f)) {
                                    Text("回忆时光机", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    if (memory != null) {
                                        Text(
                                            "${memory.yearsAgo}年前的今天 · ${memory.dateText} · ${memory.count} 张照片",
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.75f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // ── 预览层：overlay（同回收站：AnimatedVisibility 单一常驻实例；visible 用
            //    previewVisible 晚一帧显示让宫格先 measure；关闭时 previewSession 不变，
            //    预览在退出期间保持合成让 shared element 飞回）──
            AnimatedVisibility(
                visible = previewVisible,
                enter = fadeIn(tween(PhotoTransitionMillis)),
                exit = fadeOut(tween(PhotoTransitionMillis)),
                label = "memoryPreview",
            ) {
                if (previewSession > 0) {
                    SharedPhotoPreview(
                        photos = photos,
                        initialIndex = previewIndex,
                        swipeDownToClose = true,
                        sourceThumbSize = gridThumbSize,
                        fullScreenPhotoArea = true,
                        tapToToggleChrome = true,
                        doubleTapToZoom = true,
                        // 宫格已改 4 列密铺直角格子，飞行起点圆角必须与 cell 一致（坑 9 两侧一致）。
                        cellCornerRadius = 0.dp,
                         onCloseStarted = { currentMediaId ->
                             // 同回收站:关闭流程启动时记住要起飞的当前照片并重排 cell key,
                             // 必须赶在 visible 翻转之前完成。
                             flyingMediaId = currentMediaId
                             closing = true
                         },
                        active = previewOpen,
                        onClose = { current, viaSwipeDown ->
                            scope.launch {
                                // 正常关闭先让目标 cell 进入 viewport（若在屏幕外），返回飞行
                                // 才能从全屏连续缩回正确的宫格位置；下滑划走式直接关 overlay。
                                if (!viaSwipeDown) {
                                    flyingMediaId = current.mediaId
                                    val idx = photos.indexOfFirst { it.mediaId == current.mediaId }
                                    if (idx >= 0) revealGridItemIfOffscreen(gridState, idx)
                                }
                                previewIndex = -1
                            }
                        },
                    )
                }
            }
        }
    }
    }
}
