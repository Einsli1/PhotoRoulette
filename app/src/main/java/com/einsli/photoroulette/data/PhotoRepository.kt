package com.einsli.photoroulette.data

import com.einsli.photoroulette.media.MediaScanner
import kotlinx.coroutines.flow.Flow

class PhotoRepository(private val dao: PhotoDao, private val scanner: MediaScanner, private val settings: SettingsRepository) {
    val processedCount: Flow<Int> = dao.processedCount()
    val totalCount: Flow<Int> = dao.totalCount()

    suspend fun scanGallery(config: AppSettings): Int {
        dao.insertAll(scanner.scan(config.includeVideos, config.includeScreenshots, config.includedAlbums))
        return dao.totalNow()
    }

    suspend fun listAlbums(includeVideos: Boolean = false): List<String> = scanner.listAlbums(includeVideos)
    suspend fun sessionQueue(config: AppSettings): List<PhotoEntity> {
        val saved = settings.currentQueue()
        val resumable = dao.byIds(saved).filter { it.state == PhotoState.UNSEEN || it.state == PhotoState.SKIP }.sortedBy { saved.indexOf(it.mediaId) }
        if (resumable.isNotEmpty()) return resumable
        val photos = dao.randomCandidates(config.dailyCount)
        if (photos.isNotEmpty()) {
            settings.saveQueue(photos.map { it.mediaId })
        }
        return photos
    }
    suspend fun apply(photo: PhotoEntity, state: PhotoState) = dao.updateState(photo.mediaId, state, if (state == PhotoState.SKIP) null else System.currentTimeMillis())
    suspend fun startNextSession() = settings.clearQueue()
    suspend fun pendingDeletes(): List<PhotoEntity> = dao.pendingDeletes()
    suspend fun confirmDeleted(ids: List<Long>) = dao.confirmDeleted(ids)
    val trashItems: Flow<List<PhotoEntity>> = dao.trashItems()
    suspend fun trashList(): List<PhotoEntity> = dao.trashNow()
    suspend fun restoreFromTrash(ids: List<Long>) = dao.restoreFromTrash(ids)
    suspend fun revertPendingDeletes(ids: List<Long>) = dao.revertPendingDeletes(ids)
    suspend fun deleteFromTrash(ids: List<Long>) = dao.deleteByIds(ids)
    suspend fun reset() = dao.clear()
}
