package com.einsli.photoroulette.data

import com.einsli.photoroulette.media.MediaScanner
import kotlinx.coroutines.flow.Flow

data class SessionQueue(val position: Int, val queue: List<PhotoEntity>)

class PhotoRepository(private val dao: PhotoDao, private val scanner: MediaScanner, private val settings: SettingsRepository) {
    val processedCount: Flow<Int> = dao.processedCount()
    val totalCount: Flow<Int> = dao.totalCount()
    val keptCount: Flow<Int> = dao.keptCount()
    val processedDays: Flow<List<String>> = dao.processedDays()
    val trashBytes: Flow<Long> = dao.trashBytes()
    val memoryCandidates: Flow<List<PhotoEntity>> = dao.memoryCandidates()

    fun dayCountsSince(since: Long): Flow<List<PhotoDao.DayCount>> = dao.dayCountsSince(since)
    fun weekKept(since: Long): Flow<Int> = dao.weekKept(since)
    fun weekFreedBytes(since: Long): Flow<Long> = dao.weekFreedBytes(since)

    /** Resolve the [AppSettings.photoRange] into a [dateTaken] window (inclusive min, exclusive max). */
    private fun dateRange(config: AppSettings): Pair<Long?, Long?> {
        val now = System.currentTimeMillis()
        val yearAgo = now - 365L * 24 * 3600 * 1000
        return when (config.photoRange) {
            "lastYear" -> yearAgo to null
            "beforeLastYear" -> null to yearAgo
            "custom" -> if (config.customRangeStart > 0) config.customRangeStart to null else null to null
            else -> null to null // all
        }
    }

    suspend fun scanGallery(config: AppSettings): Int {
        dao.insertAll(scanner.scan(config.includeVideos, config.includeScreenshots, config.includedAlbums))
        // Drop unprocessed photos from albums that are no longer selected, so the total count
        // tracks the album selection. Processed / trashed photos are left untouched.
        if (config.includedAlbums.isNotEmpty()) {
            dao.deleteOutOfScope(config.includedAlbums.map { it.uppercase() })
        }
        return dao.totalNow()
    }

    suspend fun listAlbums(includeVideos: Boolean = false): List<String> = scanner.listAlbums(includeVideos)
    suspend fun sessionQueue(config: AppSettings): SessionQueue {
        val (savedIds, savedPos) = settings.currentQueue()
        if (savedIds.isNotEmpty()) {
            val all = dao.byIds(savedIds).sortedBy { savedIds.indexOf(it.mediaId) }
            // Resume only if the saved session still has photos left. A saved position at the
            // end means the previous session was completed (or the photos were removed from the
            // gallery); restoring it would leave the home screen with 0 remaining and a dead
            // "start" button, so start a fresh session instead.
            if (all.isNotEmpty() && savedPos < all.size) return SessionQueue(savedPos, all)
            if (all.isNotEmpty()) settings.clearQueue()
        }
        val (minDate, maxDate) = dateRange(config)
        val photos = when (config.strategy) {
            "oldest" -> dao.oldestCandidates(config.dailyCount, minDate, maxDate)
            "largest" -> dao.largestCandidates(config.dailyCount, minDate, maxDate)
            else -> dao.randomCandidates(config.dailyCount, minDate, maxDate)
        }
        if (photos.isNotEmpty()) settings.saveQueue(photos.map { it.mediaId })
        return SessionQueue(0, photos)
    }
    suspend fun savePosition(position: Int, queueIds: List<Long>) = settings.saveQueue(queueIds, position)
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
