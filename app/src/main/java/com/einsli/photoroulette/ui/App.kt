package com.einsli.photoroulette.ui

import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.compose.animation.*
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.einsli.photoroulette.PhotoViewModel
import com.einsli.photoroulette.MemoryInfo
import com.einsli.photoroulette.ReviewSession
import com.einsli.photoroulette.data.AppSettings
import com.einsli.photoroulette.data.PhotoEntity
import com.einsli.photoroulette.data.PhotoState
import kotlin.math.roundToInt

@Composable fun PhotoRouletteApp(viewModel: PhotoViewModel, onAction: (Long, PhotoState, Int, Long) -> Boolean, onCommitDeletes: () -> Unit, onRestoreFromTrash: (List<Long>) -> Unit) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
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
    PhotoRouletteTheme(dark = isDark, dynamicColor = useDynamic) {
        val dc = designColors()
        Scaffold(
            containerColor = dc.pageBg,
            bottomBar = {
                // The review and memory pages are immersive: no bottom navigation.
                if (page != 2 && page != 5) {
                    NavigationBar(containerColor = dc.navBar, tonalElevation = 0.dp) {
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = dc.accentText,
                    selectedTextColor = dc.ink,
                    indicatorColor = dc.card,
                    unselectedIconColor = dc.labelGray,
                    unselectedTextColor = dc.labelGray,
                )
                NavigationBarItem(selected = page == 0, onClick = { page = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("首页") }, colors = itemColors)
                NavigationBarItem(selected = page == 4, onClick = { page = 4 }, icon = { Icon(Icons.Default.Info, null) }, label = { Text("统计") }, colors = itemColors)
                NavigationBarItem(selected = page == 1, onClick = { page = 1 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") }, colors = itemColors)
                }
            }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (page) {
                    0 -> Home(state, onStart = { viewModel.reload(); page = 2 }, onScan = { showPicker = true }, onOpenMemory = { page = 5 })
                    1 -> Settings(state.settings, viewModel, openTrash = { page = 3 })
                    3 -> RecycleBin(viewModel, onRestore = onRestoreFromTrash) { page = 0 }
                    4 -> StatsScreen(state)
                    5 -> MemoryViewer(state.stats.memory, onBack = { page = 0 })
                    else -> {
                        val session by viewModel.sessionFlow.collectAsStateWithLifecycle(initialValue = viewModel.sessionFlow.value)
                        Review(session, onAction, onUndo = viewModel::undo, onDone = { onCommitDeletes() }, onBack = { page = 0 })
                    }
                }
            }
        }
    }

    if (showPicker) {
        AlbumsPicker(viewModel, state.settings, onClose = { showPicker = false })
    }
}

