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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import android.os.SystemClock
import android.widget.NumberPicker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import coil.request.videoFrameMillis
import coil.size.Size as CoilSize
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
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

@Composable fun PhotoRouletteApp(viewModel: PhotoViewModel, onAction: (Long, PhotoState, Int, Long) -> Boolean, onCommitDeletes: () -> Unit, onRestoreFromTrash: (List<Long>) -> Unit) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    // Collected at the app level so the value is already loaded when the RecycleBin opens.
    val trashItems by viewModel.trashItems.collectAsStateWithLifecycle(emptyList())
    var page by rememberSaveable { mutableIntStateOf(0) }
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
        if (page != newPage) page = newPage
    }
    PhotoRouletteTheme(dark = isDark, dynamicColor = useDynamic) {
        val dc = designColors()
        Scaffold(
            containerColor = dc.pageBg,
            bottomBar = {
                // Review (2) and MemoryViewer (5) are immersive: no bottom navigation.
                if (page !in immersivePages) {
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
            // Page transition, "zoom from the tapped card" style: when opening an immersive
            // page (Review/RecycleBin/MemoryViewer) the current page stays fully visible
            // underneath while the new page grows from its entry card and covers it; going
            // back, the immersive page shrinks back into the card, revealing the static
            // destination page underneath. Tab-to-tab keeps a subtle center zoom.
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val enteringImmersive = targetState in immersivePages
                    val leavingImmersive = initialState in immersivePages
                    when {
                        enteringImmersive -> {
                            // Current page stays put (no exit), new page zooms in from its card.
                            val origin = pageTransformOrigin(targetState)
                            val ct = (scaleIn(
                                initialScale = 0.55f,
                                transformOrigin = origin,
                                animationSpec = tween(320, easing = FastOutSlowInEasing)
                            ) + fadeIn(tween(260)))
                                .togetherWith(ExitTransition.None)
                            ct.targetContentZIndex = 1f
                            ct
                        }
                        leavingImmersive -> {
                            // Destination page is already fully visible underneath; the immersive
                            // page shrinks back into the card it came from.
                            val origin = pageTransformOrigin(initialState)
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
                        else -> {
                            // Plain tab switches: subtle center zoom both ways.
                            (scaleIn(
                                initialScale = 0.94f,
                                transformOrigin = TransformOrigin(0.5f, 0.5f),
                                animationSpec = tween(240, easing = FastOutSlowInEasing)
                            ) + fadeIn(tween(180)))
                                .togetherWith(
                                    scaleOut(
                                        targetScale = 0.96f,
                                        transformOrigin = TransformOrigin(0.5f, 0.5f),
                                        animationSpec = tween(200)
                                    ) + fadeOut(tween(160))
                                )
                        }
                    }
                },
                label = "page"
            ) { targetPage ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (targetPage in immersivePages) Modifier else Modifier.padding(padding))
                ) {
                    PageContent(
                        page = targetPage,
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
        0 -> Home(state, onStart = { viewModel.reload(); onNavigate(2) }, onScan = onScan, onOpenMemory = { onNavigate(5) })
        1 -> Settings(state.settings, viewModel, scrollState = settingsScroll, savedScroll = savedSettingsScroll, openTrash = { onNavigate(3) })
        3 -> RecycleBin(trashItems, viewModel, onRestore = onRestoreFromTrash, onBack = { onNavigate(1) })
        4 -> StatsScreen(state)
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable private fun RecycleBin(items: List<PhotoEntity>, viewModel: com.einsli.photoroulette.PhotoViewModel, onRestore: (List<Long>) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var previewIndex by remember { mutableIntStateOf(-1) }
    val previewOpen = previewIndex in items.indices
    // Page-level back returns to Settings. While the preview is open, SharedPhotoPreview's own
    // BackHandler (composed later) wins and closes the preview first.
    BackHandler(onBack = onBack)
    val gridState = rememberLazyGridState()
    // Cell-sized decode target for grid thumbnails: 3 columns, so ~screenWidth/3 px. Fixing the
    // request size keeps every cell's memory-cache entry identical and small, so fast scrolling
    // re-shows already-loaded photos instantly instead of re-decoding.
    val gridCellPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp.dp.toPx() / 3f).roundToInt()
    }
    val gridThumbSize = remember(gridCellPx) { CoilSize(gridCellPx, gridCellPx) }
    // Preload thumbnails aggressively: a first batch immediately on entry (so the initial
    // viewports are decoded before the user scrolls), then a window around the visible range
    // while scrolling. The requests use the exact same data+size as the cells, so they fill
    // the memory-cache entries the cells will read — a fast fling re-shows photos instantly
    // instead of re-decoding, and a cell that scrolls out mid-load is not wasted (the preload
    // already holds the decoded bitmap).
    val preloadContext = LocalContext.current
    val preloadLoader = remember(preloadContext) { preloadContext.imageLoader }
    LaunchedEffect(gridState, items, gridThumbSize) {
        // Enqueue each index exactly once (no repeated requests piling up in Coil's queue):
        // a monotonically advancing frontier keeps the queue short so early requests finish fast.
        var frontier = -1
        fun enqueueUntil(end: Int) {
            val e = end.coerceAtMost(items.size)
            while (frontier < e) {
                frontier++
                val photo = items.getOrNull(frontier) ?: break
                preloadLoader.enqueue(
                    ImageRequest.Builder(preloadContext)
                        .data(photo.uri)
                        .size(gridThumbSize)
                        .apply {
                            if (photo.mimeType.startsWith("video/")) videoFrameMillis(1000)
                        }
                        .build()
                )
            }
        }
        // Batch 1: the first ~3 viewports (≈ 40 cells) immediately on entry.
        enqueueUntil(40)
        // Then advance the frontier as the user scrolls: always ~1.5 viewports ahead and keep
        // ~1 viewport behind for upward scrolls.
        snapshotFlow {
            val info = gridState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            first to last
        }.collect { (first, _) ->
            enqueueUntil(first + 54)
        }
    }
    PhotoSharedTransitionLayout {
        Box(Modifier.fillMaxSize()) {
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
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("回收站", style = MaterialTheme.typography.headlineMedium)
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
                        Text("长按选中，点击预览", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        if (items.isEmpty()) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("回收站为空") }
                        } else {
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(items) { index, photo ->
                                    val checked = selected.contains(photo.mediaId)
                                    // Live copy of `checked`: pointerInput does NOT restart when
                                    // selection changes, so the long-press handler must read the
                                    // latest value through a live state.
                                    val liveChecked by rememberUpdatedState(checked)
                                    Box(
                                        Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .pointerInput(photo.mediaId) {
                                                detectTapGestures(
                                                    onTap = { previewIndex = index },
                                                    onLongPress = { selected = if (liveChecked) selected - photo.mediaId else selected + photo.mediaId }
                                                )
                                            }
                                    ) {
                                        SharedGridImage(photo, radius, this@AnimatedContent, Modifier.fillMaxSize(), gridSize = gridThumbSize)
                                        VideoBadge(photo, Modifier.fillMaxSize(), centerSize = 26.dp, textSize = 9)
                                        if (checked) {
                                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "已选中",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(6.dp)
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary)
                                                    .padding(3.dp)
                                            )
                                        }
                                    }
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
                        onClose = { current ->
                            scope.launch {
                                val idx = items.indexOfFirst { it.mediaId == current.mediaId }
                                // Scroll the grid BEFORE closing so the returning photo's cell is
                                // already in view (and composed) when the return transition starts.
                                if (idx >= 0) revealGridItemIfOffscreen(gridState, idx)
                                previewIndex = -1
                            }
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable private fun Review(session: ReviewSession?, onAction: (Long, PhotoState, Int, Long) -> Boolean, onUndo: () -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    // System back (including the edge-swipe gesture) returns to the home screen. While the
    // full-screen preview is open, SharedPhotoPreview's own BackHandler (composed later) wins
    // and closes the preview first.
    BackHandler(onBack = onBack)
    var previewOpen by remember { mutableStateOf(false) }
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
    val current = session?.current
    // Fixed thumbnail size shared by the card, the preview's source-scale copy and the preview
    // placeholder, so the first preview open reuses the already-loaded card bitmap instead of
    // showing a blank gap while the full-screen copy decodes.
    val config = LocalConfiguration.current
    val density = LocalDensity.current.density
    val cardThumbSize = remember {
        CoilSize(
            (config.screenWidthDp * density).roundToInt(),
            ((config.screenHeightDp - 190).coerceAtLeast(240) * density).roundToInt(),
        )
    }
    PhotoSharedTransitionLayout {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = if (previewOpen && current != null) 0 else null,
                transitionSpec = {
                    fadeIn(tween(PhotoTransitionMillis)) togetherWith fadeOut(tween(PhotoTransitionMillis))
                },
                label = "reviewPreview",
            ) { target ->
                if (target == null) {
                    // ── Card branch ──
                    val radius = photoBranchRadius(gridCornerRadius = 20.dp, gridSide = true)
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(dc.pageBg)
                            // Review is composed full-bleed; inset ourselves so the header stays
                            // clear of the status bar.
                            .systemBarsPadding()
                            .padding(horizontal = 20.dp)
                            .graphicsLayer {
                                scaleX = animScale.value
                                scaleY = animScale.value
                            }
                    ) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                            Text("本次整理", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = dc.ink, modifier = Modifier.weight(1f))
                            Text("剩余 ${session?.remaining ?: 0} / ${session?.queue?.size ?: 0}", fontSize = 13.sp, color = dc.slate)
                        }
                        Spacer(Modifier.height(12.dp))
                        val s = session
                        when {
                            // No loading spinner: render nothing until the session is published.
                            s == null -> Box(Modifier.fillMaxWidth().weight(1f))
                            s.current == null -> Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text("本次完成！", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = dc.ink)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = onDone, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = dc.accent, contentColor = Color.White)) { Text("处理删除并继续整理") }
                            }
                            else -> Box(Modifier.fillMaxWidth().weight(1f)) {
                                SwipePhoto(s, onAction, onUndo, animatedRadius = radius, animatedVisibilityScope = this@AnimatedContent, cardThumbSize = cardThumbSize, onTapPhoto = { previewOpen = true })
                            }
                        }
                    }
                } else {
                    // ── Preview branch ──
                    if (current != null) {
                        SharedPhotoPreview(
                            photos = listOf(current),
                            initialIndex = 0,
                            animatedRadius = photoBranchRadius(gridCornerRadius = 20.dp, gridSide = false),
                            animatedVisibilityScope = this@AnimatedContent,
                            sourceContentScale = ContentScale.Fit,
                            sourceThumbSize = cardThumbSize,
                            onClose = { previewOpen = false },
                        )
                    }
                }
            }
        }
    }
}

