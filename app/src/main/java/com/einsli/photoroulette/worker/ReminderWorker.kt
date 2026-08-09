package com.einsli.photoroulette.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.einsli.photoroulette.R

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = "daily_reminder"
        manager.createNotificationChannel(NotificationChannel(channel, "每日照片提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("照片轮盘")
            .setContentText("今天还有照片等你整理 📷").setAutoCancel(true).build()
        manager.notify(1001, notification)
        return Result.success()
    }
}
