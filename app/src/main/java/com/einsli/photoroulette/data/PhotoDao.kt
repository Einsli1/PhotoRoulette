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

    @Query("SELECT * FROM photos WHERE state IN ('UNSEEN', 'SKIP') AND inTrash = 0 ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomCandidates(limit: Int): List<PhotoEntity>

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

    @Query("SELECT COUNT(*) FROM photos")
    fun totalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun totalNow(): Int

    @Query("DELETE FROM photos")
    suspend fun clear()
}
