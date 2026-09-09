package com.einsli.photoroulette.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Size as CoilSize
import com.einsli.photoroulette.model.PhotoItem
import com.einsli.photoroulette.media.PreviewCache
import kotlinx.coroutines.delay
import java.util.Locale

/** "1:23" / "1:02:03" — used by the duration pill and the player's time labels. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/**
 * AsyncImage that decodes a representative video frame (1s in) instead of the first frame
 * (which is often black). For images it behaves exactly like a plain AsyncImage.
 *
 * [thumbSize] pins the request to the same key as the review card's SharedGridImage, so the
 * behind-card / flying-card copies hit the already-loaded cache entry instead of re-decoding.
 *
 * [usePreviewFile] 读取 PreviewCache 的磁盘封面小图(cacheDir/previews):冷启动打开即显。
 * 未命中时回退原图请求,并在成功后 write-through 补写小图,下次启动即命中。
 */
@Composable
fun VideoAwareImage(photo: PhotoItem, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, thumbSize: CoilSize? = null, usePreviewFile: Boolean = false) {
    val context = LocalContext.current
    val request = remember(photo.uri, thumbSize, usePreviewFile) {
        if (usePreviewFile) PreviewCache.homeRequest(context, photo, thumbSize)
        else photoThumbRequest(context, photo, thumbSize)
    }
    // 封面缓存未命中 → 本次回退原图;成功后补写小图(write-through),下次冷启动即显。
    val writeThrough: ((AsyncImagePainter.State.Success) -> Unit)? =
        if (usePreviewFile && !PreviewCache.hasPreview(context, photo.mediaId)) {
            { _ -> PreviewCache.ensureAsync(context, photo) }
        } else {
            null
        }
    AsyncImage(
        model = request,
        contentDescription = photo.displayName,
        modifier = modifier,
        contentScale = contentScale,
        onSuccess = writeThrough,
    )
}

/**
 * Video marker drawn over a photo cell: a centered play triangle in a scrim + a duration pill
 * at the bottom-end. Renders nothing for images. Must be placed as a SIBLING of the shared
 * element (not inside it) so the badge doesn't zoom with the photo during transitions.
 */
@Composable
fun VideoBadge(photo: PhotoItem, modifier: Modifier = Modifier, centerSize: Dp = 44.dp, textSize: Int = 12) {
    if (!photo.mimeType.startsWith("video/")) return
    Box(modifier) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(centerSize)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "视频",
                tint = Color.White,
                modifier = Modifier.size(centerSize * 0.72f),
            )
        }
        if (photo.duration > 0) {
            Text(
                formatDuration(photo.duration),
                color = Color.White,
                fontSize = textSize.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * Full-screen video playback for the preview: VideoView (zero new dependencies) framed by the
 * video's first-frame thumbnail until the video is prepared. A floating control bar (rounded
 * pill) offers play/pause, a seek slider and time labels.
 *
 * [active] is false for pager pages that are off-screen — the video pauses so it never keeps
 * playing in the background. [resetTick] mirrors ZoomablePhoto's contract: bumping it (the
 * preview is closing) pauses the video and immediately calls [onResetDone], so the shared
 * element can return without waiting on a playing video.
 *
 * 全屏模式下：点击屏幕**不**切换播放/暂停，而是由 [onTap] 回调（图片同款：隐藏/显示标题、
 * 按钮和本进度条）；播放/暂停交给控制条里的按钮。控制条通过 [bottomInset] 悬浮在底部按钮
 * 上方，并随 [chromeProgress] 与标题/按钮一起飞出/飞回。
 */
