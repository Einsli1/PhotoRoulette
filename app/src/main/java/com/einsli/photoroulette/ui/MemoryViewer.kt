package com.einsli.photoroulette.ui

// 回忆时光机（MemoryViewer）：「N年前的今天」照片宫格 + 全屏预览。页面骨架由共享的
// MediaGridScreen 提供；本文件只剩回忆时光机的差异部分——悬浮头部内容（标题 +
// 「N年前的今天 · 日期 · 张数」副标题）与主题配色。
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.einsli.photoroulette.MemoryInfo

@Composable
internal fun MemoryViewer(memory: MemoryInfo?, onBack: () -> Unit) {
    val dc = designColors()
    MediaGridScreen(
        photos = memory?.photos ?: emptyList(),
        onBack = onBack,
        pageBackground = dc.pageBg,
        emptyText = "暂无回忆",
        emptyTextColor = dc.slate,
        gridBottomPadding = 16.dp,
        headerBottomPadding = 48.dp,
        dummyKeyPrefix = "memory",
        // 悬浮头部（同回收站）：关闭 X + 标题/副标题改白字浮在宫格上层，顶部渐变让穿过的
        // 照片有压暗层次、白字始终可读。
        headerContent = {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.Close, "返回", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("回忆时光机", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (memory != null) {
                        Text(
                            "${memory.yearsAgo}年前的今天 · ${memory.dateText} · ${memory.count} 张照片",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        },
    )
}