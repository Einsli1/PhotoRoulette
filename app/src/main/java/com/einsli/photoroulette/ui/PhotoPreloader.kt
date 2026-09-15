package com.einsli.photoroulette.ui

// 宫格缩略图预载引擎（回收站 / 回忆时光机共用）：以 viewport 为中心的固定窗口预载。
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Size as CoilSize
import com.einsli.photoroulette.model.PhotoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

// ── 宫格缩略图预载（回收站 / 回忆时光机共用）───────────────────────────────────────
// 设计：只预载「当前 viewport 附近」的固定窗口，绝不因快速滚动把沿途照片灌进队列。
// 用户从第 0 张甩到第 5000 张：fling 期间 isScrollInProgress 恒为 true，经过的
// 1..4999 不产生任何预载；fling 停稳（settle）并经过一小段防抖后，才围绕停稳位置
// （如可见 5000..5020）铺 4970..5050 这一圈。滚动条快速拖拽的中间位置同样被防抖吞掉。
// 可见区域 + 上方少量 + 下方少量 = 窗口；窗口大小固定，不随滚动历史增长（无 frontier）。
//
// 视频**不预载**（2026-09-15 真机实测后加的）：视频缩略图是「抽一帧」，一次要占一个
// MediaMetadataRetriever（走 VideoDecodeGate 的并发闸门，见该类注释），解码成本是图片的
// 几十倍。窗口是可见区 ±30 项，全视频的一屏（~28 格）就会往里塞 ~88 个抽帧请求，在 3 个
// 名额的闸门后面排长队——实测冷启动时队尾要等 13.2 秒，而用户滑走时这些请求又会被取消，
// 纯属无用功，还挤占了可见格子的名额。现在预载只铺图片（图片解码快、不限并发），视频交给
// 可见格子自己的组合期请求按需抽帧；请求参数与预载完全一致，缓存命中路径不变。
internal const val GRID_PRELOAD_MARGIN_ITEMS = 30
internal const val GRID_PRELOAD_SETTLE_DEBOUNCE_MS = 150L

/**
 * 以 viewport 为中心的固定窗口预载。请求与格子完全同参（data+size+视频帧），直接填
 * 格子要读的那条内存缓存；窗口随停稳位置移动，落在窗口外、尚未解码完的请求立即取消，
 * 因此任意时刻队列里的预载工作量都被限制在一个窗口内，而不是随滚动历史累积。
 * 可见格子自身仍由组合期的 cell 请求负责（滑出即取消），这里的窗口只负责「附近的余量」。
 */
@Composable
internal fun GridWindowedThumbnailPreload(gridState: LazyGridState, photos: List<PhotoItem>, size: CoilSize) {
    val context = LocalContext.current
    val loader = remember(context) { context.imageLoader }
    LaunchedEffect(gridState, photos, size) {
        // index → 该预载请求的 Disposable：在队/解码中即存在，完成或取消后移出。
        val tracked = HashMap<Int, Disposable>()
        fun enqueue(index: Int) {
            if (index in tracked) return
            val photo = photos.getOrNull(index) ?: return
            // 视频不预载（见文件头注释）：抽帧贵且要抢系统取帧名额，交给可见格子按需请求。
            if (photo.mimeType.startsWith("video/")) return
            tracked[index] = loader.enqueue(
                ImageRequest.Builder(context)
                    .data(photo.uri)
                    .size(size)
                    .build()
            )
        }
        fun preloadAroundViewport() {
            val info = gridState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = (info.visibleItemsInfo.lastOrNull()?.index ?: first).coerceAtLeast(first)
            val from = (first - GRID_PRELOAD_MARGIN_ITEMS).coerceAtLeast(0)
            val to = (last + GRID_PRELOAD_MARGIN_ITEMS).coerceAtMost(photos.lastIndex)
            if (from > to) return
            for (i in first..last) enqueue(i)      // 停稳的可见区最先入队
            for (i in (last + 1)..to) enqueue(i)   // 下方余量（继续下滑的方向）
            for (i in from until first) enqueue(i) // 上方余量
            // 窗口外仍在排队/解码中的请求已不需要：取消；已完成的结果留在内存缓存不重解码。
            val keep = from..to
            tracked.entries.removeAll { (index, d) ->
                if (index in keep) false else { if (!d.job.isCompleted) d.dispose(); true }
            }
        }
        try {
            // 进页时 isScrollInProgress 初始为 false，同样走到这里 → 首屏窗口在进入后
            // ~150ms 铺开。滚动一恢复，collectLatest 会取消未触发的 delay，重新等停稳。
            snapshotFlow { gridState.isScrollInProgress }
                .distinctUntilChanged()
                .collectLatest { scrolling ->
                    if (!scrolling) {
                        delay(GRID_PRELOAD_SETTLE_DEBOUNCE_MS)
                        preloadAroundViewport()
                    }
                }
        } finally {
            // 离开页面或列表变化重建时，未完成的预载一并取消，不背着旧窗口跑完。
            tracked.values.forEach { if (!it.job.isCompleted) it.dispose() }
        }
    }
}
