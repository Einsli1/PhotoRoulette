package com.einsli.photoroulette.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import coil.compose.AsyncImage
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
        Header()
        Spacer(Modifier.height(8.dp))
        TodayTaskCard(state, onStart, onScan)
        Spacer(Modifier.height(10.dp))
        ProgressSection(state)
        Spacer(Modifier.height(8.dp))
        StatsRow(state)
        Spacer(Modifier.height(10.dp))
        MemoryCard(state, onOpenMemory)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header() {
    val dc = designColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
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
        // The wheel icon. In light mode it sits on a lavender disc; in dark mode it stands
        // alone (no frame) and is zoomed slightly so the artwork's white margin is clipped.
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .then(if (dc.isDark) Modifier else Modifier.background(dc.card)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.wheel_roulette),
                contentDescription = "照片轮盘",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (dc.isDark) { scaleX = 1.25f; scaleY = 1.25f }
                    },
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
                        Text(
                            "$count 张照片",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = dc.ink,
                            maxLines = 1
                        )
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
                            .height(78.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(dc.white)
                    ) {
                        if (preview != null) {
                            AsyncImage(
                                model = preview.uri,
                                contentDescription = preview.displayName,
                                modifier = Modifier.fillMaxSize(),
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
                Spacer(Modifier.height(10.dp))
                // The hero action: the single most prominent control on the home screen.
                if (state.total == 0 && !state.loading) {
                    Button(
                        onClick = onScan,
                        Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = dc.accent, contentColor = Color.White)
                    ) {
                        Text("扫描相册", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Button(
                        onClick = onStart,
                        Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = dc.accent, contentColor = Color.White)
                    ) {
                        Text(if (inProgress) "继续整理" else "开始整理", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
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

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = dc.cardSoft),
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
                        .background(dc.accent)
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
        colors = CardDefaults.cardColors(containerColor = dc.cardSoft),
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
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(dc.white)
                            ) {
                                AsyncImage(
                                    model = photo.uri,
                                    contentDescription = photo.displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        if (memory.count > 2) {
                            Box(
                                Modifier
                                    .size(46.dp)
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
