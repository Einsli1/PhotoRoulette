package com.einsli.photoroulette.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.core.view.WindowCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.Disposable
import coil.request.videoFrameMillis
import coil.size.Size as CoilSize
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import com.einsli.photoroulette.AppUiState
import com.einsli.photoroulette.PhotoViewModel
import com.einsli.photoroulette.MemoryInfo
import com.einsli.photoroulette.ReviewSession
import com.einsli.photoroulette.data.AppSettings
import com.einsli.photoroulette.data.PhotoEntity
import com.einsli.photoroulette.data.PhotoState

/** Pages that hide the bottom navigation bar (immersive): Review (2), RecycleBin (3) and
 *  MemoryViewer (5) are standalone pages entered via a dedicated button. */
private val immersivePages = setOf(2, 3, 5)

/** 底部 Tab 栏的页面顺序:首页(0) / 统计(4) / 设置(1) —— pager 索引 ↔ page 值的映射。 */
private val tabPages = listOf(0, 4, 1)

/**
 * Where a page "comes from" on the screen, used as the scale transform origin for the
 * page transition. The origin matches the position of the entry button on the source
 * page: Review (2) opens from the middle (今日任务's 继续整理 button), RecycleBin (3)
 * from the upper area (设置's 回收站 row), MemoryViewer (5) from the lower part of the
 * screen (回忆时光机 card). Other pages zoom from the center.
 */
private fun pageTransformOrigin(page: Int): TransformOrigin = when (page) {
    2 -> TransformOrigin(0.5f, 0.35f)
    3 -> TransformOrigin(0.5f, 0.3f)
    5 -> TransformOrigin(0.5f, 0.75f)
    else -> TransformOrigin(0.5f, 0.5f)
}

