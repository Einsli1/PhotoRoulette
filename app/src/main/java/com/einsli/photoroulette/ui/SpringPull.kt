package com.einsli.photoroulette.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A gentler fling: the initial flick velocity is scaled down ([friction], 0.5 = half the
 * distance), so a quick swipe no longer rolls the page extremely far. Mirrors the default
 * spline-based fling otherwise.
 */
@Composable
fun rememberGentleFlingBehavior(friction: Float = 0.8f): FlingBehavior {
    val decay = rememberSplineBasedDecay<Float>()
    return remember(friction) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                if (abs(initialVelocity) <= 1f) return 0f
                var velocityLeft = initialVelocity * friction
                var lastValue = 0f
                var hitLimit = false
                val animationState = AnimationState(
                    initialValue = 0f,
                    initialVelocity = velocityLeft,
                )
                try {
                    animationState.animateDecay(decay) {
                        val delta = value - lastValue
                        val consumed = scrollBy(delta)
                        lastValue = value
                        if (abs(delta - consumed) > 0.5f) {
                            // Reached a scroll limit: cancel and return ZERO leftover velocity so
                            // the platform stretch/overscroll effect gets nothing to stretch with
                            // — the page stops right at the limit instead of traveling far.
                            hitLimit = true
                            this.cancelAnimation()
                        } else {
                            velocityLeft = this.velocity
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                }
                return if (hitLimit) 0f else velocityLeft
            }
        }
    }
}

/**
 * Full-screen elastic pull for pages: the page follows the finger with a rubber-band damping —
 * the further it is from rest, the less it follows ("越往下跟手的距离越小") — and springs back
 * on release. Works in BOTH directions (pull down and pull up). This is a translation of the
 * content, NOT a stretch.
 *
 * For scrollable pages ([usePointer] = false, the default) the pull engages only at the scroll
 * limits via a nested-scroll connection, which also swallows the platform stretch overscroll so
 * the two never double up. A fling that slams into a limit does not drag-pull; instead it kicks
 * a short bouncy spring whose size follows the arrival velocity (see [flingSpringScale]). For
 * non-scrollable pages ([usePointer] = true, e.g. Home) a direct drag gesture is used instead.
 */
