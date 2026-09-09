package com.einsli.photoroulette.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Log
import android.util.Size
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.videoFrameMillis
import coil.size.Size as CoilSize
import com.einsli.photoroulette.model.PhotoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 首页封面缓存:今日任务卡(会话 current)与回忆时光机卡(memory 前 2 张)的封面小图。
 *
 * 冷启动时进程内存缓存必空,而磁盘小 JPEG 的解码只要几毫秒——把封面落成 cacheDir
 * 下的文件,首页打开即显示,不再等 MediaStore 原图打开 + 降采样解码(视频更慢)。
 *
 * 生成时机 = 「数据确定的一刻」(挂在 PhotoViewModel):冷启动恢复存档队列后 /
 * current 推进后 / memory 重算后 —— 这些时刻都远早于首页首帧,新内容第一次出现
 * 即命中。首页在缓存未命中时回退原图请求,成功后 write-through 补写一份,兜住
 * 极少数生成输给首帧的竞态(如超大视频抽帧)。
 *
 * 文件随 cacheDir 走:系统/用户清缓存后自然自愈(回退原图 → 重新生成)。
 */
object PreviewCache {
    private const val TAG = "PreviewCache"
    private const val MAX_EDGE = 320     // 封面最长边;卡片显示 ~350x250px 足够
    private const val JPEG_QUALITY = 82
    private const val KEEP_FILES = 60    // LRU 上限:整理一张生成一张,防止无限堆积

    // 单飞:同一 mediaId 的并发生成只跑一次(快滑连续推进/首页与 ViewModel 同时触发)。
    private val inflight = HashSet<Long>()
    private val inflightMutex = Mutex()

    // 供「请求成功后补写」的 fire-and-forget 调用;失败静默,不影响 UI。
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun fileFor(context: Context, mediaId: Long): File =
        File(File(context.applicationContext.cacheDir, "previews"), "$mediaId.jpg")

    fun hasPreview(context: Context, mediaId: Long): Boolean = fileFor(context, mediaId).exists()

    /** 首页封面请求:小图存在 → 直接用(打开即显);否则回退原图(同参,视频仍抽 1s 帧)。 */
    fun homeRequest(context: Context, photo: PhotoItem, size: CoilSize? = null): ImageRequest =
        ImageRequest.Builder(context)
            .data(fileFor(context, photo.mediaId).takeIf { it.exists() } ?: Uri.parse(photo.uri))
            .apply {
                size?.let { size(it) }
                if (photo.mimeType.startsWith("video/")) videoFrameMillis(1000)
            }
            .build()

    /** 确保 [photo] 的封面存在,缺失则生成。幂等(文件已在 → 立即返回)+ 单飞。 */
    suspend fun ensure(context: Context, photo: PhotoItem) {
        val app = context.applicationContext
        if (fileFor(app, photo.mediaId).exists()) return
        val acquired = inflightMutex.withLock { inflight.add(photo.mediaId) }
        if (!acquired) return
        try {
            withContext(Dispatchers.IO) {
                val bitmap = loadViaSystemThumbnail(app, photo) ?: loadViaCoil(app, photo)
                    ?: return@withContext
                val scaled = if (max(bitmap.width, bitmap.height) > MAX_EDGE) scaleDown(bitmap) else bitmap
                val file = fileFor(app, photo.mediaId)
                file.parentFile?.mkdirs()
                runCatching {
                    file.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
                }.onFailure { Log.w(TAG, "write preview ${photo.mediaId} failed", it) }
                if (scaled !== bitmap) bitmap.recycle()
                scaled.recycle()
                prune(app)
            }
        } finally {
            inflightMutex.withLock { inflight.remove(photo.mediaId) }
        }
    }

    /** 非挂起点(Compose 回调)用的 fire-and-forget 版本。 */
    fun ensureAsync(context: Context, photo: PhotoItem) {
        ioScope.launch { runCatching { ensure(context, photo) } }
    }

    /** 系统缩略图库:命中 MediaProvider 缓存时不解码原图,生成更快;未命中返回 null。 */
    private fun loadViaSystemThumbnail(context: Context, photo: PhotoItem): Bitmap? = runCatching {
        context.contentResolver.loadThumbnail(Uri.parse(photo.uri), Size(MAX_EDGE, MAX_EDGE), null)
    }.onFailure { Log.d(TAG, "loadThumbnail miss for ${photo.mediaId}: ${it.message}") }.getOrNull()

    /** 回退:Coil 解码原图(视频抽 1s 帧);allowHardware(false) 因为 JPEG 压缩需要软件位图。 */
    private suspend fun loadViaCoil(context: Context, photo: PhotoItem): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context)
            .data(Uri.parse(photo.uri))
            .size(MAX_EDGE, MAX_EDGE)
            .apply { if (photo.mimeType.startsWith("video/")) videoFrameMillis(1000) }
            .allowHardware(false)
            .build()
        (context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable
    }.getOrNull()?.bitmap

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val scale = MAX_EDGE.toFloat() / max(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** LRU:超上限时按 lastModified 淘汰最旧的;活跃封面刚写入,不会被淘汰。 */
    private fun prune(context: Context) {
        val files = File(context.cacheDir, "previews").listFiles() ?: return
        if (files.size <= KEEP_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - KEEP_FILES)
            .forEach { it.delete() }
    }
}
