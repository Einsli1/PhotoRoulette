package com.einsli.photoroulette.ui

import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import com.einsli.photoroulette.AppUiState
import com.einsli.photoroulette.PhotoViewModel
import com.einsli.photoroulette.MemoryInfo
import com.einsli.photoroulette.ReviewSession
import com.einsli.photoroulette.data.AppSettings
import com.einsli.photoroulette.data.PhotoEntity
import com.einsli.photoroulette.data.PhotoState

/** Pages that use the zoom in / zoom out transition and hide the bottom navigation bar. */
private val zoomPages = setOf(2, 3, 5)

@Composable fun PhotoRouletteApp(viewModel: PhotoViewModel, onAction: (Long, PhotoState, Int, Long) -> Boolean, onCommitDeletes: () -> Unit, onRestoreFromTrash: (List<Long>) -> Unit) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    // Collected at the app level so the value is already loaded when the RecycleBin opens. The
    // zoom overlay would otherwise start from the empty initial list and flash mid-zoom (empty
    // state → grid, and the 全选 button's text/size changes as the data arrives).
    val trashItems by viewModel.trashItems.collectAsStateWithLifecycle(emptyList())
    var page by rememberSaveable { mutableIntStateOf(0) }
    // [basePage] is the page composed UNDERNEATH. During a zoom transition the zoom page is
    // layered on top of it (entering: zooming in; leaving: zooming out), so the destination
    // page is already composed behind the outgoing one while it shrinks away — the old page
    // never "flashes in from nothing" at the end of the exit animation.
    var basePage by rememberSaveable { mutableIntStateOf(0) }
    val overlayPage = remember { mutableStateOf<Int?>(null) }
    // Plain states (not Animatable): initial values are set synchronously in navigate(), so the
    // overlay's very first frame already has the correct scale/alpha — no full-size flash.
    var overlayScale by remember { mutableFloatStateOf(1f) }
    var overlayAlpha by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    var overlayJob by remember { mutableStateOf<Job?>(null) }
    // True while a zoom page is zooming IN: nothing is composed behind it then.
    var overlayEntering by remember { mutableStateOf(false) }
    // Hoisted so the Settings scroll position survives Settings being disposed while the
    // RecycleBin (a zoom page) is open on top of it.
    val settingsScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
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
        val old = page
        if (old == newPage) return
        val entering = newPage in zoomPages
        val exiting = old in zoomPages
        if (!entering && !exiting) {
            // Plain switch (bottom navigation): no zoom.
            page = newPage
            basePage = newPage
            return
        }
        // The zoom page always animates ON TOP. While it is ENTERING, nothing is composed behind
        // it (just the plain background), so the previous page is never visible through or around
        // it; while it is EXITING, the destination page is composed behind it and revealed as it
        // shrinks away.
        overlayJob?.cancel()
        overlayEntering = entering
        overlayPage.value = if (entering) newPage else old
        basePage = newPage
        page = newPage
        // Set the initial transform synchronously (plain state writes) so the overlay's first
        // frame is already at the correct scale/alpha — the overlay never renders full-size.
        if (entering) {
            overlayScale = 0.6f
            overlayAlpha = 1f
        } else {
            overlayScale = 1f
            overlayAlpha = 1f
        }
        overlayJob = scope.launch {
            if (entering) {
                // No alpha on entrance: the page stays opaque while zooming in — translucency
                // made the previous page's content show through and made light text look dark
                // (black-ish) over the dark background during the animation.
                animate(0.6f, 1f, animationSpec = tween(400, easing = FastOutSlowInEasing)) { value, _ -> overlayScale = value }
            } else {
                coroutineScope {
                    launch { animate(1f, 0.6f, animationSpec = tween(300, easing = FastOutSlowInEasing)) { value, _ -> overlayScale = value } }
                    launch { animate(1f, 0f, animationSpec = tween(300)) { value, _ -> overlayAlpha = value } }
                }
            }
            overlayPage.value = null
            overlayEntering = false
            basePage = newPage
            overlayJob = null
        }
    }
    PhotoRouletteTheme(dark = isDark, dynamicColor = useDynamic) {
        val dc = designColors()
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = dc.pageBg,
                bottomBar = {
                    // Zoom pages are immersive: no bottom navigation.
                    if (page !in zoomPages) {
                        NavigationBar(containerColor = dc.navBar, tonalElevation = 0.dp) {
                            val itemColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = dc.accentText,
                                selectedTextColor = dc.ink,
                                indicatorColor = dc.card,
                                unselectedIconColor = dc.labelGray,
                                unselectedTextColor = dc.labelGray,
                            )
                            NavigationBarItem(selected = page == 0, onClick = { navigate(0) }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("首页") }, colors = itemColors)
                            NavigationBarItem(selected = page == 4, onClick = { navigate(4) }, icon = { Icon(Icons.Default.Info, null) }, label = { Text("统计") }, colors = itemColors)
                            NavigationBarItem(selected = page == 1, onClick = { navigate(1) }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") }, colors = itemColors)
                        }
                    }
                }
            ) { padding ->
                // The staying page sits behind the animated overlay, inside the Scaffold content.
                // Zoom pages are full-bleed (they inset themselves with systemBarsPadding), so the
                // overlay and the base render identically and the swap at the end of the entrance
                // animation does not jump. While a zoom page is zooming IN, nothing is composed
                // here — the previous page must not shift or show through behind the entrance.
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (basePage in zoomPages) Modifier else Modifier.padding(padding))
                ) {
                    if (!overlayEntering) {
                        PageContent(
                            page = basePage,
                            state = state,
                            viewModel = viewModel,
                            settingsScroll = settingsScroll,
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
            // The zoom page animating in/out on top — covers the whole screen (nav bar included)
            // so the bottom bar never pops in while the outgoing page is still visible.
            val overlay = overlayPage.value
            if (overlay != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = overlayScale
                            scaleY = overlayScale
                            alpha = overlayAlpha
                        }
                ) {
                    // The overlay sits OUTSIDE the Scaffold, so it is outside the Surface that
                    // provides a themed LocalContentColor. Without this, Text/Icon (which default
                    // to LocalContentColor = Color.Black) render BLACK in dark mode.
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        PageContent(
                            page = overlay,
                            state = state,
                            viewModel = viewModel,
                            settingsScroll = settingsScroll,
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

/** One app page. Used both for the staying page (behind) and the zooming overlay page (on top). */
@Composable
private fun PageContent(
    page: Int,
    state: AppUiState,
    viewModel: PhotoViewModel,
    settingsScroll: ScrollState,
    trashItems: List<PhotoEntity>,
    onAction: (Long, PhotoState, Int, Long) -> Boolean,
    onCommitDeletes: () -> Unit,
    onRestoreFromTrash: (List<Long>) -> Unit,
    onNavigate: (Int) -> Unit,
    onScan: () -> Unit,
) {
    when (page) {
        0 -> Home(state, onStart = { viewModel.reload(); onNavigate(2) }, onScan = onScan, onOpenMemory = { onNavigate(5) })
        1 -> Settings(state.settings, viewModel, scrollState = settingsScroll, openTrash = { onNavigate(3) })
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

@Composable private fun RecycleBin(items: List<PhotoEntity>, viewModel: com.einsli.photoroulette.PhotoViewModel, onRestore: (List<Long>) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var previewIndex by remember { mutableIntStateOf(-1) }
    // Window bounds of each grid cell, recorded at layout — the preview zoom starts from the
    // tapped photo's actual position instead of the screen center.
    val cellBounds = remember { mutableStateOf<Map<Int, Rect>>(emptyMap()) }
    val previewOpen = previewIndex in items.indices
    // Page-level back returns to Settings. Composed BEFORE the preview handler below so the
    // preview (composed later) wins while it is open.
    BackHandler(onBack = onBack)
    BackHandler(enabled = previewOpen) { previewIndex = -1 }
    // The preview is a full-screen overlay INSIDE RecycleBin's own layout, not a separate
    // Dialog window. A Dialog's height is measured as WRAP_CONTENT, which on some devices
    // reports a taller-than-visible window and pushes the bottom buttons off-screen.
    Box(Modifier.fillMaxSize()) {
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
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items) { index, photo ->
                    val checked = selected.contains(photo.mediaId)
                    // Live copy of `checked`: pointerInput does NOT restart when selection changes,
                    // so the long-press handler must read the latest value through a live state.
                    val liveChecked by rememberUpdatedState(checked)
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .onGloballyPositioned { coords ->
                                val r = coords.boundsInWindow()
                                if (r.width > 0f) cellBounds.value = cellBounds.value + (index to r)
                            }
                            .pointerInput(photo.mediaId) {
                                detectTapGestures(
                                    onTap = { previewIndex = index },
                                    onLongPress = { selected = if (liveChecked) selected - photo.mediaId else selected + photo.mediaId }
                                )
                            }
                    ) {
                        AsyncImage(photo.uri, photo.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
        if (previewOpen) {
            TrashPreview(
                photos = items,
                initialIndex = previewIndex,
                sourceBounds = cellBounds.value[previewIndex],
                onClose = { previewIndex = -1 },
                onRestore = { ids ->
                    previewIndex = -1
                    onRestore(ids)
                },
                onDelete = { ids ->
                    previewIndex = -1
                    scope.launch { viewModel.deleteFromTrash(ids) }
                }
            )
        }
    }
}

/** Full-screen preview zoom: the content scales up from the tapped photo's [sourceBounds]
 *  (window coordinates) instead of the screen center, and shrinks back to it when [close] is
 *  invoked (then [onClosed] runs). */
@Composable
private fun ZoomPreview(
    sourceBounds: Rect?,
    modifier: Modifier = Modifier,
    onClosed: () -> Unit,
    content: @Composable (close: () -> Unit, chromeAlpha: Float) -> Unit,
) {
    val previewBounds = remember { mutableStateOf<Rect?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var fade by remember { mutableFloatStateOf(1f) }
    var chromeAlpha by remember { mutableFloatStateOf(1f) }
    var bgAlpha by remember { mutableFloatStateOf(1f) }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    var started by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var s0 by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    var openJob by remember { mutableStateOf<Job?>(null) }

    // Start the reveal once the preview's own bounds are known (first layout). Keyed on Unit so
    // later bounds updates (onGloballyPositioned can fire more than once) can never restart the
    // effect and cancel the running animation — that left the preview stuck at the tiny scale.
    LaunchedEffect(Unit) {
        val pb = snapshotFlow { previewBounds.value }
            .firstOrNull { it != null && it.width > 0f && it.height > 0f } ?: return@LaunchedEffect
        val sb = sourceBounds
        if (sb != null && sb.width > 0f) {
            s0 = (sb.width / pb.width).coerceIn(0.05f, 1f)
            origin = TransformOrigin(
                ((sb.center.x - pb.left) / pb.width).coerceIn(0f, 1f),
                ((sb.center.y - pb.top) / pb.height).coerceIn(0f, 1f)
            )
        } else {
            // Fallback (no recorded cell bounds): zoom from the center.
            s0 = 0.6f
            origin = TransformOrigin.Center
        }
        started = true
        scale = s0
        fade = 1f
        openJob = scope.launch {
            animate(s0, 1f, animationSpec = tween(320, easing = FastOutSlowInEasing)) { v, _ -> scale = v }
        }
    }

    val close: () -> Unit = {
        if (!closing) {
            closing = true
            // Cancel the entrance animation so it can never fight the exit for the scale state.
            openJob?.cancel()
            scope.launch {
                coroutineScope {
                    // Chrome (title/buttons) fades out immediately — it does NOT shrink with the
                    // photo, it just disappears over the shrinking preview.
                    launch { animate(chromeAlpha, 0f, animationSpec = tween(90)) { v, _ -> chromeAlpha = v } }
                    // The black background fades out too — it does NOT shrink either.
                    launch { animate(bgAlpha, 0f, animationSpec = tween(90)) { v, _ -> bgAlpha = v } }
                    // Shrink the photo back to the tapped cell — the grid behind is revealed the
                    // whole time, not only at the end.
                    launch { animate(scale, s0, animationSpec = tween(200, easing = FastOutSlowInEasing)) { v, _ -> scale = v } }
                }
                // Quick fade of the now tiny preview so its removal is seamless.
                animate(fade, 0f, animationSpec = tween(80)) { v, _ -> fade = v }
                onClosed()
            }
        }
    }

    Box(modifier) {
        // Full-screen black background: fades out on exit (does NOT shrink).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (started) bgAlpha else 0f }
                .background(Color.Black)
        )
        // Scaled layer: photo + chrome zoom together. On exit the black background and the
        // chrome (title/buttons) have already faded out, so only the photo visibly shrinks
        // back to the tapped cell.
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { previewBounds.value = it.boundsInWindow() }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = origin
                    alpha = if (started) fade else 0f
                }
        ) {
            content(close, chromeAlpha)
        }
    }
}

@Composable private fun TrashPreview(photos: List<PhotoEntity>, initialIndex: Int, sourceBounds: Rect?, onClose: () -> Unit, onRestore: (List<Long>) -> Unit, onDelete: (List<Long>) -> Unit) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    val dismissThreshold = with(LocalDensity.current) { 160.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }
    ZoomPreview(
        sourceBounds = sourceBounds,
        modifier = Modifier.fillMaxSize(),
        onClosed = onClose
    ) { close, chromeAlpha ->
        BackHandler { close() }
        Column(
            Modifier
                .fillMaxSize()
                // Keep the top title and bottom buttons clear of the status bar and
                // gesture/navigation bar in this edge-to-edge window.
                .systemBarsPadding()
                .graphicsLayer { translationY = dragY }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, amount ->
                            // Downward drags pull the preview down; upward ones snap back.
                            if (amount > 0f || dragY > 0f) {
                                dragY += amount
                                change.consume()
                            }
                        },
                        onDragEnd = { if (dragY > dismissThreshold) close() else dragY = 0f },
                        onDragCancel = { dragY = 0f }
                    )
                }
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).graphicsLayer { alpha = chromeAlpha }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.Default.Close, "关闭", tint = Color.White) }
                Text(photos[pagerState.currentPage].displayName, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text("${pagerState.currentPage + 1}/${photos.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                ZoomablePhoto(photos[page])
            }
            Row(Modifier.fillMaxWidth().padding(16.dp).graphicsLayer { alpha = chromeAlpha }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onRestore(listOf(photos[pagerState.currentPage].mediaId)) }, Modifier.weight(1f)) { Text("移出回收站") }
                Button(onClick = { onDelete(listOf(photos[pagerState.currentPage].mediaId)) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("永久删除") }
            }
        }
    }
}

