package com.einsli.photoroulette.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.einsli.photoroulette.AppUiState
import com.einsli.photoroulette.WeekStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private fun formatBytes(b: Long): String {
    val gb = b / 1_073_741_824.0
    val mb = b / 1_048_576.0
    val kb = b / 1024.0
    return when {
        gb >= 1 -> String.format("%.1fGB", gb)
        mb >= 1 -> String.format("%.0fMB", mb)
        kb >= 1 -> String.format("%.0fKB", kb)
        else -> "0B"
    }
}

private fun formatCount(n: Int): String = String.format("%,d", n)

private fun weekdayLabel(d: DayOfWeek): String = when (d) {
    DayOfWeek.MONDAY -> "周一"; DayOfWeek.TUESDAY -> "周二"; DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"; DayOfWeek.FRIDAY -> "周五"; DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

/** 周区间标题:8月25日 - 8月31日(跨月也简短) */
private fun weekRangeHeader(monday: LocalDate): String {
    val sunday = monday.plusDays(6)
    return "${monday.monthValue}月${monday.dayOfMonth}日 - ${sunday.monthValue}月${sunday.dayOfMonth}日"
}

/** 某周统计尚未加载出来时的全 0 占位(既有约定)。 */
private val EMPTY_WEEK_STATS = WeekStats(List(7) { 0 }, 0, 0, 0L)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(
    state: AppUiState,
    historyWeek: LocalDate? = null,
    onSelectHistoryWeek: (LocalDate?) -> Unit = {},
    weekStatsOf: (LocalDate) -> Flow<WeekStats> = { flowOf(EMPTY_WEEK_STATS) },
    earliestMonth: suspend () -> YearMonth = { YearMonth.now() },
    monthDayCounts: suspend (YearMonth) -> Map<LocalDate, Int> = { emptyMap() },
) {
    val dc = designColors()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stats = state.stats
    val week = state.week
    val cumulative = state.cumulative
    val processed = state.processed
    val total = state.total
    val ratio = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else 0f
    val keepPct = if (processed > 0) (stats.kept * 100.0 / processed).roundToInt() else 0
    val daysLeft = if (state.settings.dailyCount > 0)
        ((total - processed).toDouble() / state.settings.dailyCount).let { kotlin.math.ceil(it).toInt() } else 0
    val thisMonday = remember { LocalDate.now().with(DayOfWeek.MONDAY) }
    val latestCommit by rememberUpdatedState(onSelectHistoryWeek)

    // ── 周卡片翻页范围:页 0 = 最早记录所在周的周一(取 earliestMonth 月初那一周),
    //    最后一页 = 本周。范围异步查出,查出前先按当前查看周静态渲染。 ──
    var firstMonday by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(Unit) { firstMonday = earliestMonth().atDay(1).with(DayOfWeek.MONDAY) }
    val weekCount = firstMonday?.let {
        (ChronoUnit.WEEKS.between(it, thisMonday) + 1).toInt().coerceAtLeast(1)
    }

    // Spring pull in both directions; engages only at the scroll limits (nested scroll also
    // swallows the platform stretch overscroll).
    SpringPullBox(
        modifier = Modifier.fillMaxSize(),
        pullAtTop = { scroll.value.toFloat() },
        pullAtBottom = { (scroll.maxValue - scroll.value).coerceAtLeast(0).toFloat() },
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .background(dc.pageBg)
            .verticalScroll(scroll, flingBehavior = rememberGentleFlingBehavior())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("统计", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = dc.ink)
        Spacer(Modifier.height(14.dp))

        // ── 本周整理 / 历史整理(同一套周视图:7 天趋势 + 周汇总),左右滑动连续翻周 ──
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = dc.card),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            // 月历弹层打开期间禁用卡片左右滑动(弹层收起后恢复)。
            var popupBusy by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                val fm = firstMonday
                if (fm == null || weekCount == null) {
                    // 翻页范围还没查出来:先按当前查看周静态渲染,与 pager 对应页内容一致,不闪变。
                    WeekCardContent(
                        monday = historyWeek ?: thisMonday,
                        thisMonday = thisMonday,
                        currentWeek = week,
                        atCurrentWeek = (historyWeek ?: thisMonday) == thisMonday,
                        canPrevWeek = false,
                        onPrevWeek = {}, onNextWeek = {}, onPickWeek = {},
                        onPopupExpandedChange = {},
                        weekStatsOf = weekStatsOf,
                        earliestMonth = earliestMonth,
                        monthDayCounts = monthDayCounts,
                    )
                } else {
                    val pagerState = rememberPagerState(initialPage = weekCount - 1) { weekCount }
                    // 切 Tab 返回时 pager 状态重建:恢复上次查看的周(只做一次)。
                    var restored by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(pagerState) {
                        if (!restored) {
                            restored = true
                            historyWeek?.let { hw ->
                                val idx = ChronoUnit.WEEKS.between(fm, hw).toInt().coerceIn(0, weekCount - 1)
                                if (idx != pagerState.currentPage) pagerState.scrollToPage(idx)
                            }
                        }
                    }
                    // 翻页落定后把当前周写回选中状态(目标 = 本周时清空历史选中);
                    // pager 位置是唯一事实源,外部入口只负责驱动 pager,不反向监听动画,避免坑 26。
                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.settledPage }.collect { page ->
                            val monday = fm.plusWeeks(page.toLong())
                            latestCommit(if (monday == thisMonday) null else monday)
                        }
                    }
                    // ── 周卡片 ↔ 外层 Tab pager 的横向隔离 ──
                    // 内层周 pager 滑到尽头(本周往左/最早一周往右)后,剩余的横向位移与惯性
                    // 速度全部在这里消费掉,绝不交给外层 Tab pager(否则继续滑会切 Tab 页);
                    // 纵向分量一律放行(返回 Offset/Velocity 的 y 恒为 0),页面垂直滚动与
                    // SpringPull 手感完全不受影响。
                    // 只挂 post 通路:不碰 onPreScroll/onPreFling,周 pager 自己的拖拽/吸附
                    // 照常先消费、到边界才轮到本 connection,pager 自身手势行为零改变;
                    // 且本 connection 只收得到周 pager 子树向上派发的 delta(垂直滚动 Column、
                    // SpringPullBox 都在它上游),宫格卡片之外的滚动/翻页不经过它。
                    val weekPagerEdgeIsolation = remember {
                        object : NestedScrollConnection {
                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: NestedScrollSource,
                            ): Offset = Offset(available.x, 0f)

                            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                                Velocity(available.x, 0f)
                        }
                    }
                    // 关掉 pager 边缘的 stretch overscroll:到边界就停,内容不撑出卡片圆角。
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxWidth().nestedScroll(weekPagerEdgeIsolation),
                            pageSpacing = 14.dp,
                            userScrollEnabled = !popupBusy,
                        ) { page ->
                            val monday = fm.plusWeeks(page.toLong())
                            WeekCardContent(
                                monday = monday,
                                thisMonday = thisMonday,
                                currentWeek = week,
                                atCurrentWeek = monday == thisMonday,
                                canPrevWeek = page > 0,
                                onPrevWeek = {
                                    scope.launch { pagerState.animateScrollToPage((page - 1).coerceAtLeast(0)) }
                                },
                                onNextWeek = {
                                    if (monday < thisMonday) {
                                        scope.launch { pagerState.animateScrollToPage(page + 1) }
                                    }
                                },
                                onPickWeek = { target ->
                                    val idx = ChronoUnit.WEEKS.between(fm, target).toInt()
                                        .coerceIn(0, weekCount - 1)
                                    scope.launch { pagerState.animateScrollToPage(idx) }
                                },
                                onPopupExpandedChange = { popupBusy = it },
                                weekStatsOf = weekStatsOf,
                                earliestMonth = earliestMonth,
                                monthDayCounts = monthDayCounts,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 整理进度 ──
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = dc.card),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("整理进度", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = dc.ink)
                    Spacer(Modifier.weight(1f))
                    Text("已整理 $processed / $total 张", fontSize = 13.sp, color = dc.accentText)
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(dc.track)
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(ratio)
                            .clip(CircleShape)
                            .background(dc.accent)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("按每天 ${state.settings.dailyCount} 张，预计还需 $daysLeft 天完成", fontSize = 13.sp, color = dc.slate)
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 长期统计 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigStat(
                Modifier.weight(1f),
                badge = dc.badgeStreak,
                icon = { Icon(Icons.Default.LocalFireDepartment, null, tint = dc.badgeStreakIcon, modifier = Modifier.size(22.dp)) },
                value = "${stats.streak}天",
                label = "连续整理"
            )
            BigStat(
                Modifier.weight(1f),
                badge = dc.badgeSpace,
                icon = { Icon(Icons.Default.Delete, null, tint = dc.badgeSpaceIcon, modifier = Modifier.size(22.dp)) },
                value = formatBytes(stats.trashBytes),
                label = "释放空间"
            )
            BigStat(
                Modifier.weight(1f),
                badge = dc.badgeKeep,
                icon = { Icon(Icons.Default.Favorite, null, tint = dc.badgeKeepIcon, modifier = Modifier.size(22.dp)) },
                value = "$keepPct%",
                label = "保留的照片"
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── 累计统计（累计整理 / 累计删除 / 累计保留）──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigStat(
                Modifier.weight(1f),
                badge = dc.badgeOrganized,
                icon = { Icon(Icons.Default.PhotoLibrary, null, tint = dc.badgeOrganizedIcon, modifier = Modifier.size(22.dp)) },
                value = "${formatCount(cumulative.organizedTotal)} 张",
                label = "累计整理"
            )
            BigStat(
                Modifier.weight(1f),
                badge = dc.badgeDeleted,
                icon = { Icon(Icons.Default.Clear, null, tint = dc.badgeDeletedIcon, modifier = Modifier.size(22.dp)) },
                value = "${formatCount(cumulative.deletedTotal)} 张",
                label = "累计删除"
            )
            BigStat(
                Modifier.weight(1f),
                badge = dc.badgeKeptTotal,
                icon = { Icon(Icons.Default.VerifiedUser, null, tint = dc.badgeKeptTotalIcon, modifier = Modifier.size(22.dp)) },
                value = "${formatCount(cumulative.keptTotal)} 张",
                label = "累计保留"
            )
        }

        Spacer(Modifier.height(14.dp))
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = dc.card),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("小贴士", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = dc.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "每天抽出几分钟整理少量照片，坚持下来就是一笔宝贵的回忆财富。左滑移入回收站，右滑保留。",
                    fontSize = 13.sp,
                    color = dc.slate,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(8.dp))
                Text("保留的照片不会再次出现在轮盘中，删除前系统会二次确认。", fontSize = 13.sp, color = dc.labelGray, lineHeight = 19.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

/** 周卡片单页内容:周头部 + 7 天趋势图 + 周汇总四格。pager 每个 page 一份。
 *  本周页直接用实时的 state.week;历史页按需订阅该周的统计流(加载瞬间全 0 占位)。 */
@Composable
private fun WeekCardContent(
    monday: LocalDate,
    thisMonday: LocalDate,
    currentWeek: WeekStats,
    atCurrentWeek: Boolean,
    canPrevWeek: Boolean,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onPickWeek: (LocalDate) -> Unit,
    onPopupExpandedChange: (Boolean) -> Unit,
    weekStatsOf: (LocalDate) -> Flow<WeekStats>,
    earliestMonth: suspend () -> YearMonth,
    monthDayCounts: suspend (YearMonth) -> Map<LocalDate, Int>,
) {
    val dc = designColors()
    val isCurrentWeek = monday == thisMonday
    val week = if (isCurrentWeek) currentWeek
    else remember(monday) { weekStatsOf(monday) }.collectAsState(EMPTY_WEEK_STATS).value

    Column(Modifier.fillMaxWidth()) {
        HistoryWeekHeader(
            isHistory = !isCurrentWeek,
            monday = monday,
            atCurrentWeek = atCurrentWeek,
            canPrev = canPrevWeek,
            onPrevWeek = onPrevWeek,
            onNextWeek = onNextWeek,
            onPickWeek = onPickWeek,
            onPopupExpandedChange = onPopupExpandedChange,
            earliestMonth = earliestMonth,
            monthDayCounts = monthDayCounts,
        )
        Spacer(Modifier.height(12.dp))
        WeekTrendChart(week.days, monday)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeekMini(if (isCurrentWeek) "本周整理" else "整理", "${week.organized} 张", Modifier.weight(1f), dc)
            WeekMini("删除", "${week.deleted} 张", Modifier.weight(1f), dc)
            WeekMini("保留", "${week.kept} 张", Modifier.weight(1f), dc)
            WeekMini("释放", formatBytes(week.freedBytes), Modifier.weight(1f), dc)
        }
    }
}

