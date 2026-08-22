package com.einsli.photoroulette.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.einsli.photoroulette.AppUiState
import java.time.DayOfWeek
import java.time.LocalDate
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

private fun weekdayLabel(d: DayOfWeek): String = when (d) {
    DayOfWeek.MONDAY -> "周一"; DayOfWeek.TUESDAY -> "周二"; DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"; DayOfWeek.FRIDAY -> "周五"; DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

@Composable
fun StatsScreen(state: AppUiState) {
    val dc = designColors()
    val stats = state.stats
    val week = state.week
    val processed = state.processed
    val total = state.total
    val ratio = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else 0f
    val keepPct = if (processed > 0) (stats.kept * 100.0 / processed).roundToInt() else 0
    val daysLeft = if (state.settings.dailyCount > 0)
        ((total - processed).toDouble() / state.settings.dailyCount).let { kotlin.math.ceil(it).toInt() } else 0

    Column(
        Modifier
            .fillMaxSize()
            .background(dc.pageBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("统计", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = dc.ink)
        Spacer(Modifier.height(14.dp))

        // ── 近 7 天整理趋势 ──
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = dc.card),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("本周整理", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = dc.ink)
                Spacer(Modifier.height(12.dp))
                WeekTrendChart(week.days)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WeekMini("本周整理", "${week.organized} 张", Modifier.weight(1f), dc)
                    WeekMini("删除", "${week.deleted} 张", Modifier.weight(1f), dc)
                    WeekMini("保留", "${week.kept} 张", Modifier.weight(1f), dc)
                    WeekMini("释放", formatBytes(week.freedBytes), Modifier.weight(1f), dc)
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

/** Simple Material-3 style bar chart: last 7 days, oldest first, today highlighted. */
@Composable
private fun WeekTrendChart(days: List<Int>) {
    val dc = designColors()
    val maxCount = (days.maxOrNull() ?: 0).coerceAtLeast(1)
    val today = LocalDate.now()
    Row(
        Modifier.fillMaxWidth().height(110.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        days.forEachIndexed { i, count ->
            val d = today.minusDays((6 - i).toLong())
            val isToday = i == days.size - 1
            val barHeight = if (count > 0) {
                (64.dp * (count.toFloat() / maxCount)).coerceAtLeast(4.dp)
            } else 0.dp
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))
                Text("$count", fontSize = 9.sp, color = if (isToday) dc.accentText else dc.labelGray)
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(if (isToday) dc.accent else dc.accent.copy(alpha = 0.4f))
                )
                Spacer(Modifier.height(5.dp))
                Text(weekdayLabel(d.dayOfWeek), fontSize = 10.sp, color = if (isToday) dc.accentText else dc.labelGray)
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