@Composable
fun VideoPhoto(
    photo: PhotoItem,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    resetTick: Int = 0,
    onResetDone: () -> Unit = {},
    placeholderRequest: ImageRequest? = null,
    /**
     * true = shared element 飞行期（打开 / 关闭起飞前）：不挂载播放器，只渲染宫格同款静态帧
     * （带 Crop↔Fit morph）。VideoView 未 prepared 的 surface 是纯黑、内容也与宫格缩略图
     * 不同源，直接参与飞行会从黑块起飞/落地跳变；飞行落定后调用方翻回 false 再挂播放器。
     * 整理页（默认 false）行为不变。
     */
    staticFrame: Boolean = false,
    /**
     * 播放器已挂载时用静态帧盖在上面、直到 prepared 才淡出（无缝交棒：避免 VideoView
     * 未 prepared 的纯黑 surface 盖掉静态帧闪一下黑）。staticFrame=true 时无需此参数。
     */
    coverWithFrame: Boolean = false,
    /** 静态帧的 Crop↔Fit morph（同 ZoomablePhoto.cropFitProgress）：0=Crop（飞行起点=
     *  宫格观感）、1=Fit（静止态）。由 SharedPhotoPreview 的 contentMorph 驱动。 */
    cropFitProgress: Float = 1f,
    /** 控制条离屏幕底部的悬浮高度（全屏模式下=底部按钮行高+间距；0=贴底）。 */
    bottomInset: Dp = 0.dp,
    /** 0=显示，1=隐藏（与标题/按钮的 chromeProgress 同步）。 */
    chromeProgress: Float = 0f,
    /** 隐藏时控制条向下飞出的距离（与按钮一致）。 */
    chromeExitPx: Float = 0f,
    /** 非空：点击屏幕调用它（隐藏/显示 chrome）；null：点击屏幕切换播放/暂停（整理页预览）。 */
    onTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var prepared by remember(photo.mediaId) { mutableStateOf(false) }
    var isPlaying by remember(photo.mediaId) { mutableStateOf(false) }
    var positionMs by remember(photo.mediaId) { mutableLongStateOf(0L) }
    var durationMs by remember(photo.mediaId) { mutableLongStateOf(photo.duration.coerceAtLeast(0L)) }

    // Same fixed screen-size request as SharedGridImage's Fit copy (data + size + frame param
    // all identical), so the return transition hits the memory-cache entry instead of re-decoding.
    val previewSize = rememberScreenPixelSize()
    val thumbRequest = remember(photo.uri, previewSize) {
        ImageRequest.Builder(context).data(photo.uri).size(previewSize).videoFrameMillis(1000).build()
    }
    // Crop↔Fit 缩放比（方形宫格，仅静态帧生效）：把 Fit 帧额外放大 cropRatio 倍即等于
    // Crop。aspect 来自解码缓存（宫格缩略图解码时已写入，视频=1s 帧的宽高比），取不到
    // 则退化为 1（纯 Fit，无 morph）。
    val aspect = remember(photo.mediaId) { PhotoAspectCache.get(photo.mediaId) }
    val cropRatio = aspect?.let { cropToFitRatio(it) } ?: 1f
    val cropFitScale = 1f + (cropRatio - 1f) * (1f - cropFitProgress)

    LaunchedEffect(photo.mediaId, videoView) {
        val vv = videoView ?: return@LaunchedEffect
        vv.setVideoURI(Uri.parse(photo.uri))
        vv.setOnPreparedListener { mp ->
            durationMs = mp.duration.toLong().coerceAtLeast(0L)
            prepared = true
            if (active) {
                mp.start()
                isPlaying = true
            }
        }
        vv.setOnCompletionListener {
            isPlaying = false
            positionMs = durationMs
        }
        vv.setOnErrorListener { _, _, _ ->
            prepared = false
            true
        }
    }

    // Resume when this page becomes the current one; pause when it stops being current.
    LaunchedEffect(active, prepared) {
        val vv = videoView ?: return@LaunchedEffect
        if (prepared) {
            if (active && !vv.isPlaying) {
                vv.start()
                isPlaying = true
            } else if (!active && vv.isPlaying) {
                vv.pause()
                isPlaying = false
            }
        }
    }

    // Position polling while playing, so the seek slider tracks the video.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val vv = videoView
            if (vv != null && vv.currentPosition >= 0) positionMs = vv.currentPosition.toLong()
            delay(250)
        }
    }

    // Closing the preview, swapping back to the static flight frame (player unmounts), or the
    // pager page leaving composition stops playback immediately.
    DisposableEffect(photo.mediaId, staticFrame) {
        onDispose {
            runCatching { videoView?.stopPlayback() }
        }
    }

    LaunchedEffect(resetTick) {
        if (resetTick > 0) {
            videoView?.let { runCatching { it.pause() } }
            isPlaying = false
            onResetDone()
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        // 播放器：飞行期（staticFrame）不挂载，落定后才有——静态帧与它无缝交棒。
        if (!staticFrame) {
            AndroidView(
                factory = { ctx -> VideoView(ctx).apply { videoView = this } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 静态帧（宫格同款 1s 帧，同 cache key）：staticFrame（飞行期）或 coverWithFrame
        // （播放器已挂但未 prepared）时盖在播放器上层。旧实现把它们垫在 VideoView 下面——
        // VideoView 未 prepared 的 surface 是纯黑，等于永远看不见，飞行因此是一块黑。
        // prepared 后一起淡出露出真正的视频（180ms 交叉淡化）；staticFrame 时 prepared 恒
        // 为 false，常显。morph 与 ZoomablePhoto 的 cropFitProgress 同一套机制：0=Crop
        // （飞行起点与宫格 cell 观感一致）、1=Fit。
        if (staticFrame || coverWithFrame) {
            if (placeholderRequest != null) {
                val placeholderAlpha by animateFloatAsState(
                    targetValue = if (prepared) 0f else 1f,
                    animationSpec = tween(180),
                    label = "videoPlaceholderAlpha",
                )
                Image(
                    painter = rememberAsyncImagePainter(placeholderRequest),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = cropFitScale; scaleY = cropFitScale }
                        .alpha(placeholderAlpha),
                    contentScale = ContentScale.Fit,
                )
            }
            // Sharper frame shown while the video prepares; fades in once decoded.
            var thumbReady by remember(photo.mediaId, thumbRequest) { mutableStateOf(false) }
            val thumbAlpha by animateFloatAsState(
                targetValue = if (thumbReady && !prepared) 1f else 0f,
                animationSpec = tween(180),
                label = "videoThumbAlpha",
            )
            Image(
                painter = rememberAsyncImagePainter(thumbRequest, onSuccess = { thumbReady = true }),
                contentDescription = photo.displayName,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = cropFitScale; scaleY = cropFitScale }
                    .alpha(thumbAlpha),
                contentScale = ContentScale.Fit,
            )
        }
        // Tap layer: 全屏模式下隐藏/显示 chrome（与图片一致）；整理页预览保持播放/暂停。
        // Taps only — vertical drags still reach the swipe-down-to-close gesture and horizontal
        // drags reach the pager.
        val currentOnTap by rememberUpdatedState(onTap)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(photo.mediaId) {
                    detectTapGestures(onTap = {
                        val cb = currentOnTap
                        if (cb != null) {
                            cb()
                        } else {
                            val vv = videoView ?: return@detectTapGestures
                            if (vv.isPlaying) {
                                vv.pause()
                                isPlaying = false
                            } else {
                                vv.start()
                                isPlaying = true
                            }
                        }
                    })
                }
        )
        if (prepared && !staticFrame) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = bottomInset)
                    .graphicsLayer { translationY = chromeProgress * chromeExitPx }
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .pointerInput(photo.mediaId) {
                            detectTapGestures(onTap = {
                                val vv = videoView ?: return@detectTapGestures
                                if (vv.isPlaying) {
                                    vv.pause()
                                    isPlaying = false
                                } else {
                                    vv.start()
                                    isPlaying = true
                                }
                            })
                        },
                )
                val maxDur = durationMs.coerceAtLeast(1L).toFloat()
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, maxDur),
                    onValueChange = { target ->
                        positionMs = target.toLong()
                        videoView?.seekTo(target.toInt())
                    },
                    valueRange = 0f..maxDur,
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                Text(
                    "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
