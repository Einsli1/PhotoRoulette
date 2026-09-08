package com.einsli.photoroulette.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import com.einsli.photoroulette.worker.ReminderScheduler

private val Context.settingsDataStore by preferencesDataStore("settings")
data class AppSettings(
    val dailyCount: Int = 10,
    val includeVideos: Boolean = false,
    val includeScreenshots: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val includedAlbums: List<String> = emptyList(),
    val darkMode: Int = 0, // 0=跟随系统 1=浅色 2=深色
    val photoRange: String = "all", // all | lastYear | beforeLastYear | custom
    val customRangeStart: Long = 0L, // epoch ms for the custom range start date
    val strategy: String = "random", // random | oldest | largest
)

/**
 * Lifetime cumulative stats (累计整理 / 累计删除 / 累计保留), persisted independently of the
 * photo table so they never shrink when trash items are purged or the gallery is rescanned.
 * organized = kept + deleted, mirroring the design's 累计整理 = 累计删除 + 累计保留.
 */
data class StatsCounters(
    val keptTotal: Int = 0,
    val deletedTotal: Int = 0,
) {
    val organizedTotal: Int get() = keptTotal + deletedTotal
}

class SettingsRepository(private val context: Context) {
    /** 无 UI 组件(PreviewCache 等应用级单例)需要的 ApplicationContext 入口。 */
    val appContext: Context get() = context.applicationContext
    private object Keys {
        val DAILY = intPreferencesKey("daily_count"); val VIDEO = booleanPreferencesKey("include_videos")
        val SCREENSHOTS = booleanPreferencesKey("include_screenshots"); val HOUR = intPreferencesKey("reminder_hour"); val MINUTE = intPreferencesKey("reminder_minute")
        val QUEUE_DAY = stringPreferencesKey("queue_day"); val QUEUE_IDS = stringPreferencesKey("queue_ids"); val QUEUE_POS = intPreferencesKey("queue_pos")
        val ALBUMS = stringPreferencesKey("included_albums")
        val DARK = intPreferencesKey("dark_mode")
        val PHOTO_RANGE = stringPreferencesKey("photo_range")
        val CUSTOM_RANGE_START = longPreferencesKey("custom_range_start")
        val STRATEGY = stringPreferencesKey("strategy")
        val KEPT_TOTAL = intPreferencesKey("stats_kept_total")
        val DELETED_TOTAL = intPreferencesKey("stats_deleted_total")
    }
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val albumsRaw = p[Keys.ALBUMS].orEmpty()
        val albums = if (albumsRaw.isEmpty()) emptyList() else albumsRaw.split("||")
        AppSettings(
            dailyCount = p[Keys.DAILY] ?: 10,
            includeVideos = p[Keys.VIDEO] ?: false,
            includeScreenshots = p[Keys.SCREENSHOTS] ?: true,
            reminderHour = p[Keys.HOUR] ?: 20,
            reminderMinute = p[Keys.MINUTE] ?: 0,
            includedAlbums = albums,
            darkMode = p[Keys.DARK] ?: 0,
            photoRange = p[Keys.PHOTO_RANGE] ?: "all",
            customRangeStart = p[Keys.CUSTOM_RANGE_START] ?: 0L,
            strategy = p[Keys.STRATEGY] ?: "random",
        )
    }
    // 数据层直调 worker 的重排是刻意保留的耦合:改提醒时间的入口只有 save 一处,在这里
    // 顺手重排才不漏;搬到 ViewModel 会有时序竞态(真机已复现),不要"顺手"上提。
    suspend fun save(settings: AppSettings) = context.settingsDataStore.edit { p ->
        p[Keys.DAILY] = settings.dailyCount; p[Keys.VIDEO] = settings.includeVideos; p[Keys.SCREENSHOTS] = settings.includeScreenshots
        p[Keys.HOUR] = settings.reminderHour; p[Keys.MINUTE] = settings.reminderMinute
        p[Keys.ALBUMS] = settings.includedAlbums.joinToString("||")
        p[Keys.DARK] = settings.darkMode
        p[Keys.PHOTO_RANGE] = settings.photoRange
        p[Keys.CUSTOM_RANGE_START] = settings.customRangeStart
        p[Keys.STRATEGY] = settings.strategy
    }.also { ReminderScheduler.schedule(context, settings.reminderHour, settings.reminderMinute) }
    suspend fun saveQueue(ids: List<Long>, position: Int = 0) = context.settingsDataStore.edit { p ->
        p[Keys.QUEUE_IDS] = ids.joinToString(",")
        p[Keys.QUEUE_POS] = position
    }
    suspend fun currentQueue(): Pair<List<Long>, Int> = context.settingsDataStore.data.map { p ->
        val ids = p[Keys.QUEUE_IDS].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        ids to (p[Keys.QUEUE_POS] ?: 0)
    }.let { it.first() }
    suspend fun clearQueue() = context.settingsDataStore.edit { it.remove(Keys.QUEUE_IDS); it.remove(Keys.QUEUE_DAY); it.remove(Keys.QUEUE_POS) }

    val statsCounters: Flow<StatsCounters> = context.settingsDataStore.data.map { p ->
        StatsCounters(
            keptTotal = p[Keys.KEPT_TOTAL] ?: 0,
            deletedTotal = p[Keys.DELETED_TOTAL] ?: 0,
        )
    }

    /** Adjust the lifetime counters atomically; deltas may be negative (undo / restore). */
    suspend fun updateStatsCounters(keptDelta: Int, deletedDelta: Int) = context.settingsDataStore.edit { p ->
        p[Keys.KEPT_TOTAL] = ((p[Keys.KEPT_TOTAL] ?: 0) + keptDelta).coerceAtLeast(0)
        p[Keys.DELETED_TOTAL] = ((p[Keys.DELETED_TOTAL] ?: 0) + deletedDelta).coerceAtLeast(0)
    }

    /** Full reset (used by the app's reset action) zeroes the lifetime counters too. */
    suspend fun resetStatsCounters() = context.settingsDataStore.edit { p ->
        p[Keys.KEPT_TOTAL] = 0
        p[Keys.DELETED_TOTAL] = 0
    }

    /** One-time migration: the first launch after this feature seeds the counters from the
     *  current DB state (kept / processed-kept) so existing installs don't start at zero. */
    suspend fun backfillStatsCountersIfAbsent(kept: Int, deleted: Int) = context.settingsDataStore.edit { p ->
        if (!p.contains(Keys.KEPT_TOTAL) && !p.contains(Keys.DELETED_TOTAL)) {
            p[Keys.KEPT_TOTAL] = kept
            p[Keys.DELETED_TOTAL] = deleted
        }
    }
}
