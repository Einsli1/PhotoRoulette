package com.einsli.photoroulette.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PhotoState { UNSEEN, KEEP, DELETE_PENDING, DELETE, SKIP, FAVORITE }

// v7 加的四个索引(state / inTrash / dateTaken / album):候选池(策略×范围)、统计、回收站
// 列表都按这几列过滤或排序,没有索引时每次建队列/进统计页都是全表扫描。索引名必须保持
// Room 生成约定 index_<表>_<列>,MIGRATION_6_7 与 app/schemas 导出的 schema 都依赖这一点。
@Entity(
    tableName = "photos",
    indices = [Index("state"), Index("inTrash"), Index("dateTaken"), Index("album")]
)
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
