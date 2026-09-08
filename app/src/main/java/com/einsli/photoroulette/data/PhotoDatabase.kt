package com.einsli.photoroulette.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PhotoEntity::class], version = 7, exportSchema = true)
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
        // 6 → 7: photos 建四个索引(state / inTrash / dateTaken / album),定义见 PhotoEntity。
        // 索引名必须用 Room 的生成约定 index_<表>_<列>,否则与 app/schemas/7.json 对不上,
        // 迁移测试的 schema 校验会挂。internal:MigrationTest 直接引用,避免测试里复写一份
        // SQL 造成两处漂移。
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_state` ON `photos` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_inTrash` ON `photos` (`inTrash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_dateTaken` ON `photos` (`dateTaken`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_album` ON `photos` (`album`)")
            }
        }
        fun create(context: Context): PhotoDatabase = Room.databaseBuilder(
            context, PhotoDatabase::class.java, "photo-roulette.db"
        ).addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            // v1/v2 老库没有任何显式迁移路径(3 起才有),只能破坏性重建;v3+ 一律走显式迁移,
            // 绝不 fallback——漏写一条迁移就静默清库,统计页的全部历史等于被抹掉(AGENTS.md 坑 8)。
            .fallbackToDestructiveMigrationFrom(1, 2).build()
    }
}
