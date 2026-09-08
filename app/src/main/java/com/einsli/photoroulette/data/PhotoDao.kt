package com.einsli.photoroulette.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<PhotoEntity>)

    // Distinct album paths of the never-processed pool — the caller keeps only albums that pass
    // the SAME prefix rule MediaScanner.scan uses, then deletes the rest via [deleteOutOfScope].
    @Query("SELECT DISTINCT album FROM photos WHERE inTrash = 0 AND state IN ('UNSEEN', 'SKIP') AND album != ''")
    suspend fun poolAlbums(): List<String>

    // Photos of deselected albums that were never processed: removed on rescan so the pool and
    // the total count reflect the album selection. Processed / trashed photos are kept, so the
    // organizing history and the trash can never be wiped by a selection change. [albums] must
    // be non-empty; pass the out-of-scope album paths uppercased.
    @Query("DELETE FROM photos WHERE inTrash = 0 AND state IN ('UNSEEN', 'SKIP') AND UPPER(album) IN (:albums)")
    suspend fun deleteOutOfScope(albums: List<String>)

    // Same idea for the 包含视频 toggle: when videos are turned OFF, drop unprocessed videos
    // from the pool so the total count tracks the toggle. Processed / trashed videos stay, so
    // organizing history and the trash are never wiped by toggling.
    @Query("DELETE FROM photos WHERE inTrash = 0 AND state IN ('UNSEEN', 'SKIP') AND mimeType LIKE 'video/%'")
    suspend fun deleteOutOfVideoScope()

    // Videos scanned before the duration column existed keep duration=0 (insertAll IGNORE
    // never touches existing rows); backfill them from a fresh scan so old videos also show
    // their duration pill. The duration=0 guard keeps it a no-op once filled.
    @Query("UPDATE photos SET duration = :duration WHERE mediaId = :mediaId AND duration = 0 AND mimeType LIKE 'video/%'")
    suspend fun backfillDuration(mediaId: Long, duration: Long)

    // ── 多步写的事务包装:连续的 DB 写整段包一个事务,中途失败整体回滚,不留半套中间态。
    // 约束:这里只做纯 DB 写,慢 IO(MediaStore 扫描、文件活性探测)必须由调用方留在事务外。

    // 扫描入库一条龙:insertAll → 视频时长回填 → 相册/视频开关清理(PhotoRepository.upsertFromScan 调用)。
    @Transaction
    suspend fun applyScan(scanned: List<PhotoEntity>, keepAlbumsUppercase: Set<String>, videosEnabled: Boolean) {
        insertAll(scanned)
        scanned.filter { it.duration > 0 }.forEach { backfillDuration(it.mediaId, it.duration) }
        // 相册白名单变更:未处理、不在白名单内的行删除,让总数跟随相册选择。已处理/回收站的
        // 行绝不动(整理历史与回收站不能被开关抹掉)。keep 规则必须与 MediaScanner.scan 的相册
        // 过滤严格同源:ignoreCase 精确相等,用户拍板「选了哪个目录就算哪个,父目录不自动包含
        // 子相册」——当年 scan 用前缀、清理用精确匹配,两边不一致让 451 张子目录照片每轮对账
        // 插了又删,首页总数肉眼可见地来回跳(AGENTS.md 坑 23)。删除清单按 UPPER(album) 精确删。
        if (keepAlbumsUppercase.isNotEmpty()) {
            val outOfScope = poolAlbums().filter { album -> album.uppercase() !in keepAlbumsUppercase }
            if (outOfScope.isNotEmpty()) deleteOutOfScope(outOfScope.map { it.uppercase() })
        }
        // 关闭「包含视频」:未处理的视频行移出候选池,让总数跟随开关。已处理/回收站的视频行保留。
        if (!videosEnabled) deleteOutOfVideoScope()
    }

    // ── candidate selection: strategy (random / oldest / largest) × date range ──
    @Query("SELECT * FROM photos WHERE state IN ('UNSEEN', 'SKIP') AND inTrash = 0 AND (:minDate IS NULL OR dateTaken >= :minDate) AND (:maxDate IS NULL OR dateTaken < :maxDate) ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomCandidates(limit: Int, minDate: Long?, maxDate: Long?): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE state IN ('UNSEEN', 'SKIP') AND inTrash = 0 AND (:minDate IS NULL OR dateTaken >= :minDate) AND (:maxDate IS NULL OR dateTaken < :maxDate) ORDER BY dateTaken ASC LIMIT :limit")
    suspend fun oldestCandidates(limit: Int, minDate: Long?, maxDate: Long?): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE state IN ('UNSEEN', 'SKIP') AND inTrash = 0 AND (:minDate IS NULL OR dateTaken >= :minDate) AND (:maxDate IS NULL OR dateTaken < :maxDate) ORDER BY size DESC LIMIT :limit")
    suspend fun largestCandidates(limit: Int, minDate: Long?, maxDate: Long?): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE mediaId IN (:ids) AND gone = 0")
    suspend fun byIds(ids: List<Long>): List<PhotoEntity>

    @Query("UPDATE photos SET lastShownDay = :day WHERE mediaId IN (:ids)")
    suspend fun markShown(ids: List<Long>, day: String)

    @Query("UPDATE photos SET state = :state, processedAt = :processedAt WHERE mediaId = :id")
    suspend fun updateState(id: Long, state: PhotoState, processedAt: Long?)

    @Query("SELECT * FROM photos WHERE state = 'DELETE_PENDING'")
    suspend fun pendingDeletes(): List<PhotoEntity>

    @Query("UPDATE photos SET state = 'DELETE', inTrash = 1 WHERE mediaId IN (:ids)")
    suspend fun confirmDeleted(ids: List<Long>)

    // Trash page order: most recently deleted first. processedAt is stamped when the user
    // swipes a photo into the delete flow (confirmDeleted flips inTrash right after), so it
    // is the app's "trashed at" time; IFNULL keeps hypothetically-NULL rows at the bottom
    // and dateTaken breaks ties.
    // 注意:谓词/排序与下方 [trashNow] 是双份维护(@Query 无法共享 WHERE 片段),改一处必须两处同步。
    @Query("SELECT * FROM photos WHERE inTrash = 1 AND gone = 0 ORDER BY IFNULL(processedAt, 0) DESC, dateTaken DESC")
    fun trashItems(): Flow<List<PhotoEntity>>

    // 一次性版本,谓词/排序与 [trashItems] 完全相同,两处必须保持同步(约束见上)。
    @Query("SELECT * FROM photos WHERE inTrash = 1 AND gone = 0 ORDER BY IFNULL(processedAt, 0) DESC, dateTaken DESC")
    suspend fun trashNow(): List<PhotoEntity>

    // 恢复 = 回待整理池。state 重置 UNSEEN 不会误伤别的状态:inTrash=1 只有 confirmDeleted
    // 会写,且同一句 UPDATE 就把 state 置成 'DELETE',所以走到这里的行必然已是 DELETE
    // (FAVORITE 更是全工程无人写入的枚举值,见 PhotoState 的使用面)。processedAt 有意保留
    // ——它要喂周统计与连续天数,syncExternallyRestored 也是同样的处理。
    @Query("UPDATE photos SET inTrash = 0, state = 'UNSEEN' WHERE mediaId IN (:ids)")
    suspend fun restoreFromTrash(ids: List<Long>)

    // User declined the system trash confirmation: undo the delete-pending marking so the
    // photos re-enter the pool instead of lingering as "pending but never trashed" items
    // that keep re-prompting at every session end.
    @Query("UPDATE photos SET state = 'UNSEEN', processedAt = NULL WHERE mediaId IN (:ids)")
    suspend fun revertPendingDeletes(ids: List<Long>)

    @Query("DELETE FROM photos WHERE mediaId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    // gone=0 on every "presence" count below: reconciled-away rows (deleted outside the app)
    // must stop inflating the totals, while the row itself keeps feeding history stats.
    @Query("SELECT COUNT(*) FROM photos WHERE state != 'UNSEEN' AND state != 'SKIP' AND gone = 0")
    fun processedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE state = 'KEEP' AND gone = 0")
    fun keptCount(): Flow<Int>

    // 与 [totalNow] 同谓词双份维护(@Query 无法共享 WHERE 片段),改一处必须两处同步。
    @Query("SELECT COUNT(*) FROM photos WHERE gone = 0")
    fun totalCount(): Flow<Int>

    // 一次性版本,谓词与 [totalCount] 完全相同,两处必须保持同步(约束见上)。
    @Query("SELECT COUNT(*) FROM photos WHERE gone = 0")
    suspend fun totalNow(): Int

    // Distinct local calendar days on which any photo was processed — used to compute the
    // current organizing streak.
    @Query("SELECT DISTINCT substr(date(processedAt / 1000, 'unixepoch', 'localtime'), 1, 10) FROM photos WHERE processedAt IS NOT NULL ORDER BY 1 DESC")
    fun processedDays(): Flow<List<String>>

    // Total bytes of photos currently sitting in the app's trash (i.e. space the user has
    // moved out of the gallery).
    @Query("SELECT COALESCE(SUM(size), 0) FROM photos WHERE inTrash = 1 AND gone = 0")
    fun trashBytes(): Flow<Long>

    // Photos that could be "memories" (have a taken date and are not deleted/trashed).
    @Query("SELECT * FROM photos WHERE inTrash = 0 AND gone = 0 AND dateTaken > 0 AND state != 'DELETE' ORDER BY dateTaken DESC")
    fun memoryCandidates(): Flow<List<PhotoEntity>>

    // ── reconcile (对账): sync rows that vanished from MediaStore outside the app ──

    // All live mediaIds to diff against MediaStore's existence set.
    @Query("SELECT mediaId FROM photos WHERE gone = 0")
    suspend fun activeIds(): List<Long>

    // Dead rows that were never processed carry no history (processedAt NULL): delete outright.
    @Query("DELETE FROM photos WHERE gone = 0 AND inTrash = 0 AND state IN ('UNSEEN', 'SKIP', 'DELETE_PENDING') AND mediaId IN (:ids)")
    suspend fun deleteDeadPool(ids: List<Long>): Int

    // Dead rows that WERE processed (keep / trash): mark gone so they leave the pool, totals,
    // trash page and memories, but keep processedAt for weekly stats and the streak.
    @Query("UPDATE photos SET gone = 1 WHERE gone = 0 AND mediaId IN (:ids)")
    suspend fun markGone(ids: List<Long>): Int

    @Query("SELECT mediaId FROM photos WHERE gone = 0 AND inTrash = 1")
    suspend fun trashIds(): List<Long>

    // The user restored a trash photo from the system gallery: mirror the app's own restore
    // (inTrash=0, state=UNSEEN, processedAt kept) — the caller backs out the deleted counter.
    @Query("UPDATE photos SET inTrash = 0, state = 'UNSEEN' WHERE gone = 0 AND inTrash = 1 AND mediaId IN (:ids)")
    suspend fun syncExternallyRestored(ids: List<Long>): Int

    // 对账的三段连续 DB 写(deleteDeadPool → markGone → syncExternallyRestored)整段一个事务:
    // 中途失败全部回滚,绝不留下「删了一半/标了一半」的中间态。入参由调用方在事务外备齐
    // (MediaStore 扫描与文件活性探测是慢 IO,不能占着事务);chunked(900) 分块避开 SQLite
    // IN 参数上限。deleteDeadPool 先行,markGone 只会命中余下的已处理行。
    @Transaction
    suspend fun applyReconcile(dead: List<Long>, restoredCandidates: Set<Long>): ReconcileWrites {
        var poolDeleted = 0
        var goneMarked = 0
        for (chunk in dead.chunked(900)) poolDeleted += deleteDeadPool(chunk)
        for (chunk in dead.chunked(900)) goneMarked += markGone(chunk)
        // 常规扫描(不含系统回收站)里出现的回收站行 = 在系统相册被恢复了。
        var restoredCount = 0
        for (chunk in trashIds().filter { it in restoredCandidates }.chunked(900)) {
            restoredCount += syncExternallyRestored(chunk)
        }
        return ReconcileWrites(poolDeleted, goneMarked, restoredCount)
    }

    // [applyReconcile] 的返回:三段写各命中多少行,仓库层补上 livenessDead 组装 ReconcileResult。
    data class ReconcileWrites(val poolDeleted: Int, val goneMarked: Int, val restoredCount: Int)

    // ── weekly stats ──
    data class DayCount(val day: String, val cnt: Int)

    // [start, end) window — the current week passes next Monday as :end, so any historical
    // week can reuse the same three queries (历史整理也是按周切换的).
    @Query("SELECT substr(date(processedAt / 1000, 'unixepoch', 'localtime'), 1, 10) AS day, COUNT(*) AS cnt FROM photos WHERE processedAt IS NOT NULL AND processedAt >= :start AND processedAt < :end GROUP BY day ORDER BY day")
    fun dayCountsBetween(start: Long, end: Long): Flow<List<DayCount>>

    @Query("SELECT COUNT(*) FROM photos WHERE state = 'KEEP' AND processedAt >= :start AND processedAt < :end")
    fun weekKeptBetween(start: Long, end: Long): Flow<Int>

    // Photos processed in the window that were not kept went to the trash — those bytes are "freed".
    @Query("SELECT COALESCE(SUM(size), 0) FROM photos WHERE processedAt >= :start AND processedAt < :end AND processedAt IS NOT NULL AND state != 'KEEP'")
    fun weekFreedBytesBetween(start: Long, end: Long): Flow<Long>

    // Oldest processed timestamp — the history picker's year wheel starts here.
    @Query("SELECT MIN(processedAt) FROM photos WHERE processedAt IS NOT NULL")
    suspend fun earliestProcessedAt(): Long?

    @Query("DELETE FROM photos")
    suspend fun clear()
}
