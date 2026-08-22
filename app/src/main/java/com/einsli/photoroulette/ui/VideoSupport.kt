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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.einsli.photoroulette.data.PhotoEntity
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
 */
@Composable
fun VideoAwareImage(photo: PhotoEntity, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val request = remember(photo.uri) {
        ImageRequest.Builder(context).data(photo.uri).apply {
            if (photo.mimeType.startsWith("video/")) videoFrameMillis(1000)
        }.build()
    }
    AsyncImage(model = request, contentDescription = photo.displayName, modifier = modifier, contentScale = contentScale)
}

/**
 * Video marker drawn over a photo cell: a centered play triangle in a scrim + a duration pill
 * at the bottom-end. Renders nothing for images. Must be placed as a SIBLING of the shared
 * element (not inside it) so the badge doesn't zoom with the photo during transitions.
 */
@Composable
fun VideoBadge(photo: PhotoEntity, modifier: Modifier = Modifier, centerSize: Dp = 44.dp, textSize: Int = 12) {
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
 * video's first-frame thumbnail until the video is prepared. Tap toggles play/pause; a bottom
 * control bar offers play/pause, a seek slider and time labels.
 *
 * [active] is false for pager pages that are off-screen — the video pauses so it never keeps
 * playing in the background. [resetTick] mirrors ZoomablePhoto's contract: bumping it (the
 * preview is closing) pauses the video and immediately calls [onResetDone], so the shared
 * element can return without waiting on a playing video.
 */
@Composable
fun VideoPhoto(
    photo: PhotoEntity,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    resetTick: Int = 0,
    onResetDone: () -> Unit = {},
    placeholderRequest: ImageRequest? = null,
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

    // Closing the preview (or the pager page leaving composition) stops playback immediately.
    DisposableEffect(photo.mediaId) {
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

    Box(modifier.fillMaxSize().background(Color.Black)) {
        // Cached source thumbnail (the cell/card's bitmap): instant on the first open, covers
        // the decode gap of the sharper frame below.
        if (placeholderRequest != null) {
            Image(
                painter = rememberAsyncImagePainter(placeholderRequest),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
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
            modifier = Modifier.fillMaxSize().alpha(thumbAlpha),
            contentScale = ContentScale.Fit,
        )
        AndroidView(
            factory = { ctx -> VideoView(ctx).apply { videoView = this } },
            modifier = Modifier.fillMaxSize(),
        )
        // Tap layer: toggles play/pause. Taps only — vertical drags still reach the
        // swipe-down-to-close gesture and horizontal drags reach the pager.
        Box(
            Modifier
                .fillMaxSize()
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
                }
        )
        if (prepared) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
