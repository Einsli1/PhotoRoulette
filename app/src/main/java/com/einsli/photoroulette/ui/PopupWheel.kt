package com.einsli.photoroulette.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 悬浮卡片弹层 + 吸附滚轮,设置页(数量/每日提醒)与统计页(历史整理月历)共用。
 *  原先私有在 App.kt;统计页的弹层锚点不是药丸(是周区间文本),故拆出
 *  [PopupState] + [PopupCard] 供自定义触发器复用。 */

/** 弹层开关状态 + 两段式展开/收回动画(收起时先播动画,结束后才真正移除)。 */
@Stable
internal class PopupState(private val anim: Animatable<Float, AnimationVector1D>) {
    var expanded by mutableStateOf(false)
        private set
    var closing by mutableStateOf(false)
        private set

    /** 是否应组合弹层内容(展开中或收起动画中)。 */
    val visible: Boolean get() = expanded || closing
    val progress: Float get() = anim.value

    fun open() {
        if (!expanded) {
            closing = false
            expanded = true
        }
    }

    fun close() {
        if (!closing) closing = true
    }

    internal suspend fun animateOpen() = anim.animateTo(1f, tween(200, easing = FastOutSlowInEasing))

    internal suspend fun animateClose() {
        anim.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
        expanded = false
        closing = false
    }
}

/** 弹层状态:开关语义与动画与原 SettingPopupPicker 内部实现完全一致。 */
@Composable
internal fun rememberPopupState(): PopupState {
    val anim = remember { Animatable(0f) }
    val state = remember(anim) { PopupState(anim) }
    LaunchedEffect(state.expanded, state.closing) {
        when {
            state.expanded && !state.closing -> state.animateOpen()
            state.closing -> state.animateClose()
        }
    }
    return state
}

/** 悬浮卡片弹层本体:锚点右对齐、下方展开(无空间翻转),从锚点右上角缩放/淡入。
 *  触发器由调用方自定(药丸、文本、任意组合),[state.visible] 时才组合内容。 */
@Composable
internal fun PopupCard(
    state: PopupState,
    onDismissRequest: () -> Unit = { state.close() },
    cardWidth: Dp = 248.dp,
    title: String? = null,
    showConfirm: Boolean = false,
    onConfirm: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dc = designColors()
    val gapPx = with(LocalDensity.current) { 8.dp.toPx() }.roundToInt()
    val marginPx = with(LocalDensity.current) { 12.dp.toPx() }.roundToInt()
    val popupPosition = remember(gapPx, marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = (anchorBounds.right - popupContentSize.width)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                var y = anchorBounds.bottom + gapPx
                if (y + popupContentSize.height > windowSize.height - marginPx) {
                    y = anchorBounds.top - popupContentSize.height - gapPx
                }
                y = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        }
    }
    if (state.visible) {
        Popup(
            popupPositionProvider = popupPosition,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true),
        ) {
            Box(
                Modifier
                    .padding(4.dp)
                    .graphicsLayer {
                        // 从锚点方向（右上角）缩放展开/收回，配合透明度。
                        val p = state.progress
                        alpha = p
                        scaleX = 0.85f + 0.15f * p
                        scaleY = 0.85f + 0.15f * p
                        transformOrigin = TransformOrigin(1f, 0f)
                    },
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = dc.white,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        Modifier
                            .then(if (cardWidth > 0.dp) Modifier.width(cardWidth) else Modifier.widthIn(min = 88.dp))
                            .padding(start = 8.dp, end = 8.dp, bottom = if (showConfirm) 14.dp else 6.dp)
                    ) {
                        if (title == null) {
                            Spacer(Modifier.height(4.dp))
                        } else {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = dc.labelGray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        content()
                        if (showConfirm) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    onConfirm?.invoke()
                                    state.close()
                                },
                                Modifier.fillMaxWidth().height(40.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = dc.accent, contentColor = Color.White),
                            ) { Text("完成", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }
}

/** 悬浮卡片式单选下拉：药丸触发 + [PopupCard],点选项即确认并收回。 */
@Composable
internal fun SettingPopupPicker(
    label: String,
    title: String? = null,
    cardWidth: Dp = 248.dp,
    showConfirm: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val dc = designColors()
    val state = rememberPopupState()
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (state.expanded && !state.closing) dc.accent.copy(alpha = 0.14f) else dc.white)
                .clickable {
                    if (state.expanded && !state.closing) state.close() else state.open()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.accentText)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, null, tint = dc.labelGray, modifier = Modifier.size(18.dp))
        }
        PopupCard(
            state = state,
            cardWidth = cardWidth,
            title = title,
            showConfirm = showConfirm,
            onConfirm = onConfirm,
        ) {
            content { state.close() }
        }
    }
}

private val WheelItemHeight = 34.dp

/** Custom snap-scrolling number wheel: the centered row is the selection — big bold accent
 *  value with the unit beside it, neighbours in gray, a soft accent band behind the center and
 *  gradient fades at the edges. Tapping a neighbour animates it to the center. */
@Composable
internal fun StyledNumberWheel(
    values: List<Int>,
    selected: Int,
    unit: String,
    format: (Int) -> String = { it.toString() },
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dc = designColors()
    val scope = rememberCoroutineScope()
    val itemPx = with(LocalDensity.current) { WheelItemHeight.toPx() }
    val initialIdx = values.indexOf(selected).coerceIn(0, values.lastIndex.coerceAtLeast(0))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIdx)
    var centerIdx by remember { mutableIntStateOf(initialIdx) }
    val latestSelected by rememberUpdatedState(selected)
    val latestOnSelected by rememberUpdatedState(onSelected)
    // The centered item = first visible index + half-viewport offset rounding (contentPadding
    // is 2×item height and the viewport is 5×item height, so offset 0 puts item[first] centered).
    LaunchedEffect(state, values) {
        snapshotFlow {
            (state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset / itemPx >= 0.5f) 1 else 0)
                .coerceIn(0, values.lastIndex)
        }
            .distinctUntilChanged()
            .collect { idx ->
                centerIdx = idx
                values.getOrNull(idx)?.let { if (it != latestSelected) latestOnSelected(it) }
            }
    }
    Box(modifier.fillMaxWidth().height(WheelItemHeight * 5)) {
        // Center highlight band (behind the values).
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(WheelItemHeight)
                .background(dc.accent.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
        )
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
            contentPadding = PaddingValues(vertical = WheelItemHeight * 2),
        ) {
            itemsIndexed(values) { idx, v ->
                val isCenter = idx == centerIdx
                Row(
                    Modifier
                        .height(WheelItemHeight)
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            scope.launch { state.animateScrollToItem(idx) }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        format(v),
                        fontSize = if (isCenter) 22.sp else 16.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCenter) dc.accentText else dc.labelGray,
                    )
                    // Fixed-width unit slot keeps every row's number perfectly centered; the
                    // unit is only drawn beside the current value (no slot when there is none).
                    Box(Modifier.width(if (unit.isEmpty()) 0.dp else 26.dp), contentAlignment = Alignment.CenterStart) {
                        if (isCenter) Text(unit, fontSize = 12.sp, color = dc.slate)
                    }
                }
            }
        }
        // Edge fades into the card background so the list dissolves instead of clipping.
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(WheelItemHeight * 1.2f).background(Brush.verticalGradient(listOf(dc.white, Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(WheelItemHeight * 1.2f).background(Brush.verticalGradient(listOf(Color.Transparent, dc.white))))
    }
}
