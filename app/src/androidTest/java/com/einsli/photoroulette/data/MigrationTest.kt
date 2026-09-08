package com.einsli.photoroulette.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v6→v7 迁移测试:MigrationTestHelper 从 androidTest assets(app/schemas 经 sourceSets 注入,
 * 路径 = 数据库类全名/版本.json)读 6.json 建 v6 库,跑 [PhotoDatabase.MIGRATION_6_7],再把
 * 迁移结果与 7.json 逐表校验(runMigrationsAndValidate 内置,索引名/列不符会直接失败)。
 * 6.json 是手工构造的:实体自 v6 起未动,v6→v7 只有索引差异(7.json 去掉 indices、version 改 6),
 * identityHash 无法还原 v6 真实值,不影响校验——helper 只比对 TableInfo,不比对 identity_hash。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PhotoDatabase::class.java
    )

    @Test
    fun migrate6To7_addsIndicesAndKeepsData() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            // 守卫 6.json 不被误改回带索引的版本:v6 库里必须没有 index_photos_*。
            assertEquals(0, photoIndexNames(db).size)
            // 全列显式插入(NOT NULL 列大多没有 SQL 默认值,仅 gone 有 DEFAULT 0);
            // state 存枚举名文本,含中文相册路径顺带验证索引列排序无编码问题。
            db.execSQL(
                "INSERT INTO photos (mediaId, uri, displayName, dateTaken, mimeType, album, size, " +
                    "duration, state, lastShownDay, processedAt, inTrash, gone) VALUES " +
                    "(1, 'content://media/1', 'a.jpg', 1000, 'image/jpeg', 'DCIM/Camera', 123, 0, " +
                    "'KEEP', NULL, 1000, 0, 0)"
            )
            db.execSQL(
                "INSERT INTO photos (mediaId, uri, displayName, dateTaken, mimeType, album, size, " +
                    "duration, state, lastShownDay, processedAt, inTrash, gone) VALUES " +
                    "(2, 'content://media/2', 'b.jpg', 2000, 'image/jpeg', 'Pictures/校运会/', 456, 0, " +
                    "'DELETE', NULL, 2000, 1, 0)"
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 7, true, PhotoDatabase.MIGRATION_6_7).use { db ->
            assertEquals(
                setOf(
                    "index_photos_state",
                    "index_photos_inTrash",
                    "index_photos_dateTaken",
                    "index_photos_album"
                ),
                photoIndexNames(db)
            )
            // 迁移是加索引,绝不能动行数据。
            db.query("SELECT COUNT(*) FROM photos").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM photos WHERE inTrash = 1 AND state = 'DELETE'").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
    }

    private fun photoIndexNames(db: SupportSQLiteDatabase): Set<String> {
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_photos_%'").use { c ->
            val names = mutableSetOf<String>()
            while (c.moveToNext()) names.add(c.getString(0))
            return names
        }
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