/** Simple Material-3 style bar chart: the week starting [monday], Monday first, today
 *  highlighted (only when viewing the current week — a historical week has no "today").
 *  Days that have not arrived yet show an empty slot (label only). The plot area (bars) has
 *  a fixed height and the weekday labels sit on a fixed baseline, so tall bars never push
 *  the labels down. */
@Composable
private fun WeekTrendChart(days: List<Int>, monday: LocalDate) {
    val dc = designColors()
    val maxCount = (days.maxOrNull() ?: 0).coerceAtLeast(1)
    val today = LocalDate.now()
    val isCurrentWeek = today.with(DayOfWeek.MONDAY) == monday
    Column(Modifier.fillMaxWidth()) {
        // Fixed-height plot area: value labels + bars, bottom-aligned.
        Row(
            Modifier.fillMaxWidth().height(86.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            days.forEachIndexed { i, count ->
                val d = monday.plusDays(i.toLong())
                val isToday = isCurrentWeek && d == today
                val isFuture = d.isAfter(today)
                val barHeight = if (count > 0) {
                    (56.dp * (count.toFloat() / maxCount)).coerceAtLeast(3.dp)
                } else 0.dp
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.weight(1f))
                    if (!isFuture) {
                        Text("$count", fontSize = 9.sp, color = if (isToday) dc.accentText else dc.labelGray)
                    }
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (isToday) dc.accent else dc.accent.copy(alpha = 0.4f))
                    )
                }
            }
        }
        // Fixed baseline: weekday labels always on the same line below the bars.
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            days.forEachIndexed { i, _ ->
                val d = monday.plusDays(i.toLong())
                val isToday = isCurrentWeek && d == today
                Text(
                    weekdayLabel(d.dayOfWeek),
                    fontSize = 10.sp,
                    color = if (isToday) dc.accentText else dc.labelGray,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WeekMini(label: String, value: String, modifier: Modifier, dc: DesignColors) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = dc.ink, maxLines = 1)
        Spacer(Modifier.height(1.dp))
        Text(label, fontSize = 10.sp, color = dc.labelGray, maxLines = 1)
    }
}

