package com.einsli.photoroulette.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class PhotoState { UNSEEN, KEEP, DELETE_PENDING, DELETE, SKIP, FAVORITE }

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val mediaId: Long,
    val uri: String,
    val displayName: String,
    val dateTaken: Long,
    val mimeType: String,
    val album: String = "",
    val size: Long = 0,
    val duration: Long = 0,
    val state: PhotoState = PhotoState.UNSEEN,
    val lastShownDay: String? = null,
    val processedAt: Long? = null,
    val inTrash: Boolean = false,
    // 对账标记:系统相册里已被彻底删除(非回收站)的照片。gone=1 的行从候选池/总数/
    // 回收站页/回忆里隐藏,但行本身保留——processedAt 还要喂周统计与连续天数。
    @ColumnInfo(defaultValue = "0") val gone: Boolean = false
)