@Composable
fun SpringPullBox(
    modifier: Modifier = Modifier,
    usePointer: Boolean = false,
    pullAtTop: () -> Float = { 0f },
    pullAtBottom: () -> Float = { 0f },
    content: @Composable BoxScope.() -> Unit,
) {
    var pull by remember { mutableFloatStateOf(0f) }
    var springJob by remember { mutableStateOf<Job?>(null) }
    // Whether the current pull came from a fling slamming into a limit (a spring kick) rather
    // than from a finger drag. Decides how the pull is released: bouncy spring vs. plain glide.
    var pullFromFling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val maxPull = with(LocalDensity.current) { 320.dp.toPx() }
    // Over-limit fling travel is scaled down before it becomes the spring kick, so a hard flick
    // kicks the page out ~50-100px while a slow one barely moves it (kick ∝ arrival velocity).
    val flingSpringScale = 2.0f
    // Remaining scrollable distance toward each limit (0 = already at the limit). Used to split
    // a drag delta into the scrollable part and the overscroll part that becomes the pull.
    val topRemainingState = rememberUpdatedState(pullAtTop)
    val bottomRemainingState = rememberUpdatedState(pullAtBottom)

    fun damped(delta: Float): Float {
        val damp = (1f - abs(pull) / maxPull).coerceIn(0.15f, 1f)
        val next = (pull + delta * damp).coerceIn(-maxPull, maxPull)
        // Snap to exactly 0 once negligible: any tiny leftover would keep the connection
        // intercepting scrolls (the "pulling" state) forever and block the page's scrolling.
        return if (abs(next) < 1f) 0f else next
    }

    /** Apply one drag delta: extend the pull with rubber-band damping, reduce it 1:1 against
     *  the pull, and snap to 0 once negligible. */
    fun applyDelta(delta: Float): Float {
        val next = if (abs(pull) > 1f && ((delta > 0f) != (pull > 0f))) {
            if (pull > 0f) (pull + delta).coerceAtLeast(0f) else (pull + delta).coerceAtMost(0f)
        } else {
            damped(delta)
        }
        return if (abs(next) < 1f) 0f else next
    }

    fun springBack() {
        springJob?.cancel()
        val start = pull
        val bouncy = pullFromFling
        springJob = scope.launch {
            // Drag pulls glide back with NO bounce (the user wants no overshoot there); a
            // fling-limit kick springs back with a short visible bounce, its size set by how
            // hard the fling hit the limit (see onPreScroll). Snap to exactly 0 at the end —
            // Compose springs stop NEAR the target, never exactly AT it, and a non-zero tail
            // would keep intercepting scrolls.
            animate(
                start, 0f,
                animationSpec = spring(
                    // Bouncy fling kicks: damping 0.4 gives a visible overshoot on the return
                    // (lower = more oscillation); drag pulls still glide back with no bounce.
                    dampingRatio = if (bouncy) 3.0f else Spring.DampingRatioNoBouncy,
                    stiffness = if (bouncy) Spring.StiffnessMedium else Spring.StiffnessMediumLow,
                ),
            ) { value, _ -> pull = if (abs(value) < 1f) 0f else value }
            pullFromFling = false
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy == 0f) return Offset.Zero
                if (source == NestedScrollSource.SideEffect) {
                    // Fling decay deltas (in Compose 1.7 the fling dispatches with SideEffect;
                    // NestedScrollSource.Fling is deprecated and never sent). Mid-list frames just
                    // scroll through. When the decay overshoots a limit, the over-limit part of
                    // this frame (≈ arrival velocity × frame time) becomes a SHORT spring kick:
                    // we set the pull but do NOT consume the delta, so the fling still sees the
                    // limit (consumed < delta), cancels, and returns zero leftover — the platform
                    // stretch stays out of it. onPostFling then releases the kick with a bouncy
                    // spring whose size follows the arrival speed.
                    if (abs(pull) > 1f) return Offset.Zero // an active drag pull owns the gesture
                    val remaining = if (dy > 0f) topRemainingState.value() else bottomRemainingState.value()
                    if (remaining >= abs(dy)) return Offset.Zero // mid-list — keep scrolling
                    val excess = if (dy > 0f) dy - remaining else dy + remaining // same sign as dy
                    if (excess == 0f) return Offset.Zero
                    springJob?.cancel()
                    val kick = damped(excess * flingSpringScale)
                    if (abs(kick) > 1f) {
                        pullFromFling = true
                        pull = kick
                    }
                    return Offset.Zero
                }
                // UserInput deltas below (finger drags only).
                pullFromFling = false
                // The nested-scroll delta follows the finger: finger down = dy > 0, finger up =
                // dy < 0.
                val pulling = abs(pull) > 1f
                if (pulling) {
                    // A pull is active: every delta adjusts it. Pulling back reduces 1:1 and the
                    // part past zero flows to the scrollable (smooth hand-off into scrolling);
                    // extending consumes everything.
                    springJob?.cancel()
                    val pullingBack = (dy > 0f) != (pull > 0f)
                    if (pullingBack) {
                        val next = if (pull > 0f) (pull + dy).coerceAtLeast(0f) else (pull + dy).coerceAtMost(0f)
                        val consumed = next - pull
                        pull = if (abs(next) < 1f) 0f else next
                        return Offset(0f, consumed)
                    } else {
                        pull = damped(dy)
                        return Offset(0f, dy)
                    }
                }
                // Not pulling yet: split the delta. The scrollable can consume up to the
                // remaining distance toward the limit it is heading for; the EXCESS becomes the
                // pull. Consuming the excess BEFORE the scrollable scrolls means the platform
                // stretch (which lives inside the scrollable) never gets any overscroll.
                val remaining = if (dy > 0f) topRemainingState.value() else bottomRemainingState.value()
                if (remaining >= abs(dy)) return Offset.Zero // fully scrollable — let it scroll
                val excess = if (dy > 0f) dy - remaining else dy + remaining // same sign as dy
                if (excess == 0f) return Offset.Zero
                springJob?.cancel()
                pull = damped(excess)
                return Offset(0f, excess)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Absorb flings that would overscroll a limit (they would otherwise flash the
                // platform stretch); let normal flings through.
                val atLimit = if (available.y > 0f) {
                    topRemainingState.value() <= 1f
                } else {
                    bottomRemainingState.value() <= 1f
                }
                return if (abs(pull) > 1f || atLimit) available else Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (abs(pull) > 1f) springBack()
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .graphicsLayer { translationY = pull }
            .then(
                if (usePointer) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var engaged = false
                            awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                                // over = the over-slop in px (positive = downward drag).
                                if (over != 0f && !change.isConsumed) {
                                    engaged = true
                                    springJob?.cancel()
                                    change.consume()
                                    pull = applyDelta(over)
                                }
                            } ?: return@awaitEachGesture
                            if (!engaged) return@awaitEachGesture
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                val delta = change.positionChange().y
                                change.consume()
                                pull = applyDelta(delta)
                                if (!change.pressed) break
                            }
                            springBack()
                        }
                    }
                } else {
                    Modifier.nestedScroll(connection)
                }
            )
    ) { content() }
}
