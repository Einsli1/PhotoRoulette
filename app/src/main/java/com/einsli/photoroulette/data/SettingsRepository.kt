package com.einsli.photoroulette.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import com.einsli.photoroulette.worker.ReminderScheduler

private val Context.settingsDataStore by preferencesDataStore("settings")
data class AppSettings(val dailyCount: Int = 10, val includeVideos: Boolean = false, val includeScreenshots: Boolean = true, val reminderHour: Int = 20, val reminderMinute: Int = 0, val includedAlbums: List<String> = emptyList())

class SettingsRepository(private val context: Context) {
    private object Keys {
        val DAILY = intPreferencesKey("daily_count"); val VIDEO = booleanPreferencesKey("include_videos")
        val SCREENSHOTS = booleanPreferencesKey("include_screenshots"); val HOUR = intPreferencesKey("reminder_hour"); val MINUTE = intPreferencesKey("reminder_minute")
        val QUEUE_DAY = stringPreferencesKey("queue_day"); val QUEUE_IDS = stringPreferencesKey("queue_ids")
        val ALBUMS = stringPreferencesKey("included_albums")
    }
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val albumsRaw = p[Keys.ALBUMS].orEmpty()
        val albums = if (albumsRaw.isEmpty()) emptyList() else albumsRaw.split("||")
        AppSettings(p[Keys.DAILY] ?: 10, p[Keys.VIDEO] ?: false, p[Keys.SCREENSHOTS] ?: true, p[Keys.HOUR] ?: 20, p[Keys.MINUTE] ?: 0, albums)
    }
    suspend fun save(settings: AppSettings) = context.settingsDataStore.edit { p ->
        p[Keys.DAILY] = settings.dailyCount; p[Keys.VIDEO] = settings.includeVideos; p[Keys.SCREENSHOTS] = settings.includeScreenshots
        p[Keys.HOUR] = settings.reminderHour; p[Keys.MINUTE] = settings.reminderMinute
        p[Keys.ALBUMS] = settings.includedAlbums.joinToString("||")
    }.also { ReminderScheduler.schedule(context, settings.reminderHour, settings.reminderMinute) }
    suspend fun saveQueue(ids: List<Long>) = context.settingsDataStore.edit { p -> p[Keys.QUEUE_IDS] = ids.joinToString(",") }
    suspend fun currentQueue(): List<Long> = context.settingsDataStore.data.map { p ->
        p[Keys.QUEUE_IDS].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
    }.let { it.first() }
    suspend fun clearQueue() = context.settingsDataStore.edit { it.remove(Keys.QUEUE_IDS); it.remove(Keys.QUEUE_DAY) }
}
