package com.einsli.photoroulette.ui

// 宫格右侧可拖动滚动条（回收站宫格专用，见 GridScrollBar 文档）。
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 宫格右侧的可拖动滚动条（设计图：回收站滚动条.png，MIUI 相册同款）：15×46dp 深灰
 *  (#4C4C4C) 胶囊贴右缘（右边距 13dp），内部上下两个 8×4dp 白色小三角。不滚动时隐藏，
 *  滚动/拖动时出现，停止约 2 秒后淡出。药丸热区比视觉大（横向左扩 12dp、纵向上下各放宽
 *  24dp），抓住上下拖即按比例直滚宫格（目标位置含行内偏移，全程连续无逐行跳动），拖动中
 *  不淡出；仅回收站宫格使用（回忆时光机不显示滚动条）。
 *
 *  手势层随可见性挂载：淡出后（shown=false 且未在拖动）或 [interactive]=false（选择模式）
 *  时不挂 pointerInput——满高透明条只要挂着手势层，命中测试就命中最上层的它，被盖住的
 *  宫格收不到触碰（处理器里放行不消费也无效），右列照片/选择圈就会点不了。 */
@Composable
internal fun GridScrollBar(gridState: LazyGridState, totalItems: Int, rowHeightPx: Int, interactive: Boolean = true, modifier: Modifier = Modifier) {
    if (totalItems < 24) return
    val scope = rememberCoroutineScope()
    // 滚动或拖动即出现；两者都停止 2 秒后淡出（淡出期间新滚动立即重现）。
    var shown by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress, dragging) {
        if (gridState.isScrollInProgress || dragging) {
            shown = true
        } else {
            delay(2000)
            shown = false
        }
    }
    val thumbAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(250),
        label = "scrollBarAlpha",
    )
    var areaHeightPx by remember { mutableIntStateOf(0) }
    // 拖动中的药丸位置（px，相对轨道顶）：拖动开始时从当前比例快照，之后纯跟手——不能被
    // layoutInfo 反写（拖动驱动宫格、宫格又改比例，互相追会抖）。
    var dragOffsetPx by remember { mutableStateOf(0f) }
    val dragJob = remember { mutableStateOf<Job?>(null) }
    // 每帧读取滚动位置只会重组这个小工具，宫格本身不受影响。
    val info = gridState.layoutInfo
    val total = info.totalItemsCount.coerceAtLeast(1)
    val visible = info.visibleItemsInfo
    // 行起点必须与下面的 firstVisibleItemScrollOffset 同源：两者都取滚动位置状态
    // （LazyGridState 的 firstVisibleItemIndex / firstVisibleItemScrollOffset 属于同一份
    // scrollPosition，换行时索引推进与偏移回绕同帧原子完成，分子全程连续）。不能拿
    // visibleItemsInfo（上一帧 measure 的布局，滚动时恒慢一帧）的 first 去拼当前 offset：
    // 行交接那一帧状态已把锚点推进到新行、偏移已回绕，而布局仍是旧行，分子会凭空少一整
    // 行高 → 药丸每滚过一行就下跳一截、下一帧布局追上又弹回，即「每滑动一格抖一下」。
    val first = gridState.firstVisibleItemIndex
    val span = ((visible.lastOrNull()?.index ?: first) - first + 1).coerceAtLeast(1)
    val denom = (total - span).coerceAtLeast(1)
    // 行空间连续比例 = 当前滚动像素 / 可滚动行程（denom 格 ÷ 每行 4 格 × 行高）。
    // 首行已滚过的像素（firstVisibleItemScrollOffset）必须计入，否则药丸在一个行高内
    // 只能阶梯跳；与拖拽映射共用同一公式，松手交还时两边一致、药丸不跳。
    val maxScrollPx = denom * rowHeightPx / 4f
    val frac = (((first / 4) * rowHeightPx + gridState.firstVisibleItemScrollOffset) / maxScrollPx)
        .coerceIn(0f, 1f)
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { 46.dp.toPx() }
    val travelPx = (areaHeightPx - thumbHeightPx).coerceAtLeast(0f)
    Box(
        modifier
            .fillMaxHeight()
            .width(44.dp)
            .onSizeChanged { areaHeightPx = it.height }
            // 热区必须「整层存在或整层不存在」：只要这条满高透明条上挂着 pointerInput，
            // 命中测试就命中最上层的它，被盖住的宫格整条收不到触碰——在处理器里对未命中
            // 的 down「放行不消费」救不了下层（真机/模拟器实测：淡出的隐形条把右缘照片
            // 点选和选择模式右列圆圈的点击全部吞掉，即「最右列点不了」的根因）。所以滚动
            // 条不可见（淡出且未在拖动）时不挂手势层；interactive=false（选择模式）整条
            // 只做视觉，右缘完全让给每格右下角的圆形选择圈。
            .then(
                if (interactive && (shown || dragging)) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 命中区按下瞬间现场计算（pointerInput 不随重组重启，绝不能闭包组合期的
                            // 派生值——全部从 gridState/areaHeightPx 现读）。
                            val pillH = 46.dp.toPx()
                            val travel = (areaHeightPx - pillH).coerceAtLeast(0f)
                            val li = gridState.layoutInfo
                            val vis = li.visibleItemsInfo
                            // 与显示路径同一约定：行起点取滚动位置状态（与下面现读的
                            // firstVisibleItemScrollOffset 同源、同帧一致），不取布局的
                            // visibleItemsInfo.first()（滚动中恒慢一帧，行交接会配错行）。
                            val f0 = gridState.firstVisibleItemIndex
                            val l0 = vis.lastOrNull()?.index ?: f0
                            // den/maxScrollPx 在 down 时快照、整个手势内复用。分母绝不能在 move
                            // 里随「滚动结果」重读：新行从底边进入 → 可见数变 → 目标被回拉 →
                            // 行又被推出 → 下一事件再进入，形成进/出振荡（慢拖抖动的放大器）。
                            val den = (li.totalItemsCount - (l0 - f0 + 1)).coerceAtLeast(1)
                            val maxScrollPx = den * rowHeightPx / 4f
                            // 药丸起点与宫格共用同一连续公式（含首行偏移）：抓起瞬间映射目标
                            // 恰等于当前位置，内容不跳。
                            val pillTop = (((f0 / 4) * rowHeightPx + gridState.firstVisibleItemScrollOffset) / maxScrollPx)
                                .coerceIn(0f, 1f) * travel
                            val zoneLeft = size.width - (13.dp + 15.dp + 12.dp).toPx()
                            val zoneTop = pillTop - 24.dp.toPx()
                            val zoneBottom = pillTop + pillH + 24.dp.toPx()
                            if (down.position.x < zoneLeft || down.position.y < zoneTop || down.position.y > zoneBottom) {
                                return@awaitEachGesture
                            }
                            down.consume()
                            dragging = true
                            dragOffsetPx = pillTop
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUp()) {
                                        change.consume()
                                        break
                                    }
                                    val dy = change.positionChange().y
                                    if (dy != 0f) {
                                        val t = (areaHeightPx - pillH).coerceAtLeast(0f)
                                        val newOff = (dragOffsetPx + dy).coerceIn(0f, t)
                                        if (newOff != dragOffsetPx) {
                                            dragOffsetPx = newOff
                                            // 绝对映射：轨道比例 → 滚动像素 → 行坐标（含行内偏移）。
                                            // 4 列宫格连续 4 格同属一行，必须先把格坐标折成行坐标：
                                            // 若拿「格子小数 × 行高」当行内偏移，格子序号每越过整数，
                                            // 落点都会从「行顶+近一行高」瞬回「行顶」，内容呈锯齿弹跳
                                            // （慢拖时约每 1px 手指弹一次，肉眼可见）。
                                            val ratio = if (t > 0f) newOff / t else 0f
                                            val targetPx = ratio * maxScrollPx
                                            val rowF = targetPx / rowHeightPx
                                            val row = rowF.toInt()
                                            val inRow = ((rowF - row) * rowHeightPx).roundToInt()
                                            dragJob.value?.cancel()
                                            dragJob.value = scope.launch {
                                                // 现读 totalItemsCount 兜底：拖动中列表若被清减，
                                                // scrollToItem 的 index 也绝不越界。
                                                val last = gridState.layoutInfo.totalItemsCount - 1
                                                gridState.scrollToItem((row * 4).coerceAtMost(last), inRow)
                                            }
                                        }
                                        change.consume()
                                    }
                                }
                            } finally {
                                // 手势被取消（父级截获/多点冲突）也要复位，否则药丸永远不再淡出。
                                dragging = false
                            }
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            Modifier
                .offset { IntOffset(0, (if (dragging) dragOffsetPx else frac * travelPx).roundToInt()) }
                .alpha(thumbAlpha)
                .padding(end = 13.dp)
                .size(15.dp, 46.dp)
                .background(Color(0xFF4C4C4C), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScrollBarArrow(pointUp = true)
                ScrollBarArrow(pointUp = false)
            }
        }
    }
}

/** 滚动条药丸里的白色小三角（设计图：8×4dp、约 80% 白、上下两个、间距 8dp）。 */
@Composable
private fun ScrollBarArrow(pointUp: Boolean) {
    Canvas(Modifier.size(8.dp, 4.dp)) {
        val w = size.width
        val h = size.height
        val path = Path()
        if (pointUp) {
            path.moveTo(w / 2f, 0f)
            path.lineTo(w, h)
            path.lineTo(0f, h)
        } else {
            path.moveTo(0f, 0f)
            path.lineTo(w, 0f)
            path.lineTo(w / 2f, h)
        }
        path.close()
        drawPath(path, Color.White.copy(alpha = 0.8f))
    }
}
