package com.einsli.photoroulette.worker

import android.content.Context
import androidx.work.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    fun schedule(context: Context, hour: Int, minute: Int) {
        var target = LocalDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(LocalDateTime.now())) target = target.plusDays(1)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(LocalDateTime.now(), target)).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily_photo_reminder", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
