package com.einsli.photoroulette.data

import android.util.Log
import com.einsli.photoroulette.media.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class SessionQueue(val position: Int, val queue: List<PhotoEntity>)

/** One reconcile pass: rows removed from the never-processed pool, rows marked gone
 *  (processed, kept for history), trash photos the user restored in the system gallery. */
data class ReconcileResult(val poolDeleted: Int, val goneMarked: Int, val restoredCount: Int, val livenessDead: Int = 0)

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
        // tracks the album selection. Processed / trashed photos are left untouched. The keep
        // rule MUST stay in lockstep with MediaScanner.scan's filter (case-insensitive EXACT
        // match, user-chosen semantics: 选了哪个目录就算哪个,父目录不自动包含子相册) — 当年
        // scan 用前缀、清理用精确匹配,两边不一致让 451 张子目录照片每轮对账插了又删,首页
        // 总数肉眼可见地来回跳。删除清单按 UPPER(album) 精确删。
        if (config.includedAlbums.isNotEmpty()) {
            val keep = config.includedAlbums.map { it.uppercase() }.toSet()
            val outOfScope = dao.poolAlbums().filter { album -> album.uppercase() !in keep }
            if (outOfScope.isNotEmpty()) dao.deleteOutOfScope(outOfScope.map { it.uppercase() })
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
        val snap = scanner.scanExisting()
        Log.d("Reconcile", "scanned=${scanned.size} existing=${snap.existing.size} trashed=${snap.trashed.size}")
        // 同一轮扫描有结果而存在性查询为空,说明后者出了问题:宁可不对账也不批量误删。
        if (snap.existing.isEmpty() && scanned.isNotEmpty()) error("existence query empty while scan found ${scanned.size} rows")
        val active = dao.activeIds()
        val activeSet = active.toHashSet()
        // 1) id 已不在 MediaStore:AOSP 上「彻底删除」连行一起删,这一步就能抓到。
        val dead = active.filter { it !in snap.existing }.toMutableList()
        // 2) 行还挂在 MediaStore(is_trashed=1)但文件已打不开:HyperOS/MIUI 在系统相册回收站
        //    「永久删除」只删文件,provider 行残留到 30 天过期清扫,纯 id 比对永远判不出
        //    (真机实测 goneMarked 恒 0)。对这批行做文件活性探测,FileNotFoundException 才算死,
        //    其余异常按「还活着」处理——探测绝不能造成误删。
        var livenessDead = 0
        for (id in snap.trashed) {
            if (id !in activeSet) continue
            if (scanner.isFileReadable(id)) continue
            dead.add(id)
            livenessDead++
        }
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
        ReconcileResult(poolDeleted, goneMarked, restoredCount, livenessDead)
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
    /** 会话队列原位重算用:按 id 取仍然存在的行(gone=0),顺序由调用方自己保持。 */
    suspend fun liveQueuePhotos(ids: List<Long>): List<PhotoEntity> = dao.byIds(ids)
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
