package com.einsli.photoroulette.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PhotoEntity::class], version = 6, exportSchema = false)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    companion object {
        // 3 → 4: photos.album — relative path of the containing album. Used to keep the
        // organizing pool and the total count in sync with the album selection. Old rows get
        // '' (treated as always in scope) so nothing is deleted on upgrade.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN album TEXT NOT NULL DEFAULT ''")
            }
        }
        // 4 → 5: photos.duration — video length in ms (0 for images). Lets the UI show a
        // duration badge on video cards without querying MediaStore per cell.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN duration INTEGER NOT NULL DEFAULT 0")
            }
        }
        // 5 → 6: photos.gone — set when the file is gone from MediaStore entirely (deleted
        // outside the app / purged from the system trash). Hidden from pool/counts/trash/
        // memories but the row stays so weekly stats and the streak are never rewritten.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN gone INTEGER NOT NULL DEFAULT 0")
            }
        }
        fun create(context: Context): PhotoDatabase = Room.databaseBuilder(
            context, PhotoDatabase::class.java, "photo-roulette.db"
        ).addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).fallbackToDestructiveMigration().build()
    }
}