/** 周切换组里的小箭头:紧凑的点击区,日期两侧间距紧、垂直居中。 */
@Composable
private fun SwipeArrow(
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit,
    enabled: Boolean = true,
) {
    val dc = designColors()
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        icon(if (enabled) dc.accentText else dc.labelGray)
    }
}

/** 统计页顶部历史入口一行:标题 + ‹ › 周切换箭头 + 可点击的周区间文本。
 *  点周区间文本弹出月历弹层,选中某天即查看它所在的一周。 */
@Composable
private fun HistoryWeekHeader(
    isHistory: Boolean,
    monday: LocalDate,
    atCurrentWeek: Boolean,
    canPrev: Boolean,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onPickWeek: (LocalDate) -> Unit,
    onPopupExpandedChange: (Boolean) -> Unit,
    earliestMonth: suspend () -> YearMonth,
    monthDayCounts: suspend (YearMonth) -> Map<LocalDate, Int>,
) {
    val dc = designColors()
    val popup = rememberPopupState()
    val today = LocalDate.now()
    // 月历草稿状态:打开时重置为当前查看周所在月;月份切换/数据加载只活在弹层期间。
    var calMonth by remember { mutableStateOf(YearMonth.from(monday)) }
    var calCounts by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    var minMonth by remember { mutableStateOf<YearMonth?>(null) }
    LaunchedEffect(popup.expanded) { if (popup.expanded) calMonth = YearMonth.from(monday) }
    LaunchedEffect(popup.expanded) { onPopupExpandedChange(popup.expanded) }
    LaunchedEffect(Unit) { minMonth = earliestMonth() }
    LaunchedEffect(calMonth) { calCounts = monthDayCounts(calMonth) }
    val canPrevMonth = minMonth == null || calMonth > minMonth
    val canNext = calMonth < YearMonth.now()

    Box {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                if (isHistory) "历史整理" else "本周整理",
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = dc.ink,
                modifier = Modifier.weight(1f)
            )
            // 紧凑的周切换组:‹ 日期 › 整体靠右,间距紧,日期在上箭头之间垂直居中。
            SwipeArrow(
                onClick = onPrevWeek,
                enabled = canPrev,
                icon = { tint -> Icon(Icons.Default.KeyboardArrowLeft, "上一周", tint = tint, modifier = Modifier.size(20.dp)) },
            )
            Text(
                weekRangeHeader(monday),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = dc.accentText, maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { popup.open() }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
            SwipeArrow(
                onClick = onNextWeek,
                enabled = !atCurrentWeek,
                icon = { tint -> Icon(Icons.Default.KeyboardArrowRight, "下一周", tint = tint, modifier = Modifier.size(20.dp)) },
            )
        }
        PopupCard(
            state = popup,
            cardWidth = 322.dp,
            title = "选择日期，查看所在一周",
        ) {
            MonthCalendar(
                month = calMonth,
                counts = calCounts,
                today = today,
                viewedMonday = monday,
                canPrev = canPrevMonth,
                canNext = canNext,
                onPrevMonth = { if (canPrevMonth) calMonth = calMonth.minusMonths(1) },
                onNextMonth = { if (canNext) calMonth = calMonth.plusMonths(1) },
                onPickDay = { d ->
                    popup.close()
                    onPickWeek(d.with(DayOfWeek.MONDAY))
                },
            )
        }
    }
}

