package com.einsli.photoroulette.data

import com.einsli.photoroulette.media.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class SessionQueue(val position: Int, val queue: List<PhotoEntity>)

/** One reconcile pass: rows removed from the never-processed pool, rows marked gone
 *  (processed, kept for history), trash photos the user restored in the system gallery. */
data class ReconcileResult(val poolDeleted: Int, val goneMarked: Int, val restoredCount: Int)

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

    suspend fun scanGallery(config: AppSettings): Int = withContext(Dispatchers.IO) {
        upsertFromScan(config)
        dao.totalNow()
    }

    private suspend fun upsertFromScan(config: AppSettings): List<PhotoEntity> = withContext(Dispatchers.IO) {
        val scanned = scanner.scan(config.includeVideos, config.includeScreenshots, config.includedAlbums)
        dao.insertAll(scanned)
        // Old video rows keep duration=0 (insertAll IGNORE): backfill from the fresh scan so
        // videos that predate the duration column also get their duration badge.
        scanned.filter { it.duration > 0 }.forEach { dao.backfillDuration(it.mediaId, it.duration) }
        // Drop unprocessed photos from albums that are no longer selected, so the total count
        // tracks the album selection. Processed / trashed photos are left untouched.
        if (config.includedAlbums.isNotEmpty()) {
            dao.deleteOutOfScope(config.includedAlbums.map { it.uppercase() })
        }
        // Drop unprocessed videos when 包含视频 is turned OFF, so the pool tracks the toggle.
        if (!config.includeVideos) {
            dao.deleteOutOfVideoScope()
        }
        scanned
    }

    /**
     * 对账:让本地库跟上系统相册 / 系统回收站的外部变化(每次建整理队列前调用)。
     * 1) 增量扫描照常入库(新照片、相册范围、截图/视频开关);
     * 2) 系统里已彻底删除的行:未处理过的直接删,处理过的(保留/回收站)标 gone=1——
     *    从候选池/总数/回收站页/回忆里消失,但 processedAt 留存,周统计与连续天数不被追溯改写;
     * 3) 用户在系统相册恢复了回收站照片 → 同步恢复回待整理池(调用方回退累计删除计数)。
     * 任一步抛异常即整体放弃(调用方跳过本次对账),绝不基于不完整的 MediaStore 结果动手。
     */
    suspend fun reconcile(config: AppSettings): ReconcileResult = withContext(Dispatchers.IO) {
        val scanned = upsertFromScan(config)
        val existing = scanner.scanExistingIds()
        // 同一轮扫描有结果而存在性查询为空,说明后者出了问题:宁可不对账也不批量误删。
        if (existing.isEmpty() && scanned.isNotEmpty()) error("existence query empty while scan found ${scanned.size} rows")
        val dead = dao.activeIds().filter { it !in existing }
        var poolDeleted = 0
        var goneMarked = 0
        // 分块避开 SQLite IN 参数上限;deleteDeadPool 先行,markGone 只会命中余下的已处理行。
        for (chunk in dead.chunked(900)) poolDeleted += dao.deleteDeadPool(chunk)
        for (chunk in dead.chunked(900)) goneMarked += dao.markGone(chunk)
        // 常规扫描(不含系统回收站)里出现的回收站行 = 在系统相册被恢复了。
        val scanIds = scanned.map { it.mediaId }.toHashSet()
        var restoredCount = 0
        for (chunk in dao.trashIds().filter { it in scanIds }.chunked(900)) {
            restoredCount += dao.syncExternallyRestored(chunk)
        }
        ReconcileResult(poolDeleted, goneMarked, restoredCount)
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
