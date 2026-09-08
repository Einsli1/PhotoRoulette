package com.einsli.photoroulette.ui

// 整理页（Review）：全屏滑动 保留/删除 + 撤销 + chrome 隐藏/显示，含 SwipePhoto 手势卡片。
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.size.Size as CoilSize
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.einsli.photoroulette.ReviewSession
import com.einsli.photoroulette.data.PhotoEntity
import com.einsli.photoroulette.data.PhotoState

@Composable internal fun Review(session: ReviewSession?, onAction: (Long, PhotoState, Int, Long) -> Boolean, onUndo: () -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
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
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    // 固定在状态栏 + 标题栏（返回按钮 ~54dp）下方；用进入页面时捕获的状态栏
                    // 高度，状态栏隐藏时时间戳不会跟着跳到屏幕顶端。
                    .padding(start = 12.dp, top = statusBarTop + 64.dp)
            ) {
                // 图片名字：位置在时间上方，样式与时间一致（同款黑底药丸 + bodySmall 白字）。
                Text(
                    photo.displayName,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    formatTaken(photo.dateTaken),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
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