/** 月历网格(周一起始):每个日期格子直接显示当天的整理量,缺勤日显示淡点。
 *  今天高亮为实心紫圆;当前查看的那一周的日期带淡紫底。 */
@Composable
private fun MonthCalendar(
    month: YearMonth,
    counts: Map<LocalDate, Int>,
    today: LocalDate,
    viewedMonday: LocalDate,
    canPrev: Boolean,
    canNext: Boolean,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onPickDay: (LocalDate) -> Unit,
) {
    val dc = designColors()
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value - 1 // Monday=1 → 开头空槽数
    val daysInMonth = month.lengthOfMonth()
    val rows = (leading + daysInMonth + 6) / 7
    Column(Modifier.fillMaxWidth()) {
        // 月份标题 + 上/下月切换
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevMonth, enabled = canPrev) {
                Icon(Icons.Default.KeyboardArrowLeft, "上个月", tint = dc.accentText, modifier = Modifier.size(22.dp))
            }
            Text(
                "${month.year}年${month.monthValue}月",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = dc.ink,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            IconButton(onClick = onNextMonth, enabled = canNext) {
                Icon(Icons.Default.KeyboardArrowRight, "下个月", tint = dc.accentText, modifier = Modifier.size(22.dp))
            }
        }
        // 星期表头:一 二 三 四 五 六 日
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            for (dow in 1..7) {
                Text(
                    weekdayLabel(DayOfWeek.of(dow)).takeLast(1),
                    fontSize = 10.sp, color = dc.labelGray,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 日期网格
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - leading + 1
                    val date = if (dayNum in 1..daysInMonth) month.atDay(dayNum) else null
                    Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            CalendarDayCell(
                                date = date,
                                count = counts[date] ?: 0,
                                isToday = date == today,
                                inViewedWeek = !date.isBefore(viewedMonday) && !date.isAfter(viewedMonday.plusDays(6)),
                                onClick = { onPickDay(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 月历单格:日期数字(今天=实心紫圆,当前查看周=淡紫底)+ 当天整理量(无则淡点)。 */
@Composable
private fun CalendarDayCell(
    date: LocalDate,
    count: Int,
    isToday: Boolean,
    inViewedWeek: Boolean,
    onClick: () -> Unit,
) {
    val dc = designColors()
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isToday -> dc.accent
                        inViewedWeek -> dc.accent.copy(alpha = 0.12f)
                        else -> Color.Transparent
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${date.dayOfMonth}",
                fontSize = 12.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isToday -> Color.White
                    inViewedWeek -> dc.accentText
                    else -> dc.ink
                }
            )
        }
        Text(
            if (count > 0) "$count" else "·",
            fontSize = 9.sp,
            color = if (count > 0) dc.accentText else dc.labelGray.copy(alpha = 0.6f),
            maxLines = 1
        )
    }
}

@Composable
private fun BigStat(
    modifier: Modifier,
    badge: Color,
    icon: @Composable () -> Unit,
    value: String,
    label: String,
) {
    val dc = designColors()
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(dc.card)
            .padding(vertical = 16.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(badge),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.height(8.dp))
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = dc.ink, maxLines = 1)
        Spacer(Modifier.height(1.dp))
        Text(label, fontSize = 12.sp, color = dc.labelGray, maxLines = 1)
    }
}
