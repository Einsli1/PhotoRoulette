package com.einsli.photoroulette.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.einsli.photoroulette.data.PhotoEntity

/**
 * 全屏照片：双指缩放（1x–5x）+ 平移。
 *
 * [enabled] 为 false（shared element 转场进行中）时忽略手势，避免转场和缩放同时控制图片。
 * [resetTick] 递增时先把缩放/平移动画回缩到基础状态（1x、居中），再回调 [onResetDone]——
 * 关闭预览前先恢复基础态，shared element 返回动画就不会从 3x 等用户变换状态起跳。
 */
@Composable
fun ZoomablePhoto(
    photo: PhotoEntity,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resetTick: Int = 0,
    onResetDone: () -> Unit = {},
    placeholderRequest: ImageRequest? = null,
) {
    var scale by remember(photo.mediaId) { mutableFloatStateOf(1f) }
    var offsetX by remember(photo.mediaId) { mutableFloatStateOf(0f) }
    var offsetY by remember(photo.mediaId) { mutableFloatStateOf(0f) }
    var viewportW by remember(photo.mediaId) { mutableIntStateOf(0) }
    var viewportH by remember(photo.mediaId) { mutableIntStateOf(0) }

    // Fixed-size request (the screen size) instead of the constraint-based default: the request
    // key is stable across the shared-element transition (no re-decodes as the animated bounds
    // change) and matches the preload issued by SharedGridImage, so the very first preview open
    // has the bitmap ready and never flashes blank.
    val context = LocalContext.current
    val previewSize = rememberScreenPixelSize()
    val request = remember(photo.uri, previewSize) {
        ImageRequest.Builder(context).data(photo.uri).size(previewSize).build()
    }

    // Close flow: animate back to the base (1x, centered) state before the shared element
    // takes over, so the return transition never starts from a user transform.
    LaunchedEffect(resetTick) {
        if (resetTick > 0) {
            val fromScale = scale
            val fromX = offsetX
            val fromY = offsetY
            if (fromScale > 1f || fromX != 0f || fromY != 0f) {
                animate(0f, 1f, animationSpec = tween(180, easing = FastOutSlowInEasing)) { p, _ ->
                    scale = fromScale + (1f - fromScale) * p
                    offsetX = fromX * (1f - p)
                    offsetY = fromY * (1f - p)
                }
            }
            onResetDone()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewportW = it.width; viewportH = it.height }
    ) {
        // Cached source thumbnail (the cell/card's bitmap) under the full-screen copy: on the
        // first open it is already loaded, so the preview never shows a blank gap while the
        // full-res decode runs. Follows the same zoom/pan transform as the main image.
        if (placeholderRequest != null) {
            var placeholderReady by remember(photo.mediaId, placeholderRequest) { mutableStateOf(false) }
            val placeholderPainter = rememberAsyncImagePainter(
                placeholderRequest,
                onSuccess = { placeholderReady = true },
                contentScale = ContentScale.Fit,
            )
            val placeholderAlpha by animateFloatAsState(
                targetValue = if (placeholderReady) 1f else 0f,
                animationSpec = tween(180),
                label = "placeholderAlpha",
            )
            Image(
                painter = placeholderPainter,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offsetX; translationY = offsetY
                    }
                    .alpha(placeholderAlpha),
                contentScale = ContentScale.Fit,
            )
        }
        // Full-screen copy: fades in over the placeholder once decoded (instant on cache hits,
        // so repeat opens are unchanged).
        var fullReady by remember(photo.mediaId) { mutableStateOf(false) }
        val fullAlpha by animateFloatAsState(
            targetValue = if (fullReady) 1f else 0f,
            animationSpec = tween(180),
            label = "fullAlpha",
        )
        AsyncImage(
            model = request,
            contentDescription = photo.displayName,
            onSuccess = { fullReady = true },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offsetX; translationY = offsetY
                }
                .alpha(fullAlpha)
                .pointerInput(photo.mediaId, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // Single finger pans only while already zoomed; otherwise leave the events
                        // unconsumed so the pager (horizontal) or swipe-down dismiss (vertical)
                        // handles them. A second finger always starts a pinch-zoom that consumes
                        // everything.
                        var consumed = scale > 1f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) consumed = true
                            if (consumed) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                // Pointer positions are delivered in this image's local
                                // coordinates, which the graphicsLayer scale on this same node
                                // divides by the zoom factor: a screen-space finger move of N px
                                // arrives as N/scale here. translationX/Y is applied in screen
                                // pixels, so multiply the pan back up by the scale that was active
                                // during this event to track 1:1.
                                val panScale = scale
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                val maxPanX = (viewportW * (scale - 1f)) / 2f
                                val maxPanY = (viewportH * (scale - 1f)) / 2f
                                // Add each event's delta to the clamped offset instead of
                                // accumulating an unbounded panX/panY and clamping only at write
                                // time: once an overshoot at an edge grows past maxPan, reversing
                                // the drag first has to "eat" the whole overshoot before the image
                                // moves again, which makes the zoomed drag feel stuck/slow.
                                offsetX = (offsetX + pan.x * panScale).coerceIn(-maxPanX, maxPanX)
                                offsetY = (offsetY + pan.y * panScale).coerceIn(-maxPanY, maxPanY)
                                event.changes.forEach { it.consume() }
                            }
                            if (pressed == 0) break
                        }
                        if (scale <= 1f) { offsetX = 0f; offsetY = 0f }
                    }
                },
            contentScale = ContentScale.Fit,
        )
    }
}