@Composable private fun ZoomablePhoto(photo: PhotoEntity) {
    var scale by remember(photo.mediaId) { mutableFloatStateOf(1f) }
    var offsetX by remember(photo.mediaId) { mutableFloatStateOf(0f) }
    var offsetY by remember(photo.mediaId) { mutableFloatStateOf(0f) }
    var viewportW by remember(photo.mediaId) { mutableIntStateOf(0) }
    var viewportH by remember(photo.mediaId) { mutableIntStateOf(0) }
    // clipToBounds: graphicsLayer scales the rendering WITHOUT clipping, so a zoomed-in image
    // would overflow the page bounds and draw over the title/counter above. Clip keeps a 5x
    // zoom inside the pager area.
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewportW = it.width; viewportH = it.height }) {
        AsyncImage(
            photo.uri, photo.displayName,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offsetX; translationY = offsetY
            }.pointerInput(photo.mediaId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Single finger pans only while already zoomed; otherwise leave the events
                    // unconsumed so the pager (horizontal) or swipe-down dismiss (vertical) handles
                    // them. A second finger always starts a pinch-zoom that consumes everything.
                    var consumed = scale > 1f
                    var panX = 0f
                    var panY = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) consumed = true
                        if (consumed) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            panX += pan.x
                            panY += pan.y
                            val maxPanX = (viewportW * (scale - 1f)) / 2f
                            val maxPanY = (viewportH * (scale - 1f)) / 2f
                            offsetX = panX.coerceIn(-maxPanX, maxPanX)
                            offsetY = panY.coerceIn(-maxPanY, maxPanY)
                            event.changes.forEach { it.consume() }
                        }
                        if (pressed == 0) break
                    }
                    if (scale <= 1f) { offsetX = 0f; offsetY = 0f }
                }
            }
        )
    }
}

