package com.einsli.photoroulette.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PhotoEntity::class], version = 2, exportSchema = false)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    companion object {
        fun create(context: Context): PhotoDatabase = Room.databaseBuilder(
            context, PhotoDatabase::class.java, "photo-roulette.db"
        ).fallbackToDestructiveMigration().build()
    }
}
