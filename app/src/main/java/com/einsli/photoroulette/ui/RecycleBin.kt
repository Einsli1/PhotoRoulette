package com.einsli.photoroulette.ui

// 回收站页（RecycleBin）：页面骨架（4 列密铺宫格 + 多选/恢复/彻底删除 + 全屏预览，
// MIUI 黑底设计稿实现）由共享的 MediaGridScreen 提供；本文件只剩回收站的差异部分——
// 选择模式配置（恢复/彻底删除动作）、悬浮头部（回收站/总容量 + 垃圾桶入口）、
// 预览自定义头部（拍摄日期/时间）。
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.einsli.photoroulette.PhotoViewModel
import com.einsli.photoroulette.model.PhotoItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RecycleBin(
    items: List<PhotoItem>,
    trashBytes: Long,
    viewModel: PhotoViewModel,
    onRestore: (List<Long>) -> Unit,
    onBack: () -> Unit,
) {
    MediaGridScreen(
        photos = items,
        onBack = onBack,
        // 设计图（回收站顶部.jpg）：页面 = MIUI 纯黑底。
        pageBackground = Color.Black,
        emptyText = "回收站为空",
        emptyTextColor = Color.White.copy(alpha = 0.6f),
        // 设计图（回收站中间.jpg）：底部给选择胶囊留 96dp，头部渐变加高到 56dp。
        gridBottomPadding = 96.dp,
        headerBottomPadding = 56.dp,
        // 缩略图解码尺寸 = 显示像素的 70%：解码快 ~2 倍、单张内存省一半，
        // 配合扩容后的内存缓存（见 PhotoRouletteApp），窗口内载过的缩略图滑回来直接命中。
        thumbDecodeScale = 0.7f,
        dummyKeyPrefix = "trash",
        scrollBar = true,
        // 选择模式：恢复走 App 的 onRestore，彻底删除走 ViewModel（各自内部自启协程）。
        selection = MediaGridSelection(
            onRestore = onRestore,
            onDelete = { ids -> viewModel.deleteFromTrash(ids) },
        ),
        // 悬浮头部：返回‹ + 「回收站/总容量」 + 垃圾桶(进选择模式)。
        headerContent = { enterSelection ->
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("回收站", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(formatCapacity(trashBytes), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                IconButton(onClick = { enterSelection?.invoke() }) {
                    Icon(Icons.Outlined.Delete, "选择照片", tint = Color.White)
                }
            }
        },
        // 设计图（照片/视频预览页.jpg）：预览头部 = 返回‹ + 日期粗体 + 时间小字，取 dateTaken；
        // 替换默认头部（关闭/文件名/页码）。
        previewHeader = { current, requestClose ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { requestClose() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                if (formatTrashDate(current.dateTaken).isEmpty()) {
                    Text(current.displayName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(formatTrashDate(current.dateTaken), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(formatTrashTime(current.dateTaken), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        },
    )
}

/** 回收站预览头部的日期（2022年1月27日）/ 时间（23:51）两行，取 dateTaken。 */
private fun formatTrashDate(taken: Long): String =
    if (taken <= 0L) "" else SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(taken))

private fun formatTrashTime(taken: Long): String =
    if (taken <= 0L) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(taken))