@Composable private fun Review(session: ReviewSession?, onAction: (Long, PhotoState, Int, Long) -> Boolean, onUndo: () -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    // System back (including the edge-swipe gesture) returns to the home screen. The page-level
    // zoom out is played by the parent's overlay transition, which keeps Home composed behind.
    BackHandler(onBack = onBack)
    // Light zoom-in when a NEW session arrives while already on this page (处理删除并继续整理).
    // The page enter/exit zoom belongs to the parent transition, so the first session seen here —
    // even if it arrives AFTER the parent's entrance has finished — must not zoom again, otherwise
    // the page would shrink back after reaching full size.
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
    Column(
        Modifier
            .fillMaxSize()
            .background(dc.pageBg)
            // Zoom pages are composed full-bleed (behind/overlay render identically); inset
            // ourselves so the header stays clear of the status bar.
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
            else -> Box(Modifier.fillMaxWidth().weight(1f)) { SwipePhoto(s, onAction, onUndo) }
        }
    }
}

private fun formatTaken(taken: Long): String =
    if (taken <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(taken))

@Composable private fun SwipePhoto(session: ReviewSession, onAction: (Long, PhotoState, Int, Long) -> Boolean, onUndo: () -> Unit) {
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
    // Undo slide-in: the card re-enters from the side it was thrown toward (kept → from the
    // right, deleted → from the left). Forward advances don't slide — the next card is already
    // revealed behind.
    val slideInX = remember { Animatable(0f) }
    var prevPosition by remember { mutableIntStateOf(session.position) }
    LaunchedEffect(photo.mediaId) {
        val isUndo = session.position < prevPosition
        prevPosition = session.position
        if (isUndo) {
            val from = when (session.lastActionDir) {
                1 -> -flyOutDistance
                -1 -> flyOutDistance
                else -> 0f
            }
            slideInX.snapTo(from)
            slideInX.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
        } else {
            slideInX.snapTo(0f)
        }
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
            // shows DURING the swipe instead of only after the current one is fully gone.
            if (next != null) {
                AsyncImage(
                    next.uri, next.displayName,
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(dc.pageBg),
                    contentScale = ContentScale.Fit
                )
            }
            // Current card on top, draggable.
            AsyncImage(
                photo.uri, photo.displayName,
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).graphicsLayer {
                    translationX = dragX + slideInX.value; translationY = dragY
                    rotationZ = (dragX / 35f).coerceIn(-25f, 25f)
                }.background(dc.pageBg).pointerInput(photo.mediaId) {
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
                },
                contentScale = ContentScale.Fit
            )
            // Flying-out card on top (rendered last = topmost), so it visibly slides off over
            // the already-revealed next card.
            val flying = flyingPhoto
            if (flying != null) {
                AsyncImage(
                    flying.uri, flying.displayName,
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).graphicsLayer {
                        translationX = flyingX
                        translationY = flyingY
                        rotationZ = (flyingX / 35f).coerceIn(-25f, 25f)
                    }.background(dc.pageBg),
                    contentScale = ContentScale.Fit
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
@Composable private fun Settings(settings: AppSettings, vm: PhotoViewModel, scrollState: ScrollState, openTrash: () -> Unit) {
    val dc = designColors()
    var showDatePicker by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showAlbumPicker by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(dc.pageBg)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
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
@Composable
private fun MemoryViewer(memory: MemoryInfo?, onBack: () -> Unit) {
    val dc = designColors()
    val photos = memory?.photos ?: emptyList()
    var previewIndex by remember { mutableIntStateOf(-1) }
    // Window bounds of each grid cell, recorded at layout — the preview zoom starts from the
    // tapped photo's actual position instead of the screen center.
    val cellBounds = remember { mutableStateOf<Map<Int, Rect>>(emptyMap()) }
    // System back (including the edge-swipe gesture): close the full-screen preview first,
    // otherwise return to the home screen.
    BackHandler {
        if (previewIndex in photos.indices) previewIndex = -1 else onBack()
    }
    Box(Modifier.fillMaxSize().background(dc.pageBg)) {
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
                                .onGloballyPositioned { coords ->
                                    val r = coords.boundsInWindow()
                                    if (r.width > 0f) cellBounds.value = cellBounds.value + (index to r)
                                }
                                .clickable { previewIndex = index }
                        ) {
                            AsyncImage(photo.uri, photo.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
        }
        if (previewIndex in photos.indices) {
            MemoryPreview(photos, previewIndex, sourceBounds = cellBounds.value[previewIndex], onClose = { previewIndex = -1 })
        }
    }
}

@Composable
private fun MemoryPreview(photos: List<PhotoEntity>, initialIndex: Int, sourceBounds: Rect?, onClose: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    ZoomPreview(
        sourceBounds = sourceBounds,
        modifier = Modifier.fillMaxSize(),
        onClosed = onClose
    ) { close, chromeAlpha ->
        BackHandler { close() }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).graphicsLayer { alpha = chromeAlpha },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = close) { Icon(Icons.Default.Close, "关闭", tint = Color.White) }
                Text(
                    photos[pagerState.currentPage].displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(
                    "${pagerState.currentPage + 1}/${photos.size}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                ZoomablePhoto(photos[page])
            }
        }
    }
}