private fun formatTaken(taken: Long): String =
    if (taken <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(taken))

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable private fun SharedTransitionScope.SwipePhoto(session: ReviewSession, onAction: (Long, PhotoState, Int, Long) -> Boolean, onUndo: () -> Unit, animatedRadius: Dp, animatedVisibilityScope: AnimatedVisibilityScope, cardThumbSize: CoilSize, onTapPhoto: (PhotoEntity) -> Unit) {
    val dc = designColors()
    val photo = session.current!!
    val next = session.queue.getOrNull(session.position + 1)
    val swipeThreshold = with(LocalDensity.current) { 120.dp.toPx() }
    val flyOutDistance = with(LocalDensity.current) { 1600.dp.toPx() }
    // Timestamp of the last real pointer event (drag or tap) in this screen. The MIUI
    // handwriting/accessibility service injects clicks with NO pointer events, so the
    // ViewModel can tell real swipes from injected ones by comparing this with cardShownAt.
    var userTouchedAt by remember { mutableStateOf(0L) }
    // Drag offset of the current (top) card.
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    // The card currently flying out after an accepted swipe. Kept separate from the current card
    // so it keeps animating off-screen after the session advances to the next photo.
    var flyingPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var flyingX by remember { mutableFloatStateOf(0f) }
    var flyingY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // Undo slide-in: when returning to the previous card it slides back in from the side it was
    // swiped away to (kept → from the right, deleted → from the left), covering the card that
    // was shown after it — that card stays visible underneath the whole time. The Animatable is
    // re-created per photo and starts at the off-screen position directly, so the returning card
    // never renders centered and then jumps off-screen (that snap looked like a flash / "two
    // cards").
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
    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        userTouchedAt = SystemClock.elapsedRealtime()
                    }
                }
            }
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            // Next card behind — revealed as the current card is dragged away, so the next photo
            // shows DURING the swipe instead of only after the current one is fully gone. Same
            // request key as the current card's SharedGridImage (cardThumbSize), so when it
            // becomes current it is an instant cache hit instead of a fresh decode (which flashed).
            if (next != null) {
                VideoAwareImage(
                    next,
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(dc.pageBg),
                    contentScale = ContentScale.Fit,
                    thumbSize = cardThumbSize
                )
            }
            // Current card on top, draggable. Its image is the shared element: it flies to the
            // full-screen preview (放大查看) and back.
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .graphicsLayer {
                        translationX = dragX + slideInX.value; translationY = dragY
                        rotationZ = (dragX / 35f).coerceIn(-25f, 25f)
                    }
                    .background(dc.pageBg)
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
                                    // Reset the top card's offset synchronously so the next card starts
                                    // centered once the session advances; the swiped card keeps flying
                                    // out via [flyingPhoto]/[flyingX]/[flyingY].
                                    dragX = 0f
                                    dragY = 0f
                                    // The card asks the ViewModel to advance ITSELF by its own mediaId
                                    // plus the swipe direction. The ViewModel only advances if this photo
                                    // is still current, so a ghost drag can never advance the next card.
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
                    .pointerInput(photo.mediaId) {
                        // Tap opens the full-screen 放大查看 preview; drags (swipe to keep/delete)
                        // are unaffected — detectTapGestures cancels once the finger moves past slop.
                        detectTapGestures(onTap = { onTapPhoto(photo) })
                    }
            ) {
                SharedGridImage(photo, animatedRadius, animatedVisibilityScope, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, gridSize = cardThumbSize)
                VideoBadge(photo, Modifier.fillMaxSize(), centerSize = 48.dp, textSize = 12)
            }
            // Flying-out card on top (rendered last = topmost), so it visibly slides off over
            // the already-revealed next card.
            val flying = flyingPhoto
            if (flying != null) {
                VideoAwareImage(
                    flying,
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).graphicsLayer {
                        translationX = flyingX
                        translationY = flyingY
                        rotationZ = (flyingX / 35f).coerceIn(-25f, 25f)
                    }.background(dc.pageBg),
                    contentScale = ContentScale.Fit,
                    thumbSize = cardThumbSize
                )
            }
            Text(
                formatTaken(photo.dateTaken),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(dc.white.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = dc.ink
            )
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 上一张: outline / secondary
            OutlinedButton(
                onClick = onUndo,
                enabled = session.position > 0,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = dc.labelGray),
                border = BorderStroke(1.dp, dc.track)
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

    Column(
        Modifier
            .fillMaxSize()
            .background(dc.pageBg)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = dc.ink)
        Spacer(Modifier.height(14.dp))

        // ── 外观 ──
        SettingCard {
            SettingValueRow(label = "外观") {
                SettingDropdown(
                    options = listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2),
                    selectedValue = settings.darkMode,
                    onSelect = vm::setDarkMode
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 每次整理数量: tap the value to expand the wheel (5–100, step 5) ──
        var count by remember(settings.dailyCount) { mutableIntStateOf(settings.dailyCount) }
        var showCountWheel by remember { mutableStateOf(false) }
        SettingCard {
            SettingValueRow(
                label = "每次整理数量",
                onClick = { showCountWheel = !showCountWheel },
                expanded = showCountWheel
            ) {
                Text("$count 张", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = dc.accentText)
            }
            AnimatedVisibility(
                visible = showCountWheel,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(180))
            ) {
                WheelNumberPicker(
                    values = (5..100 step 5).toList(),
                    selected = count,
                    modifier = Modifier.fillMaxWidth(),
                    onValueChange = { count = it; vm.setDailyCount(it) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 每日提醒: tap the time to expand hour / minute wheels ──
        var hour by remember(settings.reminderHour) { mutableIntStateOf(settings.reminderHour) }
        var minute by remember(settings.reminderMinute) { mutableIntStateOf(settings.reminderMinute) }
        var showTimeWheel by remember { mutableStateOf(false) }
        SettingCard {
            SettingValueRow(
                label = "每日提醒",
                onClick = { showTimeWheel = !showTimeWheel },
                expanded = showTimeWheel
            ) {
                Text("${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = dc.accentText)
            }
            AnimatedVisibility(
                visible = showTimeWheel,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(180))
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    WheelNumberPicker(
                        values = (0..23).toList(),
                        selected = hour,
                        onValueChange = { hour = it; vm.setReminderHour(it) }
                    )
                    Text(":", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = dc.ink, modifier = Modifier.padding(horizontal = 8.dp))
                    WheelNumberPicker(
                        values = (0..59).toList(),
                        selected = minute,
                        onValueChange = { minute = it; vm.setReminderMinute(it) }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 照片范围 ──
        SettingCard {
            SettingValueRow(label = "照片范围") {
                SettingDropdown(
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
                SettingDropdown(
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

/** Compact pill-shaped dropdown for a single-choice setting; sits on the right of a row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingDropdown(
    options: List<Pair<String, T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
) {
    val dc = designColors()
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.second == selectedValue }?.first ?: options.first().first
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        Row(
            Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .clip(RoundedCornerShape(10.dp))
                .background(if (expanded) dc.accent.copy(alpha = 0.14f) else dc.white)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selectedLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.accentText)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, null, tint = dc.labelGray, modifier = Modifier.size(18.dp))
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (display, value) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Classic Android spinner wheel over a fixed list of Int values. Colors are set explicitly so
 *  the wheel matches the app's own theme (the activity theme follows the SYSTEM dark mode). */
@Composable
private fun WheelNumberPicker(
    values: List<Int>,
    selected: Int,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit,
) {
    val dc = designColors()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            NumberPicker(ctx).apply {
                wrapSelectorWheel = false
                displayedValues = values.map { it.toString() }.toTypedArray()
                minValue = 0
                maxValue = values.size - 1
                value = values.indexOf(selected).coerceAtLeast(0)
                setOnValueChangedListener { _, _, newVal -> onValueChange(values[newVal]) }
            }
        },
        update = { picker ->
            // setTextColor exists (API 29+). The divider-color setters were removed from the SDK
            // on newer APIs, so only the text color is themed here.
            picker.setTextColor(dc.ink.toArgb())
            val idx = values.indexOf(selected)
            if (idx >= 0 && picker.value != idx) picker.value = idx
        }
    )
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
    val scope = rememberCoroutineScope()
    // System back (including the edge-swipe gesture) returns to the home screen. While the
    // preview is open, SharedPhotoPreview's own BackHandler (composed later) closes it first.
    BackHandler(onBack = onBack)
    val gridState = rememberLazyGridState()
    // Preload memory-grid thumbnails aggressively (first batch on entry, then a window around
    // the visible range) — same trick as RecycleBin, so fast flings rarely show placeholders.
    val preloadContext = LocalContext.current
    val preloadLoader = remember(preloadContext) { preloadContext.imageLoader }
    LaunchedEffect(gridState, photos, gridThumbSize) {
        // Enqueue each index exactly once (monotonic frontier keeps Coil's queue short).
        var frontier = -1
        fun enqueueUntil(end: Int) {
            val e = end.coerceAtMost(photos.size)
            while (frontier < e) {
                frontier++
                val photo = photos.getOrNull(frontier) ?: break
                preloadLoader.enqueue(
                    ImageRequest.Builder(preloadContext)
                        .data(photo.uri)
                        .size(gridThumbSize)
                        .apply {
                            if (photo.mimeType.startsWith("video/")) videoFrameMillis(1000)
                        }
                        .build()
                )
            }
        }
        enqueueUntil(40)
        snapshotFlow {
            val info = gridState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            first
        }.collect { first ->
            enqueueUntil(first + 54)
        }
    }
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
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
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
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(photos) { index, photo ->
                                    Box(
                                        Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(dc.white)
                                            .clickable { previewIndex = index }
                                    ) {
                                        SharedGridImage(photo, radius, this@AnimatedContent, Modifier.fillMaxSize(), gridSize = gridThumbSize)
                                        VideoBadge(photo, Modifier.fillMaxSize(), centerSize = 26.dp, textSize = 9)
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
                        sourceThumbSize = gridThumbSize,
                        onClose = { current ->
                            scope.launch {
                                val idx = photos.indexOfFirst { it.mediaId == current.mediaId }
                                // Scroll the grid BEFORE closing so the returning photo's cell is
                                // already in view (and composed) when the return transition starts.
                                if (idx >= 0) revealGridItemIfOffscreen(gridState, idx)
                                previewIndex = -1
                            }
                        },
                    )
                }
            }
        }
    }
}