@Composable fun PhotoRouletteApp(viewModel: PhotoViewModel, onAction: (Long, PhotoState, Int, Long) -> Boolean, onCommitDeletes: () -> Unit, onRestoreFromTrash: (List<Long>) -> Unit, openReviewRequest: Int = 0) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    // Collected at the app level so the value is already loaded when the RecycleBin opens.
    val trashItems by viewModel.trashItems.collectAsStateWithLifecycle(emptyList())
    var page by rememberSaveable { mutableIntStateOf(if (openReviewRequest > 0) 2 else 0) }
    // ── 底部 Tab 左右滑动切换(微信式)。page 是唯一状态源,pager 只有两条方向相反的
    //    写入通路,各自的 guard 吸收反向写入,不形成回环、不叠加第二套动画:
    //    1) 滑动:pager.currentPage 越过中线那一帧 → page = 目标 Tab(底部栏选中态随动);
    //    2) 点击 Tab:navigate() 往 tabClicks 投递目标索引,独立长循环里 animateScrollToPage。
    //    点击动画绝不能放在 LaunchedEffect(page) 里:跨越中间 Tab 的动画途中,通路 1 改写
    //    page 会重启该效果、取消进行中的动画协程 —— pager 冻结在两页之间(首页⇄设置点切换
    //    必卡死在中间,即此坑)。channel 串行排队:动画中的再点击接续执行,永不半途取消。
    val pagerState = rememberPagerState(initialPage = tabPages.indexOf(page).coerceAtLeast(0)) { tabPages.size }
    val tabClicks = remember { Channel<Int>(Channel.CONFLATED) }
    LaunchedEffect(pagerState) {
        for (idx in tabClicks) {
            if (idx < 0) continue // 沉浸页期间 pager 保持原位:总是从当前 Tab 打开,返回露出的就是它
            if (pagerState.targetPage == idx || pagerState.currentPage == idx) continue
            pagerState.animateScrollToPage(idx)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { idx ->
            val p = tabPages.getOrNull(idx) ?: return@collect
            if (page in tabPages && page != p) page = p
        }
    }
    // 通知/系统闹钟点击后直达整理页(openReviewRequest 由 MainActivity 递增)。冷启动时初始 page
    // 已落在 2 上,这里只负责后续的再次导航;无进行中会话时和首页「开始整理」一样重建一个。
    LaunchedEffect(openReviewRequest) {
        if (openReviewRequest == 0) return@LaunchedEffect
        val inProgress = state.session != null && state.remaining > 0
        if (!inProgress) viewModel.startSession() else viewModel.reconcileQuietly()
        page = 2
    }
    // Hoisted so the Settings scroll position survives navigating away and back.
    val settingsScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    // Manual snapshot of the Settings scroll value: AnimatedContent re-composes the page, and
    // the restored ScrollState can be clamped before layout, so we re-apply the value on return.
    var savedSettingsScroll by rememberSaveable { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    val darkMode = state.settings.darkMode
    val isDark = when (darkMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    // Only use wallpaper-based dynamic color when the user hasn't overridden the theme.
    val useDynamic = darkMode == 0
    // Keep the system-bar icon color in sync with the *app's* resolved theme (not just the
    // system's), so the status/navigation bars never show dark icons on a dark page (or the
    // reverse) and blend seamlessly with the page background.
    val activity = LocalContext.current as? android.app.Activity
    if (activity != null) {
        SideEffect {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }
    fun navigate(newPage: Int) {
        // Snapshot the Settings scroll position before leaving so it can be restored exactly
        // (the ScrollState alone drifts because it gets clamped before layout on re-entry).
        if (page == 1) savedSettingsScroll = settingsScroll.value
        if (page != newPage) {
            page = newPage
            tabClicks.trySend(tabPages.indexOf(newPage))
        }
    }
    PhotoRouletteTheme(dark = isDark, dynamicColor = useDynamic) {
        val dc = designColors()
        Scaffold(
            containerColor = dc.pageBg,
            bottomBar = {
                // Review (2) and MemoryViewer (5) are immersive: no bottom navigation. The slot
                // must keep its 85dp height there too (transparent spacer): dropping the bar
                // re-issues smaller Scaffold padding in the SAME frame, the still-visible page's
                // viewport grows, its scroll state clamps (max drops) and the whole content
                // visibly jumps down right as the zoom cover starts (设置页跳一下).
                if (page in immersivePages) {
                    Spacer(Modifier.height(85.dp))
                } else {
                    // Slightly slimmer than the 80dp default so the content area stays roomy,
                    // but tall enough that the icons and labels never clip.
                    NavigationBar(
                        containerColor = dc.navBar,
                        tonalElevation = 0.dp,
                        modifier = Modifier.height(85.dp)
                    ) {
                        val itemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = dc.accentText,
                            selectedTextColor = dc.ink,
                            indicatorColor = dc.navIndicator,
                            unselectedIconColor = dc.labelGray,
                            unselectedTextColor = dc.labelGray,
                        )
                        NavigationBarItem(
                            selected = page == 0, onClick = { navigate(0) },
                            icon = { Icon(Icons.Default.Home, null, modifier = Modifier.size(24.dp)) },
                            label = { Text("首页", fontSize = 11.sp) }, colors = itemColors
                        )
                        NavigationBarItem(
                            selected = page == 4, onClick = { navigate(4) },
                            icon = { Icon(Icons.Default.Info, null, modifier = Modifier.size(24.dp)) },
                            label = { Text("统计", fontSize = 11.sp) }, colors = itemColors
                        )
                        NavigationBarItem(
                            selected = page == 1, onClick = { navigate(1) },
                            icon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(24.dp)) },
                            label = { Text("设置", fontSize = 11.sp) }, colors = itemColors
                        )
                    }
                }
            }
        ) { padding ->
            // 显式单根 Box:Scaffold 内容只认一个根;Tab 层在下、沉浸层在上。
            Box(Modifier.fillMaxSize()) {
                // ── Tab 层:首页/统计/设置住在一个 HorizontalPager 里,左右滑动即切换。 ──
                // 手势滑动由 pager 连续驱动(跟手、不足回弹、两端自停);唯一的 Tab 状态是
                // page,同步通路只有上方两条,不会叠加第二套动画,也不会互相打架。
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    // 三页全部常驻组合:滑走再滑回时各页滚动/展开状态零丢失,
                    // 邻页也无需在滑动中首次组合(避免掉帧)。
                    beyondViewportPageCount = tabPages.lastIndex,
                ) { pagerIndex ->
                    PageContent(
                        page = tabPages[pagerIndex],
                        state = state,
                        viewModel = viewModel,
                        settingsScroll = settingsScroll,
                        savedSettingsScroll = savedSettingsScroll,
                        trashItems = trashItems,
                        onAction = onAction,
                        onCommitDeletes = onCommitDeletes,
                        onRestoreFromTrash = onRestoreFromTrash,
                        onNavigate = ::navigate,
                        onScan = { showPicker = true }
                    )
                }
                // ── 沉浸页层(整理 2 / 回收站 3 / 回忆 5):保留原有「从入口卡片放大/缩回」
                //    转场。pager 恒在下方,天然就是旧转场里 ExitTransition.None /
                //    EnterTransition.None 的「当前页原样垫底」语义;沉浸页总是从当前 Tab 打开,
                //    返回时露出的就是它,无需额外同步。照片预览 shared element、回收站下滑
                //    返回都在各页面内部作用域,不受 pager 影响。
                AnimatedContent(
                    targetState = page.takeIf { it in immersivePages },
                    transitionSpec = {
                        val target = targetState
                        val initial = initialState
                        if (target != null) {
                            // 进入沉浸页:Tab 页原样垫底,沉浸页从入口卡片放大盖上。
                            val ct = (scaleIn(
                                initialScale = 0.55f,
                                transformOrigin = pageTransformOrigin(target),
                                animationSpec = tween(320, easing = FastOutSlowInEasing)
                            ) + fadeIn(tween(260)))
                                .togetherWith(ExitTransition.None)
                            ct.targetContentZIndex = 1f
                            ct
                        } else {
                            // 返回 Tab:目标 Tab 页已完全可见,沉浸页缩回入口卡片。
                            val origin = initial?.let { pageTransformOrigin(it) }
                                ?: TransformOrigin(0.5f, 0.5f)
                            val ct = EnterTransition.None.togetherWith(
                                scaleOut(
                                    targetScale = 0.55f,
                                    transformOrigin = origin,
                                    animationSpec = tween(300)
                                ) + fadeOut(tween(240))
                            )
                            ct.targetContentZIndex = 0f
                            ct
                        }
                    },
                    label = "immersivePage"
                ) { immersivePage ->
                    if (immersivePage != null) {
                        PageContent(
                            page = immersivePage,
                            state = state,
                            viewModel = viewModel,
                            settingsScroll = settingsScroll,
                            savedSettingsScroll = savedSettingsScroll,
                            trashItems = trashItems,
                            onAction = onAction,
                            onCommitDeletes = onCommitDeletes,
                            onRestoreFromTrash = onRestoreFromTrash,
                            onNavigate = ::navigate,
                            onScan = { showPicker = true }
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        AlbumsPicker(viewModel, state.settings, onClose = { showPicker = false })
    }
}

/** One app page. */
@Composable
private fun PageContent(
    page: Int,
    state: AppUiState,
    viewModel: PhotoViewModel,
    settingsScroll: ScrollState,
    savedSettingsScroll: Int,
    trashItems: List<PhotoEntity>,
    onAction: (Long, PhotoState, Int, Long) -> Boolean,
    onCommitDeletes: () -> Unit,
    onRestoreFromTrash: (List<Long>) -> Unit,
    onNavigate: (Int) -> Unit,
    onScan: () -> Unit,
) {
    when (page) {
        0 -> Home(state, onStart = {
            // A session already in progress must stay untouched: reload() nulls it first and
            // rebuilds asynchronously, which makes the 今日任务 card flicker (加载中 / 暂无图片 /
            // 总数量) while the Review page zooms in. Only rebuild when there is nothing to
            // continue; an in-progress session goes straight to the Review page as-is.
            // 每次点整理都触发一次与系统相册的对账:无会话时 restoreSession 立即出会话 +
            // reconcileQuietly 后台补跑;续用中的会话只走 reconcileQuietly,不打断队列。
            val inProgress = state.session != null && state.remaining > 0
            if (!inProgress) viewModel.startSession() else viewModel.reconcileQuietly()
            onNavigate(2)
        }, onScan = onScan, onOpenMemory = { onNavigate(5) })
        1 -> Settings(state.settings, viewModel, scrollState = settingsScroll, savedScroll = savedSettingsScroll, openTrash = { onNavigate(3) })
        3 -> RecycleBin(trashItems, viewModel, onRestore = onRestoreFromTrash, onBack = { onNavigate(1) })
        4 -> {
            // 历史整理:按周显示和切换,选中历史日期即查看它所在的一周。
            val historyWeek by viewModel.historyWeek.collectAsStateWithLifecycle()
            val historyWeekStats by viewModel.historyWeekStats.collectAsStateWithLifecycle()
            StatsScreen(
                state,
                historyWeek = historyWeek,
                historyWeekStats = historyWeekStats,
                onSelectHistoryWeek = viewModel::selectHistoryWeek,
                earliestMonth = viewModel::earliestHistoryMonth,
                monthDayCounts = viewModel::monthDayCounts,
            )
        }
        5 -> MemoryViewer(state.stats.memory, onBack = { onNavigate(0) })
        else -> {
            val session by viewModel.sessionFlow.collectAsStateWithLifecycle(initialValue = viewModel.sessionFlow.value)
            Review(session, onAction, onUndo = viewModel::undo, onDone = { onCommitDeletes() }, onBack = { onNavigate(0) })
        }
    }
}

@Composable private fun AlbumsPicker(viewModel: PhotoViewModel, settings: com.einsli.photoroulette.data.AppSettings, onClose: () -> Unit) {
    var albums by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf(settings.includedAlbums.toSet()) }
    LaunchedEffect(Unit) { albums = viewModel.availableAlbums() }
    AlertDialog(onDismissRequest = onClose, title = { Text("选择要扫描的相册") }, text = {
        if (albums.isEmpty()) Text("未发现相册") else {
            androidx.compose.foundation.lazy.LazyColumn { items(albums) { a ->
                val checked = selected.contains(a)
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { c -> selected = if (c) selected + a else selected - a })
                    Spacer(Modifier.width(8.dp))
                    Text(a)
                }
            } }
        }
    }, confirmButton = {
        TextButton(onClick = {
            // Save the selection and rescan — updates the photo total only, keeps records.
            viewModel.updateAlbums(selected.toList())
            onClose()
        }) { Text("确定") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

/**
 * If the photo being returned to is outside the grid's current viewport, scroll the grid to it
 * BEFORE the shared-element return transition starts. Two things depend on this ordering:
 * 1. The destination cell must be composed on the first frame of the transition, otherwise the
 *    photo stays full-screen and only snaps into the cell afterwards.
 * 2. The scroll must not happen mid-animation, otherwise the destination moves under the flying
 *    photo and the grid jumps to put the cell at the top.
 *
 * [LazyGridState.requestScrollToItem] is a synchronous position update (no remeasure), so it is
 * safe to call while the grid is not composed behind the preview. The scroll is minimal: a cell
 * above the viewport is revealed at the top edge, a cell below at the bottom edge.
 */
private fun revealGridItemIfOffscreen(state: LazyGridState, index: Int) {
    val visible = state.layoutInfo.visibleItemsInfo
    val first = visible.firstOrNull()?.index
    val last = visible.lastOrNull()?.index
    if (first == null || last == null || index < first || index > last) {
        val scrollOffset = if (first != null && last != null && index > last) {
            val cellHeight = visible.first().size.height
            (state.layoutInfo.viewportSize.height - cellHeight).coerceAtLeast(0)
        } else {
            0
        }
        state.requestScrollToItem(index, scrollOffset)
    }
}

/** Whole-page mirror of the RecycleBin, rendered behind the preview's black scrim and revealed
 *  on swipe-down. The layout mirrors the real page EXACTLY (same paddings / spacings / weights),
 *  so the reveal and the exit crossfade align pixel-for-pixel with the real page; the grid uses
 *  the same cell thumbnails and the caller keeps its scroll in sync. Not interactive. */
@Composable
private fun TrashPageBackdrop(
    items: List<PhotoEntity>,
    selected: Set<Long>,
    state: LazyGridState,
    thumbSize: CoilSize,
) {
    val allIds = items.map { it.mediaId }.toSet()
    val statusBarTop = rememberStatusBarTop()
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = statusBarTop)
            .navigationBarsPadding()
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("回收站", style = MaterialTheme.typography.headlineMedium)
            // 镜像页同步的选中数量提示（不可交互，仅保持与真实页面像素一致）
            if (selected.isNotEmpty()) {
                Text(
                    "已选 ${selected.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = {}) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) { Text(if (selected.size != allIds.size) "全选" else "取消全选") }
            // 与真实页面一致:未选中时禁用(镜像页不可交互,但状态显示必须同步)。
            Button(enabled = selected.isNotEmpty(), onClick = {}) { Text("移出回收站") }
            Button(enabled = selected.isNotEmpty(), onClick = {}) { Text("批量删除") }
        }
        Spacer(Modifier.height(4.dp))
        Text("长按选中，点击圆圈多选，点击照片预览", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false,
        ) {
            itemsIndexed(
                items,
                key = { _, photo -> photo.mediaId },
                contentType = { _, photo -> if (photo.mimeType.startsWith("video/")) "video" else "image" },
            ) { _, photo ->
                val checked = selected.contains(photo.mediaId)
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    VideoAwareImage(photo, Modifier.fillMaxSize(), thumbSize = thumbSize)
                    VideoBadge(photo, Modifier.fillMaxSize(), centerSize = 26.dp, textSize = 9)
                    // 镜像页的右下角选中圆圈（静态、不可交互——只在预览下拉时露出）；
                    // 与宫格一致，仅在有选中照片时显示。
                    if (selected.isNotEmpty()) {
                        Box(Modifier.align(Alignment.BottomEnd)) {
                            TrashSelectionBadge(checked)
                        }
                    }
                }
            }
        }
    }
}

/** 回收站宫格右下角的选中标识：未选中=空心圆圈（深色细描边 + 白色圆环，任何照片上都看得清），
 *  已选中=实心圆 + 对勾。选中状态只体现在这个圆圈上，照片本身保持原色。 */
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
                        .background(MaterialTheme.colorScheme.primary)
                        .border(1.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                } else {
                    Modifier
                        .size(22.dp)
                        .border(1.dp, Color.Black.copy(alpha = 0.3f), CircleShape)
                        .padding(1.5.dp)
                        .border(1.5.dp, Color.White, CircleShape)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Default.Check,
                contentDescription = "已选中",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Whole-page mirror of the MemoryViewer (回忆时光机), same role as [TrashPageBackdrop]. */
@Composable
private fun MemoryPageBackdrop(
    memory: MemoryInfo?,
    photos: List<PhotoEntity>,
    state: LazyGridState,
    thumbSize: CoilSize,
    dc: DesignColors,
) {
    val statusBarTop = rememberStatusBarTop()
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = statusBarTop)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}) { Icon(Icons.Default.Close, "返回") }
            Column(Modifier.weight(1f)) {
                Text("回忆时光机", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = dc.ink)
                if (memory != null) {
                    Text("${memory.yearsAgo}年前的今天 · ${memory.dateText} · ${memory.count} 张照片", fontSize = 12.sp, color = dc.slate)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            userScrollEnabled = false,
        ) {
            itemsIndexed(photos) { _, photo ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(dc.white)
                ) {
                    VideoAwareImage(photo, Modifier.fillMaxSize(), thumbSize = thumbSize)
                    VideoBadge(photo, Modifier.fillMaxSize(), centerSize = 26.dp, textSize = 9)
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
// ── 宫格缩略图预载（回收站 / 回忆时光机共用）───────────────────────────────────────
// 设计：只预载「当前 viewport 附近」的固定窗口，绝不因快速滚动把沿途照片灌进队列。
// 用户从第 0 张甩到第 5000 张：fling 期间 isScrollInProgress 恒为 true，经过的
// 1..4999 不产生任何预载；fling 停稳（settle）并经过一小段防抖后，才围绕停稳位置
// （如可见 5000..5020）铺 4970..5050 这一圈。滚动条快速拖拽的中间位置同样被防抖吞掉。
// 可见区域 + 上方少量 + 下方少量 = 窗口；窗口大小固定，不随滚动历史增长（无 frontier）。
private const val GRID_PRELOAD_MARGIN_ITEMS = 30
private const val GRID_PRELOAD_SETTLE_DEBOUNCE_MS = 150L

/**
 * 以 viewport 为中心的固定窗口预载。请求与格子完全同参（data+size+视频帧），直接填
 * 格子要读的那条内存缓存；窗口随停稳位置移动，落在窗口外、尚未解码完的请求立即取消，
 * 因此任意时刻队列里的预载工作量都被限制在一个窗口内，而不是随滚动历史累积。
 * 可见格子自身仍由组合期的 cell 请求负责（滑出即取消），这里的窗口只负责「附近的余量」。
 */
@Composable
private fun GridWindowedThumbnailPreload(gridState: LazyGridState, photos: List<PhotoEntity>, size: CoilSize) {
    val context = LocalContext.current
    val loader = remember(context) { context.imageLoader }
    LaunchedEffect(gridState, photos, size) {
        // index → 该预载请求的 Disposable：在队/解码中即存在，完成或取消后移出。
        val tracked = HashMap<Int, Disposable>()
        fun enqueue(index: Int) {
            if (index in tracked) return
            val photo = photos.getOrNull(index) ?: return
            tracked[index] = loader.enqueue(
                ImageRequest.Builder(context)
                    .data(photo.uri)
                    .size(size)
                    .apply { if (photo.mimeType.startsWith("video/")) videoFrameMillis(1000) }
                    .build()
            )
        }
        fun preloadAroundViewport() {
            val info = gridState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = (info.visibleItemsInfo.lastOrNull()?.index ?: first).coerceAtLeast(first)
            val from = (first - GRID_PRELOAD_MARGIN_ITEMS).coerceAtLeast(0)
            val to = (last + GRID_PRELOAD_MARGIN_ITEMS).coerceAtMost(photos.lastIndex)
            if (from > to) return
            for (i in first..last) enqueue(i)      // 停稳的可见区最先入队
            for (i in (last + 1)..to) enqueue(i)   // 下方余量（继续下滑的方向）
            for (i in from until first) enqueue(i) // 上方余量
            // 窗口外仍在排队/解码中的请求已不需要：取消；已完成的结果留在内存缓存不重解码。
            val keep = from..to
            tracked.entries.removeAll { (index, d) ->
                if (index in keep) false else { if (!d.job.isCompleted) d.dispose(); true }
            }
        }
        try {
            // 进页时 isScrollInProgress 初始为 false，同样走到这里 → 首屏窗口在进入后
            // ~150ms 铺开。滚动一恢复，collectLatest 会取消未触发的 delay，重新等停稳。
            snapshotFlow { gridState.isScrollInProgress }
                .distinctUntilChanged()
                .collectLatest { scrolling ->
                    if (!scrolling) {
                        delay(GRID_PRELOAD_SETTLE_DEBOUNCE_MS)
                        preloadAroundViewport()
                    }
                }
        } finally {
            // 离开页面或列表变化重建时，未完成的预载一并取消，不背着旧窗口跑完。
            tracked.values.forEach { if (!it.job.isCompleted) it.dispose() }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable private fun RecycleBin(items: List<PhotoEntity>, viewModel: com.einsli.photoroulette.PhotoViewModel, onRestore: (List<Long>) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // 根节点必须铺不透明 pageBg:Tab 层(pager)常驻垫底,透明根会透出下面的设置页。
    val dc = designColors()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var previewIndex by remember { mutableIntStateOf(-1) }
    val previewOpen = previewIndex in items.indices
    // Page-level back returns to Settings. While the preview is open, SharedPhotoPreview's own
    // BackHandler (composed later) wins and closes the preview first.
    BackHandler(onBack = onBack)
    val gridState = rememberLazyGridState()
    // Scroll state for the preview's whole-page backdrop mirror (TrashPageBackdrop), seeded
    // with the real grid's scroll when a preview opens and scrolled in sync when it closes.
    val backdropState = rememberLazyGridState()
    // The photo being closed: only ITS grid cell renders the full-screen Fit copy on re-entry
    // (the flight target); the other cells fade in Crop thumbnails (avoids a first-frame stall).
    var closedMediaId by remember { mutableLongStateOf(-1L) }
    // Cell-sized decode target for grid thumbnails: 3 columns, so ~screenWidth/3 px. Fixing the
    // request size keeps every cell's memory-cache entry identical and small, so fast scrolling
    // re-shows already-loaded photos instantly instead of re-decoding.
    val gridCellPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp.dp.toPx() / 3f).roundToInt()
    }
    // 缩略图解码尺寸 = 显示像素的 70%（400→280px）：解码快 ~2 倍、单张内存省一半，
    // 配合扩容后的内存缓存（见 PhotoRouletteApp），窗口内载过的缩略图滑回来直接命中。
    val thumbPx = remember(gridCellPx) { (gridCellPx * 0.7f).roundToInt() }
    val gridThumbSize = remember(thumbPx) { CoilSize(thumbPx, thumbPx) }
    // One grid row = cell + vertical spacing (8dp); approximates the scroll offset from the
    // first visible item's index, used by the spring pull's limit detection.
    val localDensity = LocalDensity.current
    val gridRowPx = remember(gridCellPx) { gridCellPx + with(localDensity) { 8.dp.toPx() }.roundToInt() }
    // 视口居中的固定窗口预载：只在滚动稳定停止后铺「可见区 ± 30 张」，快速甩动与滚动条
    // 拖拽经过的中间位置完全不进队列（详见 [GridWindowedThumbnailPreload]）。
    GridWindowedThumbnailPreload(gridState, items, gridThumbSize)
    PhotoSharedTransitionLayout {
        Box(Modifier.fillMaxSize().background(dc.pageBg)) {
            AnimatedContent(
                targetState = if (previewOpen) previewIndex else null,
                transitionSpec = {
                    fadeIn(tween(PhotoTransitionMillis)) togetherWith fadeOut(tween(PhotoTransitionMillis))
                },
                label = "trashPreview",
            ) { target ->
                if (target == null) {
                    // ── Grid branch ──
                    val radius = photoBranchRadius(gridCornerRadius = 8.dp, gridSide = true)
                    val statusBarTop = rememberStatusBarTop()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = statusBarTop)
                            .navigationBarsPadding()
                            .padding(12.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("回收站", style = MaterialTheme.typography.headlineMedium)
                            // 选中数量提示：显示在标题与返回按钮之间，无选中时不占位
                            if (selected.isNotEmpty()) {
                                Text(
                                    "已选 ${selected.size}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onBack) { Text("返回") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val allIds = items.map { it.mediaId }.toSet()
                            Button(onClick = { selected = if (selected.size != allIds.size) allIds else emptySet() }) { Text(if (selected.size != allIds.size) "全选" else "取消全选") }
                            Button(enabled = selected.isNotEmpty(), onClick = {
                                val ids = selected.toList()
                                selected = emptySet()
                                onRestore(ids)
                            }) { Text("移出回收站") }
                            Button(enabled = selected.isNotEmpty(), onClick = {
                                val ids = selected.toList()
                                selected = emptySet()
                                scope.launch { viewModel.deleteFromTrash(ids) }
                            }) { Text("批量删除") }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("长按选中，点击圆圈多选，点击照片预览", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        // Only the grid area is elastic: the header and buttons stay fixed, so the
                        // pull (drag past the edge or the fling-limit spring) moves just the photos.
                        // clipToBounds keeps the sliding grid from covering the header above it
                        // (the pull is a translation, so without clipping it overlaps upward).
                        SpringPullBox(
                            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
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
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("回收站为空") }
                            } else {
                                Box(Modifier.fillMaxSize()) {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(3),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                                .clip(RoundedCornerShape(8.dp))
                                                .pointerInput(photo.mediaId) {
                                                    detectTapGestures(
                                                        onTap = {
                                                            backdropState.requestScrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                                            previewIndex = index
                                                        },
                                                        onLongPress = { selected = if (liveChecked) selected - photo.mediaId else selected + photo.mediaId }
                                                    )
                                                }
                                        ) {
                                            SharedGridImage(photo, radius, this@AnimatedContent, Modifier.fillMaxSize(), gridSize = gridThumbSize, fitOnEnter = photo.mediaId == closedMediaId)
                                            VideoBadge(photo, Modifier.fillMaxSize(), centerSize = 26.dp, textSize = 9)
                                            // 右下角选中圆圈：平时不显示；只要选中了任意一张，
                                            // 所有照片都显示圆圈（选中的实心、未选的空心），点圆圈
                                            // 切换选中（不触发预览），照片本身不变色。
                                            if (selected.isNotEmpty()) {
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
                                TrashScrollbar(gridState, items.size, Modifier.align(Alignment.CenterEnd))
                                }
                            }
                        }
                    }
                } else {
                    // ── Preview branch ──
                    SharedPhotoPreview(
                        photos = items,
                        initialIndex = target,
                        animatedRadius = photoBranchRadius(gridCornerRadius = 8.dp, gridSide = false),
                        animatedVisibilityScope = this@AnimatedContent,
                        swipeDownToClose = true,
                        sourceThumbSize = gridThumbSize,
                        fullScreenPhotoArea = true,
                        tapToToggleChrome = true,
                        doubleTapToZoom = true,
                        onClose = { current, viaSwipeDown ->
                            scope.launch {
                                // 下滑划走式关闭:照片已滑出屏幕,宫格原位淡入 —— 没有 shared-element
                                // 回位,closedMediaId(仅回位时 cell 需要的 Fit 拷贝)和滚动同步都不需要。
                                // 侧滑/系统返回仍走回位路径:先滚动让目标 cell 可见并合成,再开始转场。
                                if (!viaSwipeDown) {
                                    closedMediaId = current.mediaId
                                    val idx = items.indexOfFirst { it.mediaId == current.mediaId }
                                    if (idx >= 0) {
                                        revealGridItemIfOffscreen(gridState, idx)
                                        revealGridItemIfOffscreen(backdropState, idx)
                                    }
                                }
                                previewIndex = -1
                            }
                        },
                        revealContent = {
                            TrashPageBackdrop(
                                items = items,
                                selected = selected,
                                state = backdropState,
                                thumbSize = gridThumbSize,
                            )
                        },
                        bottomControls = { current ->
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = {
                                    previewIndex = -1
                                    onRestore(listOf(current.mediaId))
                                }, Modifier.weight(1f)) { Text("移出回收站") }
                                Button(onClick = {
                                    previewIndex = -1
                                    scope.launch { viewModel.deleteFromTrash(listOf(current.mediaId)) }
                                }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("永久删除") }
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 回收站宫格右侧的快速滚动条：细轨道 + 可拖拽圆头拇指，拖动直接跳转到对应位置。 */
@Composable
private fun TrashScrollbar(gridState: LazyGridState, totalItems: Int, modifier: Modifier = Modifier) {
    if (totalItems < 24) return
    val dc = designColors()
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableIntStateOf(0) }
    // 每帧读取滚动位置只会重组这个小工具，宫格本身不受影响。
    val info = gridState.layoutInfo
    val total = info.totalItemsCount.coerceAtLeast(1)
    val visible = info.visibleItemsInfo
    val first = visible.firstOrNull()?.index ?: 0
    val span = ((visible.lastOrNull()?.index ?: first) - first + 1).coerceAtLeast(1)
    val denom = (total - span).coerceAtLeast(1)
    val frac = (first.toFloat() / denom).coerceIn(0f, 1f)
    val density = LocalDensity.current
    val thumbHeightPx = maxOf(with(density) { 48.dp.toPx() }, trackHeightPx * (span.toFloat() / total))
    val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val thumbHeightDp = with(density) { thumbHeightPx.toDp() }
    fun scrollToFraction(f: Float) {
        val target = (f.coerceIn(0f, 1f) * denom).roundToInt().coerceIn(0, total - 1)
        scope.launch { gridState.scrollToItem(target) }
    }
    Box(
        modifier
            .width(26.dp)
            .fillMaxHeight()
            .onSizeChanged { trackHeightPx = it.height }
            .pointerInput(total) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        scrollToFraction(it.y / trackHeightPx.coerceAtLeast(1))
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        scrollToFraction(change.position.y / trackHeightPx.coerceAtLeast(1))
                    },
                )
            },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(4.dp)
                .fillMaxHeight(0.92f)
                .background(dc.track.copy(alpha = 0.5f), CircleShape)
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (frac * travelPx).roundToInt()) }
                .width(5.dp)
                .height(thumbHeightDp)
                .background(if (dragging) dc.accent else dc.accent.copy(alpha = 0.55f), CircleShape)
        )
    }
}

@Composable private fun Review(session: ReviewSession?, onAction: (Long, PhotoState, Int, Long) -> Boolean, onUndo: () -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    // 离开整理页前先把状态栏恢复（chrome 隐藏时状态栏也藏了；若等到分支销毁才恢复，
    // 主页首帧布局用的是"状态栏隐藏"的 insets，状态栏弹回时整页跳位闪一下）。
    val reviewActivity = LocalContext.current as? android.app.Activity
    fun leaveReview() {
        reviewActivity?.window?.insetsController?.show(android.view.WindowInsets.Type.statusBars())
        onBack()
    }
    // System back (including the edge-swipe gesture) returns to the home screen.
    BackHandler(onBack = ::leaveReview)
    // Light zoom-in when a NEW session arrives while already on this page (处理删除并继续整理).
    val animScale = remember { Animatable(1f) }
    var seenSessionId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(session?.sessionId) {
        val id = session?.sessionId ?: return@LaunchedEffect
        if (seenSessionId != null && seenSessionId != id) {
            animScale.snapTo(0.9f)
            animScale.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        }
        seenSessionId = id
    }
    val dc = designColors()
    val s = session
    // 全屏效果（与回收站/回忆时光机预览一致）：单击照片隐藏/显示标题和按钮，双击缩放。
    // 不再有单独的"放大查看"预览——整理页本身就是全屏预览。
    var chromeHidden by remember { mutableStateOf(false) }
    val chromeProgress by animateFloatAsState(
        targetValue = if (chromeHidden) 1f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "reviewChrome",
    )
    val chromeExitPx = with(LocalDensity.current) { 140.dp.toPx() }
    // 视频控制条悬浮在底部按钮行(~72dp)上方。
    val density = LocalDensity.current
    val videoBarBottomInset: Dp = with(density) {
        (WindowInsets.navigationBars.getBottom(density) + 84.dp.toPx()).toDp()
    }
    // 状态栏高度在进入页面时固定捕获：状态栏隐藏时标题/时间戳不会跳位。
    val statusBarTop = rememberStatusBarTop()
    // 系统状态栏随标题/按钮一起隐藏/显示（本次完成时恢复）。
    SyncStatusBarWithChrome(chromeHidden && s?.current != null)
    Box(
        Modifier
            .fillMaxSize()
            .background(dc.pageBg)
            .graphicsLayer {
                scaleX = animScale.value
                scaleY = animScale.value
            }
    ) {
        when {
            // No loading spinner: render nothing until the session is published.
            s == null -> Box(Modifier.fillMaxSize())
            s.current == null -> Column(
                Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("本次完成！", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = dc.ink)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDone, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = dc.accent, contentColor = Color.White)) { Text("处理删除并继续整理") }
            }
            else -> SwipePhoto(
                session = s,
                onAction = onAction,
                onUndo = onUndo,
                onTap = { chromeHidden = !chromeHidden },
                videoBarBottomInset = videoBarBottomInset,
                chromeProgress = chromeProgress,
                chromeExitPx = chromeExitPx,
                statusBarTop = statusBarTop,
            )
        }
        // ── 浮动标题（只有正在看图时显示；单击照片可隐藏）──
        if (s?.current != null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = statusBarTop)
                    .navigationBarsPadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .graphicsLayer { translationY = -chromeProgress * chromeExitPx },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::leaveReview) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
                    Text("本次整理", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                    Text("剩余 ${s.remaining} / ${s.queue.size}", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun formatTaken(taken: Long): String =
    if (taken <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(taken))

@Composable private fun SwipePhoto(session: ReviewSession, onAction: (Long, PhotoState, Int, Long) -> Boolean, onUndo: () -> Unit, onTap: () -> Unit, videoBarBottomInset: Dp, chromeProgress: Float, chromeExitPx: Float, statusBarTop: Dp) {
    val dc = designColors()
    val photo = session.current!!
    val next = session.queue.getOrNull(session.position + 1)
    val swipeThreshold = with(LocalDensity.current) { 120.dp.toPx() }
    val flyOutDistance = with(LocalDensity.current) { 1600.dp.toPx() }
    // Timestamp of the last real pointer event (drag or tap) in this screen. The MIUI
    // handwriting/accessibility service injects clicks with NO pointer events, so the
    // ViewModel can tell real swipes from injected ones by comparing this with cardShownAt.
    var userTouchedAt by remember { mutableStateOf(0L) }
    // Drag offset of the current (top) photo.
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    // The photo currently flying out after an accepted swipe. Kept separate from the current
    // photo so it keeps animating off-screen after the session advances to the next one.
    var flyingPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var flyingX by remember { mutableFloatStateOf(0f) }
    var flyingY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // Undo slide-in: when returning to the previous photo it slides back in from the side it was
    // swiped away to (kept → from the right, deleted → from the left), covering the photo that
    // was shown after it — that photo stays visible underneath the whole time. The Animatable is
    // re-created per photo and starts at the off-screen position directly, so the returning photo
    // never renders centered and then jumps off-screen (that snap looked like a flash / "two
    // photos").
    var prevPosition by remember { mutableIntStateOf(session.position) }
    val undoFrom = remember(photo.mediaId) {
        // lastActionDir is NEGATED by the undo (the ViewModel mirrors the direction), so a keep
        // (original dir=1, swiped right) arrives as -1 here → return from the right.
        if (session.position < prevPosition) {
            when (session.lastActionDir) {
                1 -> -flyOutDistance
                -1 -> flyOutDistance
                else -> 0f
            }
        } else 0f
    }
    val slideInX = remember(photo.mediaId) { Animatable(undoFrom) }
    LaunchedEffect(photo.mediaId) {
        prevPosition = session.position
        if (slideInX.value != 0f) slideInX.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
    }
    // 全屏占位小图（ZoomablePhoto 的大图解码完成前先显示它，避免空白）。
    val context = LocalContext.current
    val placeholderSize = remember { CoilSize(480, 480) }
    val placeholder = remember(photo.uri, placeholderSize) { photoThumbRequest(context, photo, placeholderSize) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        userTouchedAt = SystemClock.elapsedRealtime()
                    }
                }
            }
    ) {
        // Next photo behind — full-screen, revealed as the current photo is dragged away.
        if (next != null) {
            VideoAwareImage(
                next,
                Modifier.fillMaxSize().background(Color.Black),
                contentScale = ContentScale.Fit,
            )
        }
        // Current photo on top, draggable (swipe to keep/delete), full-screen. Photos support
        // pinch-zoom/pan + double-tap 1x↔3x; videos play inline. Single tap toggles the chrome
        // (title + buttons) via [onTap].
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    translationX = dragX + slideInX.value; translationY = dragY
                    rotationZ = (dragX / 35f).coerceIn(-25f, 25f)
                }
                .background(Color.Black)
                .pointerInput(photo.mediaId) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            dragX += amount.x; dragY += amount.y
                        },
                        onDragEnd = {
                            val dir: Int
                            val state: PhotoState
                            when {
                                dragX < -swipeThreshold -> { dir = -1; state = PhotoState.DELETE_PENDING }
                                dragX > swipeThreshold -> { dir = 1; state = PhotoState.KEEP }
                                else -> { dir = 0; state = PhotoState.KEEP }
                            }
                            val startX = dragX
                            val startY = dragY
                            if (dir != 0) {
                                // Reset the top photo's offset synchronously so the next photo starts
                                // centered once the session advances; the swiped photo keeps flying
                                // out via [flyingPhoto]/[flyingX]/[flyingY].
                                dragX = 0f
                                dragY = 0f
                                // The photo asks the ViewModel to advance ITSELF by its own mediaId
                                // plus the swipe direction. The ViewModel only advances if this photo
                                // is still current, so a ghost drag can never advance the next photo.
                                if (onAction(photo.mediaId, state, dir, userTouchedAt)) {
                                    val dirX = if (startX < 0f) -1f else 1f
                                    val endX = dirX * flyOutDistance
                                    flyingPhoto = photo
                                    flyingX = startX
                                    flyingY = startY
                                    scope.launch {
                                        val start = System.currentTimeMillis()
                                        while (true) {
                                            val t = ((System.currentTimeMillis() - start).toFloat() / 180f).coerceIn(0f, 1f)
                                            val eased = 1f - (1f - t) * (1f - t)
                                            flyingX = startX + (endX - startX) * eased
                                            flyingY = startY * (1f - eased)
                                            if (t >= 1f) break
                                            withFrameMillis { }
                                        }
                                        flyingPhoto = null
                                    }
                                } else {
                                    // ViewModel refused: restore the offset and ease back to center.
                                    dragX = startX
                                    dragY = startY
                                    scope.launch {
                                        val sx = startX; val sy = startY
                                        val start = System.currentTimeMillis()
                                        while (true) {
                                            val t = ((System.currentTimeMillis() - start).toFloat() / 150f).coerceIn(0f, 1f)
                                            val eased = 1f - (1f - t) * (1f - t)
                                            dragX = sx * (1f - eased); dragY = sy * (1f - eased)
                                            if (t >= 1f) break
                                            withFrameMillis { }
                                        }
                                    }
                                }
                            } else {
                                // Below threshold: ease back to center.
                                scope.launch {
                                    val sx = dragX; val sy = dragY
                                    val start = System.currentTimeMillis()
                                    while (true) {
                                        val t = ((System.currentTimeMillis() - start).toFloat() / 150f).coerceIn(0f, 1f)
                                        val eased = 1f - (1f - t) * (1f - t)
                                        dragX = sx * (1f - eased); dragY = sy * (1f - eased)
                                        if (t >= 1f) break
                                        withFrameMillis { }
                                    }
                                }
                            }
                        }
                    )
                }
        ) {
            if (photo.mimeType.startsWith("video/")) {
                VideoPhoto(
                    photo = photo,
                    active = true,
                    resetTick = 0,
                    onResetDone = {},
                    placeholderRequest = null,
                    bottomInset = videoBarBottomInset,
                    chromeProgress = chromeProgress,
                    chromeExitPx = chromeExitPx,
                    onTap = onTap,
                )
            } else {
                ZoomablePhoto(
                    photo = photo,
                    enabled = true,
                    resetTick = 0,
                    onResetDone = {},
                    placeholderRequest = placeholder,
                    onTap = onTap,
                    doubleTapZoom = true,
                )
            }
            Text(
                formatTaken(photo.dateTaken),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // 固定在状态栏 + 标题栏（返回按钮 ~54dp）下方；用进入页面时捕获的状态栏
                    // 高度，状态栏隐藏时时间戳不会跟着跳到屏幕顶端。
                    .padding(start = 12.dp, top = statusBarTop + 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
        }
        // Flying-out photo on top (rendered last = topmost), so it visibly slides off over the
        // already-revealed next photo.
        val flying = flyingPhoto
        if (flying != null) {
            VideoAwareImage(
                flying,
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = flyingX
                    translationY = flyingY
                    rotationZ = (flyingX / 35f).coerceIn(-25f, 25f)
                }.background(Color.Black),
                contentScale = ContentScale.Fit,
            )
        }
        // ── 浮动底部按钮（单击照片可随标题一起隐藏）──
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .graphicsLayer { translationY = chromeProgress * chromeExitPx },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 上一张: outline / secondary
            OutlinedButton(
                onClick = onUndo,
                enabled = session.position > 0,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.9f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
            ) { Text("上一张") }
            // 删除: tonal / danger — low-saturation red container, soft red content
            FilledTonalButton(
                onClick = { onAction(photo.mediaId, PhotoState.DELETE_PENDING, -1, userTouchedAt) },
                Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = dc.dangerContainer,
                    contentColor = dc.onDangerContainer
                )
            ) { Text("删除", fontWeight = FontWeight.SemiBold) }
            // 保留: filled / primary — app lavender primary
            Button(
                onClick = { onAction(photo.mediaId, PhotoState.KEEP, 1, userTouchedAt) },
                Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = dc.accent, contentColor = Color.White)
            ) { Text("保留", fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Settings(settings: AppSettings, vm: PhotoViewModel, scrollState: ScrollState, savedScroll: Int, openTrash: () -> Unit) {
    val dc = designColors()
    var showDatePicker by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showAlbumPicker by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    // Restore the exact scroll position saved when leaving this page. The ScrollState is
    // re-created via its Saver, but it can be clamped before the content is laid out, so we
    // wait for the layout (maxValue is valid) and then re-apply the snapshot.
    LaunchedEffect(Unit) {
        if (savedScroll > 0) {
            withFrameNanos { }
            if (scrollState.maxValue > 0) {
                scrollState.scrollTo(savedScroll.coerceAtMost(scrollState.maxValue))
            }
        }
    }

    // Spring pull in both directions; engages only at the scroll limits (nested scroll also
    // swallows the platform stretch overscroll).
    SpringPullBox(
        modifier = Modifier.fillMaxSize(),
        pullAtTop = { scrollState.value.toFloat() },
        pullAtBottom = { (scrollState.maxValue - scrollState.value).coerceAtLeast(0).toFloat() },
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .background(dc.pageBg)
            .verticalScroll(scrollState, flingBehavior = rememberGentleFlingBehavior())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = dc.ink)
        Spacer(Modifier.height(14.dp))

        // ── 外观 ──
        SettingCard {
            SettingValueRow(label = "外观") {
                SettingOptionPicker(
                    options = listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2),
                    selectedValue = settings.darkMode,
                    onSelect = vm::setDarkMode
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 每次整理数量: 药丸触发悬浮滚轮(5–100, 步进1)——浮层不撑高卡片 ──
        SettingCard {
            SettingValueRow(label = "每次整理数量") {
                SettingValueWheel(
                    title = "每次整理数量",
                    label = "${settings.dailyCount} 张",
                    values = (5..100).toList(),
                    selected = settings.dailyCount,
                    unit = "张",
                    onSelect = vm::setDailyCount,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 每日提醒: 与数量选择同款的悬浮卡片, 双滚轮(时/分) ──
        SettingCard {
        SettingValueRow(label = "每日提醒") {
            SettingTimeWheel(
                label = "${settings.reminderHour.toString().padStart(2, '0')}:${settings.reminderMinute.toString().padStart(2, '0')}",
                title = "每日提醒",
                hour = settings.reminderHour,
                minute = settings.reminderMinute,
                onHour = { vm.setReminderHour(it) },
                onMinute = { vm.setReminderMinute(it) },
            )
        }
            // SCHEDULE_EXACT_ALARM 在 Android 14+ 默认拒绝,没有它提醒可能延迟几分钟。
            // 点这行进系统设置授权,回来(onResume)后会自动改用精确闹钟。
            val ctx = LocalContext.current
            val exactAlarmAvailable = runCatching { ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)
            if (!exactAlarmAvailable) {
                HorizontalDivider(color = dc.track.copy(alpha = 0.6f))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                        }
                    }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("提醒精确到分钟", fontSize = 14.sp, color = dc.ink)
                        Text("未授权时提醒可能延迟,点击去系统设置开启", fontSize = 11.sp, color = dc.labelGray)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = dc.labelGray, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 照片范围 ──
        SettingCard {
            SettingValueRow(label = "照片范围") {
                SettingOptionPicker(
                    options = listOf(
                        "全部照片" to "all",
                        "最近一年" to "lastYear",
                        "一年以前" to "beforeLastYear",
                        "自定义时间" to "custom",
                    ),
                    selectedValue = settings.photoRange,
                    onSelect = vm::setPhotoRange
                )
            }
            if (settings.photoRange == "custom") {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (settings.customRangeStart > 0) "从 ${dateFormatter.format(Date(settings.customRangeStart))} 起" else "尚未选择起始日期",
                        fontSize = 12.sp,
                        color = dc.slate
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showDatePicker = true }) { Text("选择日期", color = dc.accentText, fontSize = 12.sp) }
                }
            }
            HorizontalDivider(color = dc.track.copy(alpha = 0.6f))
            // 选择相册: resean only adds newly-included photos to the pool — it never clears
            // the organizing history (that's what 重置整理记录 does).
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showAlbumPicker = true }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("选择相册", fontSize = 14.sp, color = dc.ink, modifier = Modifier.weight(1f))
                Text(
                    if (settings.includedAlbums.isEmpty()) "全部相册" else "已选 ${settings.includedAlbums.size} 个相册",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = dc.accentText
                )
                Spacer(Modifier.width(2.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = dc.labelGray, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 整理策略 ──
        SettingCard {
            SettingValueRow(label = "整理策略") {
                SettingOptionPicker(
                    options = listOf(
                        "随机" to "random",
                        "优先旧照片" to "oldest",
                        "优先大照片" to "largest",
                    ),
                    selectedValue = settings.strategy,
                    onSelect = vm::setStrategy
                )
            }
            Spacer(Modifier.height(2.dp))
            Text("策略将在下一次「开始整理」时生效", fontSize = 11.sp, color = dc.labelGray)
        }
        Spacer(Modifier.height(12.dp))

        // ── 内容 ──
        SettingCard {
            Text("内容", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.slate)
            Spacer(Modifier.height(2.dp))
            var videos by remember(settings) { mutableStateOf(settings.includeVideos) }
            SettingSwitchRow("包含视频", videos, { videos = it; vm.setIncludeVideos(it) }, dc)
            HorizontalDivider(color = dc.track.copy(alpha = 0.6f))
            var screenshots by remember(settings) { mutableStateOf(settings.includeScreenshots) }
            SettingSwitchRow("包含截图", screenshots, { screenshots = it; vm.setIncludeScreenshots(it) }, dc)
        }
        Spacer(Modifier.height(12.dp))

        // ── 其他 ──
        SettingCard {
            Text("其他", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.slate)
            Spacer(Modifier.height(2.dp))
            SettingNavRow("回收站", openTrash, dc)
            HorizontalDivider(color = dc.track.copy(alpha = 0.6f))
            SettingNavRow("重置整理记录", { showResetConfirm = true }, dc)
        }
        Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = if (settings.customRangeStart > 0) settings.customRangeStart else null)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { vm.setCustomRangeStart(it) }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) { DatePicker(state = dateState) }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置整理记录？") },
            text = { Text("所有已经处理过的照片将重新进入随机池。\n不会删除照片。") },
            confirmButton = {
                TextButton(
                    onClick = { showResetConfirm = false; vm.reset() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("取消") } }
        )
    }

    if (showAlbumPicker) {
        AlbumsPicker(vm, settings, onClose = { showAlbumPicker = false })
    }
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    val dc = designColors()
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = dc.card),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, dc: DesignColors) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = dc.ink)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = dc.accent,
                checkedTrackColor = dc.accent.copy(alpha = 0.4f),
                uncheckedThumbColor = dc.labelGray,
                uncheckedTrackColor = dc.track
            )
        )
    }
}

