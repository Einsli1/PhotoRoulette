package com.einsli.photoroulette.data

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
    val state: PhotoState = PhotoState.UNSEEN,
    val lastShownDay: String? = null,
    val processedAt: Long? = null,
    val inTrash: Boolean = false
)