@Composable private fun AlbumsPicker(viewModel: PhotoViewModel, settings: com.einsli.photoroulette.data.AppSettings, onClose: () -> Unit) {
    var albums by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf(settings.includedAlbums.toSet()) }
    val scope = rememberCoroutineScope()
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
            scope.launch { viewModel.saveSettings(settings.copy(includedAlbums = selected.toList())); viewModel.scan() }
            onClose()
        }) { Text("确定") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable private fun RecycleBin(viewModel: com.einsli.photoroulette.PhotoViewModel, onRestore: (List<Long>) -> Unit, onBack: () -> Unit) {
    val items by viewModel.trashItems.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var previewIndex by remember { mutableIntStateOf(-1) }
    val previewOpen = previewIndex in items.indices
    BackHandler(enabled = previewOpen) { previewIndex = -1 }
    // The preview is a full-screen overlay INSIDE RecycleBin's own layout, not a separate
    // Dialog window. A Dialog's height is measured as WRAP_CONTENT, which on some devices
    // reports a taller-than-visible window and pushes the bottom buttons off-screen.
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
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

@Composable private fun TrashPreview(photos: List<PhotoEntity>, initialIndex: Int, onClose: () -> Unit, onRestore: (List<Long>) -> Unit, onDelete: (List<Long>) -> Unit) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    val dismissThreshold = with(LocalDensity.current) { 160.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                            onDragEnd = { if (dragY > dismissThreshold) onClose() else dragY = 0f },
                            onDragCancel = { dragY = 0f }
                        )
                    }
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭", tint = Color.White) }
                    Text(photos[pagerState.currentPage].displayName, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("${pagerState.currentPage + 1}/${photos.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                }
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                    ZoomablePhoto(photos[page])
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    // System back (including the edge-swipe gesture) returns to the home screen.
    BackHandler(onBack = onBack)
    // ---- lifecycle-aware cooldown ----
    // A plain delay() keeps ticking while the activity is paused, so when a session ends and
    // the system trash confirmation dialog is on top, the cooldown finishes behind it — by the
    // time the user comes back the new session's first card is already on screen, which reads
    // as "the photo jumped by itself". Track the lifecycle and hold the gate closed until the
    // activity is RESUMED again, then require a minimum 400 ms before showing the card.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE -> false
                else -> resumed
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // `remember(sessionId)` creates a FRESH MutableState(true) every time the session changes,
    // so the cooldown gate is closed *synchronously* during composition — zero frames of the new
    // photo leaking through.
    val currentId = session?.sessionId
    val gate = remember(currentId) { mutableStateOf(true) }
    LaunchedEffect(currentId, resumed) {
        if (currentId == null) return@LaunchedEffect
        // Session finished (no photo left): nothing to hide, open immediately.
        if (session.current == null) { gate.value = false; return@LaunchedEffect }
        // A new session arrived while the app was paused (system trash dialog). Keep the
        // spinner; this effect restarts when ON_RESUME fires and runs the cooldown then.
        if (!resumed) return@LaunchedEffect
        delay(400)
        gate.value = false
    }
    // ----------------------------------------------------------------
    val dc = designColors()
    Column(Modifier.fillMaxSize().background(dc.pageBg).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("本次整理", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = dc.ink, modifier = Modifier.weight(1f))
            Text("剩余 ${session?.remaining ?: 0} / ${session?.queue?.size ?: 0}", fontSize = 13.sp, color = dc.slate)
        }
        Spacer(Modifier.height(12.dp))
        when {
            session == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator(color = dc.accent) }
            gate.value && session.current != null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator(color = dc.accent) }
            session.current == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("本次完成！", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = dc.ink)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDone, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = dc.accent, contentColor = Color.White)) { Text("处理删除并继续整理") }
            }
            else -> Box(Modifier.fillMaxWidth().weight(1f)) { SwipePhoto(session, onAction, onUndo) }
        }
    }
}

private fun formatTaken(taken: Long): String =
    if (taken <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(taken))

