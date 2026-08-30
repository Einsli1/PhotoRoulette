package com.einsli.photoroulette.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.einsli.photoroulette.data.PhotoEntity
import java.io.FileNotFoundException

private const val TAG = "MediaScanner"

/** MediaStore 存在性快照:existing = 当前所有图片/视频的 mediaId;trashed = 其中处于系统
 *  回收站(is_trashed=1)的子集。对账用它区分「文件还在回收站里」和「行还在但文件已被
 *  彻底删除」——后者只在 HyperOS/MIUI 这类残留 provider 行的 ROM 上出现,见 [isFileReadable]。 */
data class MediaStoreSnapshot(val existing: Set<Long>, val trashed: Set<Long>)

class MediaScanner(private val resolver: ContentResolver) {
    fun scan(includeVideos: Boolean, includeScreenshots: Boolean, includedAlbums: List<String> = emptyList()): List<PhotoEntity> {
        val volume = MediaStore.VOLUME_EXTERNAL
        val collection = if (includeVideos) MediaStore.Files.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
        // DURATION only exists meaningfully on the videos/files side; keep it out of the
        // images-only projection so that path stays exactly as before.
        val baseProjection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE)
        val projection = if (includeVideos) baseProjection + MediaStore.MediaColumns.DURATION else baseProjection
        val selection = if (includeVideos) "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)" else null
        val args = if (includeVideos) arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()) else null
        return resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID); val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val taken = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN); val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE); val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE); val durIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            buildList { while (cursor.moveToNext()) {
                val relativePath = cursor.getString(path).orEmpty()
                if (includeScreenshots || !relativePath.contains("screenshot", true)) {
                    // 精确匹配(用户选定语义):选了哪个目录就算哪个,父目录不自动包含子相册。
                    if (includedAlbums.isNotEmpty() && includedAlbums.none { relativePath.equals(it, true) }) continue
                    val mediaId = cursor.getLong(id)
                    val uri = if (cursor.getString(mime).startsWith("video/")) MediaStore.Video.Media.getContentUri(volume, mediaId) else MediaStore.Images.Media.getContentUri(volume, mediaId)
                    val bytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    val duration = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
                    add(PhotoEntity(mediaId, uri.toString(), cursor.getString(name).orEmpty(), cursor.getLong(taken), cursor.getString(mime).orEmpty(), relativePath, bytes, duration))
                }
            } }
        } ?: emptyList()
    }

    /**
     * 全量存在性检查:系统里当前所有图片/视频的 mediaId,供对账判断哪些本地行已被外部删除。
     * 与 [scan] 不同:不做相册/截图/视频过滤,且必须 MATCH_INCLUDE 回收站与 PENDING 文件——
     * 普通查询不返回 trashed 项,漏掉它们会把刚移入系统回收站的照片误判成"已彻底删除"。
     * 同时带回 trashed 子集:[isFileReadable] 只需要对这一小撮行做文件级验证。
     * 查询失败直接抛出,绝不返回部分/空结果——调用方据此放弃本次对账,避免误删。
     */
    fun scanExisting(): MediaStoreSnapshot {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()))
        }
        return resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED), bundle, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val trashedIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
            val existing = HashSet<Long>(); val trashed = HashSet<Long>()
            while (cursor.moveToNext()) {
                val v = cursor.getLong(id)
                existing.add(v)
                if (cursor.getInt(trashedIdx) != 0) trashed.add(v)
            }
            MediaStoreSnapshot(existing, trashed)
        } ?: error("MediaStore existence query returned null")
    }

    /**
     * 单行文件活性探测:provider 行存在 ≠ 文件还在。HyperOS/MIUI 上把回收站里的照片「永久
     * 删除」后,文件立刻消失,但 MediaStore 行会以 is_trashed=1 残留(等 30 天过期清扫才删
     * 行),纯 id 存在性比对永远判不出这类删除(真机实测:goneMarked 恒为 0)。这里直接尝试
     * 打开文件描述符:FileNotFoundException = 文件确实没了;其余任何异常(权限抖动、云同步中
     * 等)一律按「还活着」处理——探测绝不能造成误删。
     */
    fun isFileReadable(mediaId: Long): Boolean {
        return try {
            val uri = rowUri(mediaId)
            resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: FileNotFoundException) {
            Log.d(TAG, "liveness: mediaId=$mediaId file gone (FNFE); row: ${describeRow(mediaId)}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "liveness: mediaId=$mediaId open failed (${e.javaClass.simpleName}) — treat as ALIVE; row: ${describeRow(mediaId)}", e)
            true
        }
    }

    private fun rowUri(mediaId: Long): Uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), mediaId)

    /** 调试辅助:MATCH_INCLUDE 下读回单行的关键字段,日志里看清残留行的真实状态。 */
    private fun describeRow(mediaId: Long): String {
        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(mediaId.toString()))
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.IS_PENDING,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE)
        return try {
            resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, bundle, null)?.use { c ->
                if (!c.moveToFirst()) "no row"
                else "is_trashed=${c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED))}" +
                    " pending=${c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING))}" +
                    " media_type=${c.getInt(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))}" +
                    " data=${c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))}" +
                    " size=${c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))}"
            } ?: "null cursor"
        } catch (e: Exception) {
            "query failed: ${e.message}"
        }
    }

    fun listAlbums(includeVideos: Boolean = false): List<String> {
        val volume = MediaStore.VOLUME_EXTERNAL
        val collection = if (includeVideos) MediaStore.Files.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
        val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
        return resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            buildList {
                val seen = HashSet<String>()
                while (cursor.moveToNext()) {
                    val p = cursor.getString(path).orEmpty()
                    if (p.isNotEmpty() && !seen.contains(p)) { seen.add(p); add(p) }
                }
            }
        } ?: emptyList()
    }
}
