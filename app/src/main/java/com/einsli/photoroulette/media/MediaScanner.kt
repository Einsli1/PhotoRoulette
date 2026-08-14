package com.einsli.photoroulette.media

import android.content.ContentResolver
import android.provider.MediaStore
import com.einsli.photoroulette.data.PhotoEntity

class MediaScanner(private val resolver: ContentResolver) {
    fun scan(includeVideos: Boolean, includeScreenshots: Boolean, includedAlbums: List<String> = emptyList()): List<PhotoEntity> {
        val volume = MediaStore.VOLUME_EXTERNAL
        val collection = if (includeVideos) MediaStore.Files.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE)
        val selection = if (includeVideos) "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)" else null
        val args = if (includeVideos) arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()) else null
        return resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID); val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val taken = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN); val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE); val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            buildList { while (cursor.moveToNext()) {
                val relativePath = cursor.getString(path).orEmpty()
                if (includeScreenshots || !relativePath.contains("screenshot", true)) {
                    if (includedAlbums.isNotEmpty() && includedAlbums.none { relativePath.startsWith(it, true) }) continue
                    val mediaId = cursor.getLong(id)
                    val uri = if (cursor.getString(mime).startsWith("video/")) MediaStore.Video.Media.getContentUri(volume, mediaId) else MediaStore.Images.Media.getContentUri(volume, mediaId)
                    val bytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    add(PhotoEntity(mediaId, uri.toString(), cursor.getString(name).orEmpty(), cursor.getLong(taken), cursor.getString(mime).orEmpty(), bytes))
                }
            } }
        } ?: emptyList()
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