@Composable private fun SwipePhoto(session: ReviewSession, onAction: (Long, PhotoState, Int, Long) -> Boolean, onUndo: () -> Unit) {
    val dc = designColors()
    val photo = session.current!!
    val swipeThreshold = with(LocalDensity.current) { 120.dp.toPx() }
    val flyOutDistance = with(LocalDensity.current) { 1600.dp.toPx() }
    // [session.lastActionDir] is the direction the PREVIOUS card was swiped (1=right keep,
    // -1=left delete), kept in the session so it survives
    // recomposition. The current card slides IN from the opposite side. On undo the direction
    // is negated — the card re-enters from where it was thrown.
    // Timestamp of the last real pointer event (drag or tap) in this screen. The MIUI
    // handwriting/accessibility service injects clicks with NO pointer events, so the
    // ViewModel can tell real swipes from injected ones by comparing this with cardShownAt.
    var userTouchedAt by remember { mutableStateOf(0L) }
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
            AnimatedContent(
                targetState = photo,
                transitionSpec = {
                    val dir = session.lastActionDir
                    val enter = when (dir) {
                        1 -> slideInHorizontally { -it } + fadeIn(tween(180))
                        -1 -> slideInHorizontally { it } + fadeIn(tween(180))
                        2 -> slideInVertically { -it } + fadeIn(tween(180))
                        -2 -> slideInVertically { it } + fadeIn(tween(180))
                        else -> slideInHorizontally { it } + fadeIn(tween(180))
                    }
                    enter togetherWith fadeOut(tween(220))
                },
                label = "card"
            ) { currentPhoto ->
                var x by remember(currentPhoto.mediaId) { mutableFloatStateOf(0f) }
                var y by remember(currentPhoto.mediaId) { mutableFloatStateOf(0f) }
                val scope = rememberCoroutineScope()
                // The card asks the ViewModel to advance ITSELF by its own mediaId plus the
                // swipe direction. The ViewModel only advances if this photo is still current,
                // so a swipe landing on the card fading out from the previous transition is
                // refused and can never advance the next card too.
                AsyncImage(
                    currentPhoto.uri, currentPhoto.displayName,
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).graphicsLayer {
                        translationX = x; translationY = y
                        rotationZ = (x / 35f).coerceIn(-25f, 25f)
                    }.pointerInput(currentPhoto.mediaId) {
                        detectDragGestures(
                            onDrag = { change, amount ->
                                change.consume()
                                x += amount.x; y += amount.y
                            },
                            onDragEnd = {
                                val dir: Int
                                val state: PhotoState
                                when {
                                    x < -swipeThreshold -> { dir = -1; state = PhotoState.DELETE_PENDING }
                                    x > swipeThreshold -> { dir = 1; state = PhotoState.KEEP }
                                    else -> { dir = 0; state = PhotoState.KEEP }
                                }
                                val accepted = if (dir != 0) onAction(currentPhoto.mediaId, state, dir, userTouchedAt) else false
                                if (accepted) {
                                    val dirX = if (x < 0f) -1f else 1f
                                    val startX = x; val startY = y
                                    val endX = dirX * flyOutDistance
                                    val endY = 0f
                                    scope.launch {
                                        val start = System.currentTimeMillis()
                                        while (true) {
                                            val t = ((System.currentTimeMillis() - start).toFloat() / 180f).coerceIn(0f, 1f)
                                            val eased = 1f - (1f - t) * (1f - t)
                                            x = startX + (endX - startX) * eased
                                            y = startY + (endY - startY) * eased
                                            if (t >= 1f) break
                                            withFrameMillis { }
                                        }
                                    }
                                } else {
                                    // Below threshold or ViewModel refused: ease back to center.
                                    scope.launch {
                                        val sx = x; val sy = y
                                        val start = System.currentTimeMillis()
                                        while (true) {
                                            val t = ((System.currentTimeMillis() - start).toFloat() / 150f).coerceIn(0f, 1f)
                                            val eased = 1f - (1f - t) * (1f - t)
                                            x = sx * (1f - eased); y = sy * (1f - eased)
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
@Composable private fun Settings(settings: AppSettings, vm: PhotoViewModel, openTrash: () -> Unit) {
    val dc = designColors()
    var showTimePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(dc.pageBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = dc.ink)
        Spacer(Modifier.height(14.dp))

        // ── 外观: Material 3 SegmentedButton (selected = purple) ──
        SettingCard {
            Text("外观", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.slate)
            Spacer(Modifier.height(8.dp))
            val options = listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (label, mode) ->
                    SegmentedButton(
                        selected = settings.darkMode == mode,
                        onClick = { vm.setDarkMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Text(label, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 每次整理数量: standard Material 3 Slider ──
        var count by remember(settings) { mutableIntStateOf(settings.dailyCount) }
        SettingCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("每次整理数量", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.ink)
                Text("$count 张", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = dc.accentText)
            }
            Slider(
                value = count.toFloat(),
                onValueChange = { count = it.roundToInt().coerceIn(5, 50) },
                valueRange = 5f..50f,
                steps = 8,
                onValueChangeFinished = { vm.setDailyCount(count) }
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── 每日提醒: tap the time for a Material 3 TimePicker ──
        var hour by remember(settings) { mutableIntStateOf(settings.reminderHour) }
        SettingCard {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showTimePicker = true }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("每日提醒", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.ink)
                    Text("点击时间可精确设置", fontSize = 11.sp, color = dc.labelGray)
                }
                Text("${hour.toString().padStart(2, '0')}:${settings.reminderMinute.toString().padStart(2, '0')}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = dc.accentText)
            }
            Slider(
                value = hour.toFloat(),
                onValueChange = { hour = it.roundToInt().coerceIn(0, 23) },
                valueRange = 0f..23f,
                steps = 22,
                onValueChangeFinished = { vm.setReminderHour(hour) }
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── 照片范围 ──
        SettingCard {
            Text("照片范围", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.slate)
            Spacer(Modifier.height(4.dp))
            val rangeOptions = listOf(
                "all" to "全部照片",
                "lastYear" to "最近一年",
                "beforeLastYear" to "一年以前",
                "custom" to "自定义时间",
            )
            rangeOptions.forEach { (key, label) ->
                SettingRadioRow(label, settings.photoRange == key, onClick = { vm.setPhotoRange(key) }, dc)
            }
            if (settings.photoRange == "custom") {
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (settings.customRangeStart > 0) "从 ${dateFormatter.format(Date(settings.customRangeStart))} 起" else "尚未选择起始日期",
                        fontSize = 12.sp,
                        color = dc.slate
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showDatePicker = true }) { Text("选择日期", color = dc.accentText, fontSize = 12.sp) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 整理策略 ──
        SettingCard {
            Text("整理策略", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.slate)
            Spacer(Modifier.height(4.dp))
            val strategyOptions = listOf(
                "random" to "随机",
                "oldest" to "优先旧照片",
                "largest" to "优先大照片",
            )
            strategyOptions.forEach { (key, label) ->
                SettingRadioRow(label, settings.strategy == key, onClick = { vm.setStrategy(key) }, dc)
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
            SettingNavRow("整理记录", { showResetConfirm = true }, dc)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(initialHour = settings.reminderHour, initialMinute = settings.reminderMinute, is24Hour = true)
        BasicAlertDialog(onDismissRequest = { showTimePicker = false }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("选择提醒时间", style = MaterialTheme.typography.titleMedium)
                    TimePicker(state = timeState)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showTimePicker = false }) { Text("取消") }
                        TextButton(onClick = {
                            vm.setReminderHour(timeState.hour)
                            vm.setReminderMinute(timeState.minute)
                            showTimePicker = false
                        }) { Text("确定") }
                    }
                }
            }
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
private fun SettingRadioRow(label: String, selected: Boolean, onClick: () -> Unit, dc: DesignColors) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 14.sp, color = dc.ink)
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

/** Full-screen browse of "N年前的今天" photos, reached via 回忆时光机 → 去看看. */
@Composable
private fun MemoryViewer(memory: MemoryInfo?, onBack: () -> Unit) {
    val dc = designColors()
    val photos = memory?.photos ?: emptyList()
    var previewIndex by remember { mutableIntStateOf(-1) }
    // System back (including the edge-swipe gesture): close the full-screen preview first,
    // otherwise return to the home screen.
    BackHandler {
        if (previewIndex in photos.indices) previewIndex = -1 else onBack()
    }
    Box(Modifier.fillMaxSize().background(dc.pageBg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
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
                                .clickable { previewIndex = index }
                        ) {
                            AsyncImage(photo.uri, photo.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
        }
        if (previewIndex in photos.indices) {
            MemoryPreview(photos, previewIndex, onClose = { previewIndex = -1 })
        }
    }
}

@Composable
private fun MemoryPreview(photos: List<PhotoEntity>, initialIndex: Int, onClose: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭", tint = Color.White) }
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
