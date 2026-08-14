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

class SettingsRepository(private val context: Context) {
    private object Keys {
        val DAILY = intPreferencesKey("daily_count"); val VIDEO = booleanPreferencesKey("include_videos")
        val SCREENSHOTS = booleanPreferencesKey("include_screenshots"); val HOUR = intPreferencesKey("reminder_hour"); val MINUTE = intPreferencesKey("reminder_minute")
        val QUEUE_DAY = stringPreferencesKey("queue_day"); val QUEUE_IDS = stringPreferencesKey("queue_ids"); val QUEUE_POS = intPreferencesKey("queue_pos")
        val ALBUMS = stringPreferencesKey("included_albums")
        val DARK = intPreferencesKey("dark_mode")
        val PHOTO_RANGE = stringPreferencesKey("photo_range")
        val CUSTOM_RANGE_START = longPreferencesKey("custom_range_start")
        val STRATEGY = stringPreferencesKey("strategy")
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
}
