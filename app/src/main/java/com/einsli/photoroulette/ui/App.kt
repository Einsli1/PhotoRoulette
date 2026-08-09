package com.einsli.photoroulette.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
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
import com.einsli.photoroulette.ReviewSession
import com.einsli.photoroulette.BuildConfig
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
    PhotoRouletteTheme(dark = isDark) {
        Scaffold(bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) { NavigationBarItem(selected = page == 0, onClick = { page = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("首页") }); NavigationBarItem(selected = page == 1, onClick = { page = 1 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") }) } }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (page == 0) Home(state, onStart = { viewModel.reload(); page = 2 }, onScan = { showPicker = true }, openTrash = { page = 3 })
                else if (page == 1) Settings(state.settings, viewModel)
                else if (page == 3) RecycleBin(viewModel, onRestore = onRestoreFromTrash) { page = 0 }
                else {
                    val session by viewModel.sessionFlow.collectAsStateWithLifecycle(initialValue = viewModel.sessionFlow.value)
                    Review(session, onAction, onUndo = viewModel::undo, onDone = { onCommitDeletes() })
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

@Composable private fun Home(state: com.einsli.photoroulette.AppUiState, onStart: () -> Unit, onScan: () -> Unit, openTrash: () -> Unit) = Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Text("照片轮盘", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(36.dp))
    if (state.loading) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("正在加载照片队列…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("本次还有 ${state.remaining} 张", style = MaterialTheme.typography.headlineMedium)
        Text("已整理 ${state.processed} / ${state.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(32.dp)); Button(onStart, Modifier.fillMaxWidth().height(56.dp), enabled = !state.loading && (state.remaining > 0 || state.session?.queue?.isNotEmpty() == true)) { Text("开始整理") }
    Spacer(Modifier.height(12.dp)); OutlinedButton(onScan, Modifier.fillMaxWidth()) { Text(if (state.total == 0) "扫描相册" else "重新扫描相册") }
    Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = openTrash, Modifier.fillMaxWidth()) { Text("回收站") }
}

@Composable private fun Review(session: ReviewSession?, onAction: (Long, PhotoState, Int, Long) -> Boolean, onUndo: () -> Unit, onDone: () -> Unit) {
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
        if (session?.current == null) { gate.value = false; return@LaunchedEffect }
        // A new session arrived while the app was paused (system trash dialog). Keep the
        // spinner; this effect restarts when ON_RESUME fires and runs the cooldown then.
        if (!resumed) return@LaunchedEffect
        delay(400)
        gate.value = false
    }
    // ----------------------------------------------------------------
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("本次整理", style = MaterialTheme.typography.titleLarge); Row { TextButton(onClick = onUndo, enabled = (session?.position ?: 0) > 0) { Text("上一张") }; Text("剩余 ${session?.remaining ?: 0} / ${session?.queue?.size ?: 0}") } }
        Spacer(Modifier.height(16.dp))
        when {
            session == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            gate.value && session.current != null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            session.current == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("本次完成！", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(16.dp)); Button(onDone) { Text("处理删除并继续整理") } }
            else -> Box(Modifier.fillMaxWidth().weight(1f)) { SwipePhoto(session, onAction) }
        }
    }
}

private fun formatTaken(taken: Long): String =
    if (taken <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(taken))

@Composable private fun SwipePhoto(session: ReviewSession, onAction: (Long, PhotoState, Int, Long) -> Boolean) {
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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { onAction(photo.mediaId, PhotoState.DELETE_PENDING, -1, userTouchedAt) }, Modifier.weight(1f)) { Text("删除") }
            FilledTonalButton(onClick = { onAction(photo.mediaId, PhotoState.KEEP, 1, userTouchedAt) }, Modifier.weight(1f), colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFBBFFFF), contentColor = Color(0xFF00382F))) { Text("保留") }
        }
    }
}

@Composable private fun Settings(settings: AppSettings, vm: PhotoViewModel) = Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Text("设置", style = MaterialTheme.typography.headlineMedium)

    // Theme — three options, applies immediately
    Text("外观", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val labels = listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2)
        labels.forEach { (label, mode) ->
            val selected = settings.darkMode == mode
            FilterChip(selected = selected, onClick = { vm.setDarkMode(mode) }, label = { Text(label) })
        }
    }

    // Daily count — slider, saves on release
    var count by remember(settings) { mutableIntStateOf(settings.dailyCount) }
    Text("每次照片数量：$count")
    Slider(count.toFloat(), { count = it.roundToInt().coerceIn(5, 50) }, valueRange = 5f..50f, steps = 8,
        onValueChangeFinished = { vm.setDailyCount(count) })

    // Reminder hour
    var hour by remember(settings) { mutableIntStateOf(settings.reminderHour) }
    Text("每日提醒：${hour.toString().padStart(2, '0')}:00")
    Slider(hour.toFloat(), { hour = it.roundToInt().coerceIn(0, 23) }, valueRange = 0f..23f, steps = 22,
        onValueChangeFinished = { vm.setReminderHour(hour) })

    // Toggles — save immediately
    var videos by remember(settings) { mutableStateOf(settings.includeVideos) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("包含视频")
        Switch(videos, { videos = it; vm.setIncludeVideos(it) })
    }
    var screenshots by remember(settings) { mutableStateOf(settings.includeScreenshots) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("包含截图")
        Switch(screenshots, { screenshots = it; vm.setIncludeScreenshots(it) })
    }

    Spacer(Modifier.height(12.dp))
    OutlinedButton(vm::reset, Modifier.fillMaxWidth()) { Text("重置整理记录") }
}
