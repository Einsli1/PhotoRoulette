package com.einsli.photoroulette.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<PhotoEntity>)

    // ── candidate selection: strategy (random / oldest / largest) × date range ──
    @Query("SELECT * FROM photos WHERE state IN ('UNSEEN', 'SKIP') AND inTrash = 0 AND (:minDate IS NULL OR dateTaken >= :minDate) AND (:maxDate IS NULL OR dateTaken < :maxDate) ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomCandidates(limit: Int, minDate: Long?, maxDate: Long?): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE state IN ('UNSEEN', 'SKIP') AND inTrash = 0 AND (:minDate IS NULL OR dateTaken >= :minDate) AND (:maxDate IS NULL OR dateTaken < :maxDate) ORDER BY dateTaken ASC LIMIT :limit")
    suspend fun oldestCandidates(limit: Int, minDate: Long?, maxDate: Long?): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE state IN ('UNSEEN', 'SKIP') AND inTrash = 0 AND (:minDate IS NULL OR dateTaken >= :minDate) AND (:maxDate IS NULL OR dateTaken < :maxDate) ORDER BY size DESC LIMIT :limit")
    suspend fun largestCandidates(limit: Int, minDate: Long?, maxDate: Long?): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE mediaId IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<PhotoEntity>

    @Query("UPDATE photos SET lastShownDay = :day WHERE mediaId IN (:ids)")
    suspend fun markShown(ids: List<Long>, day: String)

    @Query("UPDATE photos SET state = :state, processedAt = :processedAt WHERE mediaId = :id")
    suspend fun updateState(id: Long, state: PhotoState, processedAt: Long?)

    @Query("SELECT * FROM photos WHERE state = 'DELETE_PENDING'")
    suspend fun pendingDeletes(): List<PhotoEntity>

    @Query("UPDATE photos SET state = 'DELETE', inTrash = 1 WHERE mediaId IN (:ids)")
    suspend fun confirmDeleted(ids: List<Long>)

    @Query("SELECT * FROM photos WHERE inTrash = 1 ORDER BY dateTaken DESC")
    fun trashItems(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE inTrash = 1 ORDER BY dateTaken DESC")
    suspend fun trashNow(): List<PhotoEntity>

    @Query("UPDATE photos SET inTrash = 0, state = 'UNSEEN' WHERE mediaId IN (:ids)")
    suspend fun restoreFromTrash(ids: List<Long>)

    // User declined the system trash confirmation: undo the delete-pending marking so the
    // photos re-enter the pool instead of lingering as "pending but never trashed" items
    // that keep re-prompting at every session end.
    @Query("UPDATE photos SET state = 'UNSEEN', processedAt = NULL WHERE mediaId IN (:ids)")
    suspend fun revertPendingDeletes(ids: List<Long>)

    @Query("DELETE FROM photos WHERE mediaId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM photos WHERE state != 'UNSEEN' AND state != 'SKIP'")
    fun processedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE state = 'KEEP'")
    fun keptCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos")
    fun totalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun totalNow(): Int

    // Distinct local calendar days on which any photo was processed — used to compute the
    // current organizing streak.
    @Query("SELECT DISTINCT substr(date(processedAt / 1000, 'unixepoch', 'localtime'), 1, 10) FROM photos WHERE processedAt IS NOT NULL ORDER BY 1 DESC")
    fun processedDays(): Flow<List<String>>

    // Total bytes of photos currently sitting in the app's trash (i.e. space the user has
    // moved out of the gallery).
    @Query("SELECT COALESCE(SUM(size), 0) FROM photos WHERE inTrash = 1")
    fun trashBytes(): Flow<Long>

    // Photos that could be "memories" (have a taken date and are not deleted/trashed).
    @Query("SELECT * FROM photos WHERE inTrash = 0 AND dateTaken > 0 AND state != 'DELETE' ORDER BY dateTaken DESC")
    fun memoryCandidates(): Flow<List<PhotoEntity>>

    // ── weekly stats ──
    data class DayCount(val day: String, val cnt: Int)

    @Query("SELECT substr(date(processedAt / 1000, 'unixepoch', 'localtime'), 1, 10) AS day, COUNT(*) AS cnt FROM photos WHERE processedAt IS NOT NULL AND processedAt >= :since GROUP BY day ORDER BY day")
    fun dayCountsSince(since: Long): Flow<List<DayCount>>

    @Query("SELECT COUNT(*) FROM photos WHERE state = 'KEEP' AND processedAt >= :since")
    fun weekKept(since: Long): Flow<Int>

    // Photos processed in the window that were not kept went to the trash — those bytes are "freed".
    @Query("SELECT COALESCE(SUM(size), 0) FROM photos WHERE processedAt >= :since AND processedAt IS NOT NULL AND state != 'KEEP'")
    fun weekFreedBytes(since: Long): Flow<Long>

    @Query("DELETE FROM photos")
    suspend fun clear()
}
