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
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
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
import androidx.compose.ui.unit.Dp
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
 *  药丸位置 = 已滚像素 ÷ 可滚行程，两者都按像素连续取（见 [remainingScrollPx]），所以
 *  滚动中不会整行跳变。宫格没什么可滚时（张数过少，或实测行程不足一行）整条不显示。
 *
 *  [trackTopInset] = 轨道上端内缩（调用方传页面悬浮头部的实测高度）。头部是一条「整层
 *  吞触碰」的满宽层（见 MediaGridScreen 的头部 Box），轨道若不内缩，药丸滑到最顶上时整颗
 *  压在头部渐变最深处、又落在吸收层里——看得见、抓不到。内缩到头部下沿后，药丸的最上位
 *  正好落在第一排宫格上，也永远不在头部的地盘里。
 *
 *  手势层随可见性挂载：淡出后（shown=false 且未在拖动）或 [interactive]=false（选择模式）
 *  时不挂 pointerInput——满高透明条只要挂着手势层，命中测试就命中最上层的它，被盖住的
 *  宫格收不到触碰（处理器里放行不消费也无效），右列照片/选择圈就会点不了。 */
@Composable
internal fun GridScrollBar(
    gridState: LazyGridState,
    totalItems: Int,
    rowHeightPx: Int,
    interactive: Boolean = true,
    trackTopInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
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
    // 行起点必须与下面的 firstVisibleItemScrollOffset 同源：两者都取滚动位置状态
    // （LazyGridState 的 firstVisibleItemIndex / firstVisibleItemScrollOffset 属于同一份
    // scrollPosition，换行时索引推进与偏移回绕同帧原子完成，分子全程连续）。不能拿
    // visibleItemsInfo（上一帧 measure 的布局，滚动时恒慢一帧）的 first 去拼当前 offset：
    // 行交接那一帧状态已把锚点推进到新行、偏移已回绕，而布局仍是旧行，分子会凭空少一整
    // 行高 → 药丸每滚过一行就下跳一截、下一帧布局追上又弹回，即「每滑动一格抖一下」。
    val first = gridState.firstVisibleItemIndex
    val scrolledPx = ((first / 4) * rowHeightPx + gridState.firstVisibleItemScrollOffset).toFloat()
    // 行空间连续比例 = 已滚像素 / 可滚动行程。行程 = 已滚 + 视口下方剩余（见
    // [remainingScrollPx]）。分母以前写成「总张数 − 可见张数」：可见张数是整数、且来自
    // 上一帧 measure 的布局，一行照片进/出视口就整整 4 张地跳一次，分母跟着跳 → 药丸
    // 换行时抖一下；列表只比一屏多出不到一行时它还会塌到 1，药丸几下就把整条轨道走完。
    // 换成「已滚 + 剩余」后：两项同源，滚动中恒定不变（只随行高估算的固定误差缓慢漂移，
    // 不会有台阶）；而且它与「首行起点算不算含顶部 contentPadding」这个约定无关——那个
    // 常量会同时进入分子和分母，被整体抵消。
    // 首行已滚过的像素（firstVisibleItemScrollOffset）必须计入，否则药丸在一个行高内
    // 只能阶梯跳；与拖拽映射共用同一公式，松手交还时两边一致、药丸不跳。
    val maxScrollPx = (scrolledPx + remainingScrollPx(info, rowHeightPx)).coerceAtLeast(1f)
    // 行程不到一行就不显示滚动条：药丸是固定 46dp 的指示器，行程比一行还短时它会在几十
    // 像素的滑动里把整条轨道走完，看起来就是乱跳。上面那个「张数 < 24」只是这件事的廉价
    // 近似——它与行高/屏幕高度无关，大屏上 24 张可能连一屏都填不满，小屏上又可能已经能滚
    // 好几行；按实测行程判才是准的（首屏还没 measure 时行程为 0，本来也不可见）。
    if (maxScrollPx < rowHeightPx) return
    val frac = (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { 46.dp.toPx() }
    val travelPx = (areaHeightPx - thumbHeightPx).coerceAtLeast(0f)
    Box(
        modifier
            .fillMaxHeight()
            .width(44.dp)
            // 轨道上端内缩到悬浮头部下沿：内缩是在 fillMaxHeight 之内做的，所以
            // onSizeChanged / pointerInput 拿到的 size 就是缩后的轨道，药丸偏移以它顶为 0，
            // 底层坐标不用改（travelPx 也自动变成缩后的行程）。
            .padding(top = trackTopInset)
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
                            // 与显示路径同一约定：行起点取滚动位置状态（与下面现读的
                            // firstVisibleItemScrollOffset 同源、同帧一致），不取布局的
                            // visibleItemsInfo.first()（滚动中恒慢一帧，行交接会配错行）。
                            val f0 = gridState.firstVisibleItemIndex
                            val scrolledPx = ((f0 / 4) * rowHeightPx + gridState.firstVisibleItemScrollOffset).toFloat()
                            // 行程在 down 时快照、整个手势内复用。它 = 已滚 + 剩余（见
                            // [remainingScrollPx]），滚动中恒定不变，所以就算在 move 里重读
                            // 也不会振荡；旧写法取「总张数 − 可见张数」才有那个放大器：新行
                            // 从底边进入 → 可见数变 → 目标被回拉 → 行又被推出 → 再进入。
                            val maxScrollPx = (scrolledPx + remainingScrollPx(li, rowHeightPx)).coerceAtLeast(1f)
                            // 药丸起点与宫格共用同一连续公式（含首行偏移）：抓起瞬间映射目标
                            // 恰等于当前位置，内容不跳。
                            val pillTop = (scrolledPx / maxScrollPx).coerceIn(0f, 1f) * travel
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

/** 视口下方还剩多少像素可滚（0 = 已经到底）：最后一张可见照片的底边 + 内容下留白 距视口
 *  底边的距离。与 [MediaGridScreen] 的 pullAtBottom 是同一把尺（那边用它判「还能不能
 *  继续滚」），两边必须一致，否则「到底了没」和药丸位置会各说各话。
 *
 *  这是几何量而不是「可见张数」：照片进出视口时它按像素连续变化，不会整行跳变；也只跟
 *  最后一行在内容里的位置有关，与列表总张数无关——列表被删/恢复时同样不跳。 */
private fun remainingScrollPx(info: LazyGridLayoutInfo, rowHeightPx: Int): Float {
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    // 4 = 列数（与文件内其他 4 处一致；ARCHITECTURE 的 M-4 记着迟早要提成参数）。
    return ((last.index / 4) * rowHeightPx + last.size.height + info.afterContentPadding -
        info.viewportEndOffset).toFloat().coerceAtLeast(0f)
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
