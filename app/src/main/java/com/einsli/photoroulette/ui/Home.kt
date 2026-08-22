package com.einsli.photoroulette.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.einsli.photoroulette.AppUiState
import com.einsli.photoroulette.BuildConfig
import com.einsli.photoroulette.R
import com.einsli.photoroulette.data.PhotoEntity
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

/**
 * The redesigned home screen. Sized to fill the viewport without scrolling:
 * a weight spacer at the bottom absorbs whatever vertical space is left.
 */
@Composable
fun Home(state: AppUiState, onStart: () -> Unit, onScan: () -> Unit, onOpenMemory: () -> Unit) {
    val dc = designColors()
    Column(
        Modifier
            .fillMaxSize()
            .background(dc.pageBg)
            .padding(horizontal = 20.dp)
    ) {
        // Minimal top elastic space — the content hugs the top so the task card and its
        // photo preview get as much room as possible.
        Spacer(Modifier.weight(0.02f))
        Header()
        Spacer(Modifier.height(12.dp))
        TodayTaskCard(state, onStart, onScan)
        Spacer(Modifier.height(16.dp))
        ProgressSection(state)
        Spacer(Modifier.height(14.dp))
        StatsRow(state)
        Spacer(Modifier.height(16.dp))
        MemoryCard(state, onOpenMemory)
        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.weight(0.2f))
    }
}

@Composable
private fun Header() {
    val dc = designColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "PhotoRoulette",
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                color = dc.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "每天一点，整理美好回忆",
                fontSize = 12.sp,
                color = dc.slate
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "版本 ${BuildConfig.VERSION_NAME}",
                fontSize = 9.sp,
                color = dc.labelGray
            )
        }
        Spacer(Modifier.width(12.dp))
        // App icon: light theme uses icon.png, dark theme uses dark_icon.png. Shown full
        // inside a rounded-rectangle frame (no circular crop, no zoom-to-hide-margin).
        val iconRes = if (dc.isDark) R.drawable.dark_icon else R.drawable.icon
        Box(
            Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = "照片轮盘",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun TodayTaskCard(state: AppUiState, onStart: () -> Unit, onScan: () -> Unit) {
    val dc = designColors()
    val session = state.session
    val remaining = state.remaining
    val inProgress = session != null && remaining > 0
    val count = if (inProgress) remaining else state.settings.dailyCount
    val preview: PhotoEntity? = session?.queue?.firstOrNull()
    val gradient = if (dc.isDark) {
        Brush.verticalGradient(listOf(Color(0xFF27949B), Color(0xFF205F5E)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF589FFF), Color(0xFF448DF8)))
    }
    val onClick = if (state.total == 0 && !state.loading) onScan else onStart
    val label = when {
        state.total == 0 && !state.loading -> "扫描相册"
        inProgress -> "继续整理"
        else -> "开始整理"
    }

    Column(Modifier.fillMaxWidth()) {
        Text("今日任务", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = dc.ink)
        Spacer(Modifier.height(6.dp))
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = dc.card),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "$count",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = dc.accentText,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "张照片",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = dc.ink,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (state.loading) "正在加载照片队列…"
                            else if (state.total == 0) "相册里还没有照片"
                            else if (inProgress) "已整理 ${state.processed} 张，继续加油"
                            else "还没开始整理哦",
                            fontSize = 12.sp,
                            color = dc.slate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(86.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(dc.white)
                    ) {
                        if (preview != null) {
                            VideoAwareImage(
                                preview,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    null,
                                    tint = dc.placeholderIcon,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text("暂无照片", fontSize = 9.sp, color = dc.labelGray)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // The hero action lives inside the task card: a full-width gradient button
                // (light: blue gradient, dark: teal gradient).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(gradient)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressSection(state: AppUiState) {
    val dc = designColors()
    val processed = state.processed
    val total = state.total
    val ratio = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else 0f
    val daysLeft = if (state.settings.dailyCount > 0)
        ((total - processed).toDouble() / state.settings.dailyCount).let { kotlin.math.ceil(it).toInt() } else 0
    val fill = if (dc.isDark) {
        Brush.horizontalGradient(listOf(Color(0xFF2B8F8A), Color(0xFF236969)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF589FFF), Color(0xFF448DF8)))
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = dc.card),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("整理进度", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dc.ink)
                Spacer(Modifier.weight(1f))
                Text(
                    "已整理 $processed / $total 张",
                    fontSize = 12.sp,
                    color = dc.accentText,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(dc.track)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ratio)
                        .clip(CircleShape)
                        .background(fill)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "预计还需 $daysLeft 天完成",
                fontSize = 12.sp,
                color = dc.slate
            )
        }
    }
}

@Composable
private fun StatsRow(state: AppUiState) {
    val dc = designColors()
    val stats = state.stats
    val processed = state.processed
    val keepPct = if (processed > 0) (stats.kept * 100.0 / processed).roundToInt() else 0
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = dc.card),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatItem(
                modifier = Modifier.weight(1f),
                badge = dc.badgeStreak,
                icon = { Icon(Icons.Default.LocalFireDepartment, null, tint = dc.badgeStreakIcon, modifier = Modifier.size(18.dp)) },
                value = "${stats.streak}天",
                label = "连续整理"
            )
            StatItem(
                modifier = Modifier.weight(1f),
                badge = dc.badgeSpace,
                icon = { Icon(Icons.Default.Delete, null, tint = dc.badgeSpaceIcon, modifier = Modifier.size(18.dp)) },
                value = formatBytes(stats.trashBytes),
                label = "释放空间"
            )
            StatItem(
                modifier = Modifier.weight(1f),
                badge = dc.badgeKeep,
                icon = { Icon(Icons.Default.Favorite, null, tint = dc.badgeKeepIcon, modifier = Modifier.size(18.dp)) },
                value = "$keepPct%",
                label = "保留的照片"
            )
        }
    }
}

@Composable
private fun StatItem(
    modifier: Modifier,
    badge: Color,
    icon: @Composable () -> Unit,
    value: String,
    label: String,
) {
    val dc = designColors()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(badge),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.height(5.dp))
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = dc.ink,
            maxLines = 1
        )
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = dc.labelGray,
            maxLines = 1
        )
    }
}

@Composable
private fun MemoryCard(state: AppUiState, onOpenMemory: () -> Unit) {
    val dc = designColors()
    val memory = state.stats.memory
    Column(Modifier.fillMaxWidth()) {
        Text("回忆时光机", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = dc.accentText)
        Spacer(Modifier.height(6.dp))
        if (memory == null) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = dc.card),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        null,
                        tint = dc.placeholderIcon,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (state.total == 0) "扫描相册后，这里会浮现往年的今日回忆" else "今天还没有过去的回忆",
                        fontSize = 12.sp,
                        color = dc.slate
                    )
                }
            }
        } else {
            Card(
                onClick = onOpenMemory,
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = dc.card),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${memory.yearsAgo}年前的今天",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = dc.ink
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(memory.dateText, fontSize = 12.sp, color = dc.memoryDate)
                        Spacer(Modifier.height(1.dp))
                        Text("${memory.count} 张照片", fontSize = 12.sp, color = dc.memoryCount)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("去看看", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = dc.accentText)
                            Spacer(Modifier.width(3.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = dc.accentText, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        memory.photos.take(2).forEach { photo ->
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(dc.white)
                            ) {
                                VideoAwareImage(
                                    photo,
                                    Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        if (memory.count > 2) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(dc.accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "+${memory.count - 2}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
