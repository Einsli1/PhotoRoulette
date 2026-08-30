package com.einsli.photoroulette.media

import android.content.ContentResolver
import android.os.Bundle
import android.provider.MediaStore
import com.einsli.photoroulette.data.PhotoEntity

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
                    if (includedAlbums.isNotEmpty() && includedAlbums.none { relativePath.startsWith(it, true) }) continue
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
     * 查询失败直接抛出,绝不返回部分/空结果——调用方据此放弃本次对账,避免误删。
     */
    fun scanExistingIds(): Set<Long> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()))
        }
        return resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), bundle, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(id)) }
        } ?: error("MediaStore existence query returned null")
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