@Composable
private fun SettingNavRow(label: String, onClick: () -> Unit, dc: DesignColors) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = dc.ink)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = dc.labelGray, modifier = Modifier.size(20.dp))
    }
}

/** One settings row: label on the left, the value/control on the right. With [onClick] the
 *  whole row is tappable and shows a chevron that rotates when [expanded]. */
@Composable
private fun SettingValueRow(
    label: String,
    onClick: (() -> Unit)? = null,
    expanded: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    val dc = designColors()
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f, tween(200), label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = dc.ink, modifier = Modifier.weight(1f))
        trailing()
        if (onClick != null) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = if (expanded) dc.accent else dc.labelGray,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }
    }
}

/** 悬浮卡片式单选下拉：与滚轮选择器同一套卡片和展开/收回动画，点选项即确认并收回。 */
@Composable
private fun <T> SettingOptionPicker(
    options: List<Pair<String, T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
) {
    val dc = designColors()
    val selectedLabel = options.firstOrNull { it.second == selectedValue }?.first ?: options.first().first
    SettingPopupPicker(label = selectedLabel, title = null, cardWidth = 0.dp, showConfirm = false) { dismiss ->
        Column(Modifier.padding(vertical = 2.dp)) {
            options.forEach { (display, value) ->
                val selected = value == selectedValue
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onSelect(value)
                            dismiss()
                        }
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        display,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) dc.accentText else dc.ink,
                    )
                    Spacer(Modifier.width(10.dp))
                    if (selected) Icon(Icons.Default.Check, null, tint = dc.accent, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

/** 每次整理数量：单滚轮（悬浮卡片内）。 */
@Composable
private fun SettingValueWheel(
    title: String,
    label: String,
    values: List<Int>,
    selected: Int,
    unit: String,
    onSelect: (Int) -> Unit,
) {
    SettingPopupPicker(label = label, title = title, cardWidth = 132.dp) {
        StyledNumberWheel(values = values, selected = selected, unit = unit, onSelected = onSelect)
    }
}

/** 每日提醒：双滚轮（时/分），与数量选择同款悬浮卡片。 */
@Composable
private fun SettingTimeWheel(
    title: String,
    label: String,
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
) {
    val dc = designColors()
    SettingPopupPicker(label = label, title = title) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            StyledNumberWheel(
                values = (0..23).toList(),
                selected = hour,
                unit = "",
                format = { it.toString().padStart(2, '0') },
                onSelected = onHour,
                modifier = Modifier.weight(1f),
            )
            Text(":", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = dc.ink)
            StyledNumberWheel(
                values = (0..59).toList(),
                selected = minute,
                unit = "",
                format = { it.toString().padStart(2, '0') },
                onSelected = onMinute,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Full-screen browse of "N年前的今天" photos, reached via 回忆时光机 → 去看看. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MemoryViewer(memory: MemoryInfo?, onBack: () -> Unit) {
    val dc = designColors()
    val photos = memory?.photos ?: emptyList()
    var previewIndex by remember { mutableIntStateOf(-1) }
    val previewOpen = previewIndex in photos.indices
    // Cell-sized decode target for the 3-column memory grid (same trick as RecycleBin).
    val gridCellPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp.dp.toPx() / 3f).roundToInt()
    }
    val gridThumbSize = remember(gridCellPx) { CoilSize(gridCellPx, gridCellPx) }
    // One grid row = cell + vertical spacing (6dp); approximates the scroll offset from the
    // first visible item's index, used by the spring pull's limit detection.
    val localDensity = LocalDensity.current
    val gridRowPx = remember(gridCellPx) { gridCellPx + with(localDensity) { 6.dp.toPx() }.roundToInt() }
    val scope = rememberCoroutineScope()
    // System back (including the edge-swipe gesture) returns to the home screen. While the
    // preview is open, SharedPhotoPreview's own BackHandler (composed later) closes it first.
    BackHandler(onBack = onBack)
    val gridState = rememberLazyGridState()
    // Scroll state for the preview's whole-page backdrop mirror (MemoryPageBackdrop).
    val backdropState = rememberLazyGridState()
    // The photo being closed: only its cell renders the Fit copy on re-entry (see RecycleBin).
    var closedMediaId by remember { mutableLongStateOf(-1L) }
    // 视口居中的固定窗口预载（同回收站）：甩动/滚动条拖拽经过的中间位置不进队列。
    GridWindowedThumbnailPreload(gridState, photos, gridThumbSize)
    PhotoSharedTransitionLayout {
        Box(Modifier.fillMaxSize().background(dc.pageBg)) {
            AnimatedContent(
                targetState = if (previewOpen) previewIndex else null,
                transitionSpec = {
                    fadeIn(tween(PhotoTransitionMillis)) togetherWith fadeOut(tween(PhotoTransitionMillis))
                },
                label = "memoryPreview",
            ) { target ->
                if (target == null) {
                    // ── Grid branch ──
                    val radius = photoBranchRadius(gridCornerRadius = 12.dp, gridSide = true)
                    val statusBarTop = rememberStatusBarTop()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = statusBarTop)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp)
                    ) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) { Icon(Icons.Default.Close, "返回") }
                            Column(Modifier.weight(1f)) {
                                Text("回忆时光机", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = dc.ink)
                                if (memory != null) {
                                    Text("${memory.yearsAgo}年前的今天 · ${memory.dateText} · ${memory.count} 张照片", fontSize = 12.sp, color = dc.slate)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // Only the grid is elastic: the header stays fixed, so the pull (drag past
                        // the edge or the fling-limit spring) moves just the photos. clipToBounds
                        // keeps the sliding grid from covering the header above it.
                        SpringPullBox(
                            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
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
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text("暂无回忆", color = dc.slate)
                                }
                            } else {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(3),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    flingBehavior = rememberGentleFlingBehavior()
                                ) {
                                    itemsIndexed(photos) { index, photo ->
                                        Box(
                                            Modifier
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(dc.white)
                                                .clickable {
                                                    backdropState.requestScrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                                    previewIndex = index
                                                }
                                        ) {
                                            SharedGridImage(photo, radius, this@AnimatedContent, Modifier.fillMaxSize(), gridSize = gridThumbSize, fitOnEnter = photo.mediaId == closedMediaId)
                                            VideoBadge(photo, Modifier.fillMaxSize(), centerSize = 26.dp, textSize = 9)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ── Preview branch ──
                    SharedPhotoPreview(
                        photos = photos,
                        initialIndex = target,
                        animatedRadius = photoBranchRadius(gridCornerRadius = 12.dp, gridSide = false),
                        animatedVisibilityScope = this@AnimatedContent,
                        swipeDownToClose = true,
                        sourceThumbSize = gridThumbSize,
                        fullScreenPhotoArea = true,
                        tapToToggleChrome = true,
                        doubleTapToZoom = true,
                        onClose = { current, viaSwipeDown ->
                            scope.launch {
                                // 下滑划走式关闭:照片已滑出屏幕,宫格原位淡入,跳过回位相关准备
                                // (同回收站);侧滑/系统返回仍走回位路径,先滚动再转场。
                                if (!viaSwipeDown) {
                                    closedMediaId = current.mediaId
                                    val idx = photos.indexOfFirst { it.mediaId == current.mediaId }
                                    if (idx >= 0) {
                                        revealGridItemIfOffscreen(gridState, idx)
                                        revealGridItemIfOffscreen(backdropState, idx)
                                    }
                                }
                                previewIndex = -1
                            }
                        },
                        revealContent = {
                            MemoryPageBackdrop(
                                memory = memory,
                                photos = photos,
                                state = backdropState,
                                thumbSize = gridThumbSize,
                                dc = dc,
                            )
                        },
                    )
                }
            }
        }
    }
}
