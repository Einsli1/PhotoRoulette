package com.einsli.photoroulette.ui

// 回忆时光机（MemoryViewer）：「N年前的今天」照片宫格 + 全屏预览。页面骨架由共享的
// MediaGridScreen 提供；本文件只剩回忆时光机的差异部分——悬浮头部内容（标题 +
// 「N年前的今天 · 日期 · 张数」副标题）、主题配色，以及回收站同款的多选删除入口。
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
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
internal fun MemoryViewer(memory: MemoryInfo?, onBack: () -> Unit, onDelete: (List<Long>) -> Unit) {
    val dc = designColors()
    MediaGridScreen(
        photos = memory?.photos ?: emptyList(),
        onBack = onBack,
        pageBackground = dc.pageBg,
        emptyText = "暂无回忆",
        emptyTextColor = dc.slate,
        // 与回收站同款：底部给选择胶囊留 96dp。常量留白（不是"进选择模式才加"）——留白
        // 一变宫格内容就跳位，回收站也是这么做的。
        gridBottomPadding = 96.dp,
        headerBottomPadding = 48.dp,
        dummyKeyPrefix = "memory",
        // 选择模式（同回收站）：长按或右上垃圾桶进入多选，选择圈/头部选择条/底部胶囊全部
        // 复用同一套 UI。差异只在动作——回忆时光机的删除是"移入系统回收站"（照片不在回收站，
        // 没有恢复可言），所以只给 onDelete，胶囊少一颗按钮、样式不变。
        selection = MediaGridSelection(onDelete = onDelete),
        // 悬浮头部（同回收站）：关闭 X + 标题/副标题改白字浮在宫格上层，顶部渐变让穿过的
        // 照片有压暗层次、白字始终可读；右侧垃圾桶 = 选择模式入口（与回收站同一位置）。
        headerContent = { enterSelection ->
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
                IconButton(onClick = { enterSelection?.invoke() }) {
                    Icon(Icons.Outlined.Delete, "选择照片", tint = Color.White)
                }
            }
        },
    )
}
