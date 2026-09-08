package com.einsli.photoroulette.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.core.view.WindowCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    //    写入通路,不形成回环、不叠加第二套动画:
    //    1) 滑动:pager.currentPage 越过中线那一帧 → page = 目标 Tab(底部栏选中态随动);
    //    2) 点击 Tab:navigate() 无条件往 tabClicks 投递目标索引,独立长循环用收敛循环
    //       (while currentPage != idx + 打断重试)把 pager 驱动到目标页。
    //    点击动画绝不能放在 LaunchedEffect(page) 里:跨越中间 Tab 的动画途中,通路 1 改写
    //    page 会重启该效果、取消进行中的动画协程 —— pager 冻结在两页之间(首页⇄设置点切换
    //    必卡死在中间,即坑 26)。channel 串行排队:动画中的再点击接续执行,永不半途取消。
    val pagerState = rememberPagerState(initialPage = tabPages.indexOf(page).coerceAtLeast(0)) { tabPages.size }
    val tabClicks = remember { Channel<Int>(Channel.CONFLATED) }
    // 收敛循环:不能「单发一次 animateScrollToPage 完事」。它跑在 MutatePriority.Default,
    // 会被更高优先级的滚动打断,且两种打断都会把 CancellationException 直接抛进本协程——
    // (a) 动画进行中手指按下 pager(UserInput 打断 Default);(b) 手势的惯性/吸附(fling
    // 属同一次 UserInput 滚动)还在跑时点了 Tab,MutatorMutex 发现新调用优先级更低,对调用
    // 方抛异常。没有 catch 时整个消费循环被杀死:之后 navigate 照常写 page(图标切换)、
    // trySend 到 CONFLATED 通道照常成功,但再也没人执行动画 —— 「图标切了、页面不切」且
    // 本次会话内永不自愈(2026-09-06 报障的根因)。catch 里 ensureActive:组合销毁导致的
    // 真取消(本协程 Job 被取消)照常传播,不能吞。
    // 重试之间必须用 withFrameNanos 做真正让出线程的挂起(原因见 catch 内注释)——用
    // snapshotFlow 之类「可能同步恢复」的等待顶替,会和打断者形成 trampoline 饿死互锁。
    // 守卫不读 targetPage:PagerState.scroll 对 programmaticScrollTargetPage 的重置没有
    // try/finally,动画被打断后该值滞留,isScrollInProgress 期间 targetPage 会返回旧目标。
    // 只认 currentPage —— 已停到目标页时 while 不执行,不会叠加第二套动画。
    LaunchedEffect(pagerState) {
        for (idx in tabClicks) {
            if (idx < 0) continue // 沉浸页期间 pager 保持原位:总是从当前 Tab 打开,返回露出的就是它
            while (pagerState.currentPage != idx) {
                try {
                    pagerState.animateScrollToPage(idx)
                } catch (e: CancellationException) {
                    ensureActive()
                    // 重试前必须有「时间上真正流逝」的挂起,绝不能让重试链同步连转。
                    // 真机实测(2026-09-06,100% 复现):拖拽打断点击动画的那一瞬间,打断者
                    // 只完成了 MutatorMutex.tryMutateOrCancel —— 已经占住 currentMutator
                    // (UserInput > Default,所以下一次重试会同步抛 "higher priority"),
                    // 但它的 scroll block 还排在 dispatcher 队列里没开始跑,此刻
                    // isScrollInProgress 仍是 false。AndroidUiDispatcher 的 trampoline 会把
                    // 「同步抛 → catch → 同步返回的等待 → 重试」整条链在一个 dispatch 里无限
                    // 连转(实测 2 秒 30 万拍、主线程 100%、输入事件全部饿死),打断者自己的
                    // 恢复永远排不上队 → 互斥量永久被占 → 页面冻结、永不自愈。
                    // withFrameNanos 走 Choreographer 等下一帧,必然真正让出线程:打断者的
                    // block 得以启动,isScrollInProgress 才会变 true,下面的等待才有意义。
                    withFrameNanos { }
                    // 打断者的滚动(拖拽/惯性吸附)真在跑就礼貌等它结束(帧 paced,不轮询);
                    // 已结束则直接重试 —— 此刻 mutex 已空,重试会成功。
                    if (pagerState.isScrollInProgress) {
                        snapshotFlow { pagerState.isScrollInProgress }.first { !it }
                    }
                }
            }
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
        }
        // 无条件投递(不管 page 是否已等于 newPage):page 可能早就被 snapshotFlow 的
        // currentPage 翻页写成了目标值,而 pager 还在别处/还在路上(手势惯性、被打断的旧
        // 动画)——此时跳过投递,这次点击就只切了图标、pager 永远不会跟过去。消费端对
        // 「已停在目标页」的投递是无操作(while 条件不成立),重复投递无副作用。
        tabClicks.trySend(tabPages.indexOf(newPage))
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
        3 -> RecycleBin(trashItems, state.stats.trashBytes, viewModel, onRestore = onRestoreFromTrash, onBack = { onNavigate(1) })
        4 -> {
            // 历史整理:按周显示和切换,选中历史日期即查看它所在的一周。
            val historyWeek by viewModel.historyWeek.collectAsStateWithLifecycle()
            StatsScreen(
                state,
                historyWeek = historyWeek,
                onSelectHistoryWeek = viewModel::selectHistoryWeek,
                weekStatsOf = viewModel::weekStatsOf,
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

/**
 * 关闭预览前先让目标 cell 进入 viewport（若它在屏幕外）：返回飞行需要在转场首帧就能
 * 找到与预览同 key 的 shared element，否则照片全屏停留、末了才跳进格子（滚动发生在
 * 动画中途还会让目标格移动）。requestScrollToItem 同步生效，grid 未组合时也能调用。
 */
internal fun revealGridItemIfOffscreen(state: LazyGridState, index: Int) {
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

