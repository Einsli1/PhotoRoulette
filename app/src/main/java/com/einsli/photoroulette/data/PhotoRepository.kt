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

    fun dayCountsBetween(start: Long, end: Long): Flow<List<PhotoDao.DayCount>> = dao.dayCountsBetween(start, end)
    fun weekKeptBetween(start: Long, end: Long): Flow<Int> = dao.weekKeptBetween(start, end)
    fun weekFreedBytesBetween(start: Long, end: Long): Flow<Long> = dao.weekFreedBytesBetween(start, end)
    suspend fun earliestProcessedAt(): Long? = dao.earliestProcessedAt()

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
        // MediaStore 扫描是慢 IO,必须留在事务外;随后的 insertAll → 时长回填 → 相册/视频
        // 开关清理是连续 DB 写,由 [PhotoDao.applyScan] 包一个事务(中途失败整体回滚)。
        // 相册 keep 规则必须与 MediaScanner.scan 的过滤严格同源,规则细节与历史教训见 applyScan 注释。
        val scanned = scanner.scan(config.includeVideos, config.includeScreenshots, config.includedAlbums)
        dao.applyScan(
            scanned = scanned,
            keepAlbumsUppercase = config.includedAlbums.map { it.uppercase() }.toSet(),
            videosEnabled = config.includeVideos
        )
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
        // 3) 连续 DB 写(deleteDeadPool/markGone/syncExternallyRestored)整段包一个事务
        //    ([PhotoDao.applyReconcile],分块也在里面):上面的扫描与活性探测都是慢 IO,必须留在事务外。
        val writes = dao.applyReconcile(dead, scanned.map { it.mediaId }.toHashSet())
        ReconcileResult(writes.poolDeleted, writes.goneMarked, writes.restoredCount, livenessDead)
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
    /** 按 id 取仍然活着的行(gone=0 且不在回收站),顺序由调用方自己保持。 */
    suspend fun photosByIds(ids: List<Long>): List<PhotoEntity> = dao.byIds(ids)
    /** 会话队列原位重算用:按 id 取仍然存在的行,顺序由调用方自己保持。 */
    suspend fun liveQueuePhotos(ids: List<Long>): List<PhotoEntity> = photosByIds(ids)
    /** 应用动作:只需 mediaId + 目标状态(调用方持 UI 模型,不再把实体传回数据层)。 */
    suspend fun apply(mediaId: Long, state: PhotoState) = dao.updateState(mediaId, state, if (state == PhotoState.SKIP) null else System.currentTimeMillis())
    suspend fun startNextSession() = settings.clearQueue()
    suspend fun pendingDeletes(): List<PhotoEntity> = dao.pendingDeletes()
    suspend fun confirmDeleted(ids: List<Long>) = dao.confirmDeleted(ids)
    /** 回忆时光机的删除:系统回收站请求被确认后直接落库(不进整理会话的 DELETE_PENDING)。 */
    suspend fun confirmTrashedFromMemory(ids: List<Long>, at: Long) = dao.confirmDeletedAt(ids, at)
    val trashItems: Flow<List<PhotoEntity>> = dao.trashItems()
    suspend fun trashList(): List<PhotoEntity> = dao.trashNow()
    suspend fun restoreFromTrash(ids: List<Long>) = dao.restoreFromTrash(ids)
    suspend fun revertPendingDeletes(ids: List<Long>) = dao.revertPendingDeletes(ids)
    suspend fun deleteFromTrash(ids: List<Long>) = dao.deleteByIds(ids)
    suspend fun reset() = dao.clear()
}
