package com.einsli.photoroulette.worker

import android.app.AlarmManager
import android.app.AlarmManager.AlarmClockInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.einsli.photoroulette.AppContainer
import com.einsli.photoroulette.MainActivity
import com.einsli.photoroulette.PhotoRouletteApp
import com.einsli.photoroulette.R
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 每日提醒调度器 —— 基于 AlarmManager,而不是 WorkManager。
 *
 * 为什么不用 WorkManager:周期任务是非精确的(会被批量/延迟),国产 ROM(MIUI/HyperOS)在应用
 * 被划掉或进入 Doze 后基本不会及时执行,结果就是"只有打开 App 时才发通知"。AlarmManager 的
 * 闹钟由系统调度,应用进程被杀也能按时拉起接收器。调度降级链(按可用性):
 *  1. [AlarmManager.setExactAndAllowWhileIdle]:需要 SCHEDULE_EXACT_ALARM(Android 14+
 *     默认不授予,设置页有「提醒精确到分钟」授权入口);
 *  2. [AlarmManager.setAlarmClock]:文档号称免权限,但 Android 15+/HyperOS 上缺权限时同样
 *     抛 SecurityException,所以先试、失败再降级;
 *  3. [AlarmManager.setAndAllowWhileIdle]:无需权限、进程被杀也会触发,但窗口可长达 1 小时
 *     (MIUI 实测到点不触发就是这个原因),只有授权情况 1 才能真正准点。
 *
 * 防"到点不发"的守卫(replace=false,即 onCreate/onResume 等环境性重排时):
 *  存储上次武装的目标时间,若它落在今天且已过点 → 说明闹钟还没被派发(派发后接收器会立即换成
 *  明天的目标) → **立即补发**(now+15s),而不是被 SET 语义静默换到明天;
 *  若目标在未来 → 重新注册相同目标(无害;若用户刚授权精确闹钟还会顺势升级成准点);
 *  其余(隔天/force-stop 后重开等)→ 按设置时间正常重排。
 *
 * 每次触发后由接收器用持久化的提醒时间重新排下一天(alarm 是单次而非周期的)。
 */
object ReminderScheduler {
    const val ACTION_REMIND = "com.einsli.photoroulette.action.REMIND"
    private const val REQUEST_ALARM = 1901
    private const val REQUEST_NOTIFICATION = 1902
    private const val CHANNEL_ID = "daily_reminder"
    private const val NOTIFICATION_ID = 1001
    private const val PREFS = "reminder_schedule"
    private const val KEY_TARGET_MS = "last_target_ms"

    /**
     * 从 DataStore 重新读取提醒时间并重排每日闹钟。
     *  @param replace true = 闹钟已真正触发,消费掉了今天的排期,必须按 SET 语义排明天
     *                 (ReminderReceiver 用);
     *                 false = 环境性恢复(开机/时区变化/应用更新),走守卫。注意 HyperOS 会对
     *                 未开自启动的应用在每次进程启动时补投 BOOT_COMPLETED,若这里强制
     *                 replace=true,会把"今天已武装待发"的补发覆盖成明天。
     */
    suspend fun reschedule(context: Context, replace: Boolean = true) {
        val settings = container(context).settings.settings.first()
        schedule(context, settings.reminderHour, settings.reminderMinute, replace = replace)
    }

    /** 从 Application 上的容器取进程唯一的依赖(原先这里每次重排都 new 一个
     *  SettingsRepository,与 MainActivity 持有的实例互不相识)。闹钟/开机接收器被系统
     *  拉起时进程必先创建 Application,取容器不会落空。 */
    private fun container(context: Context): AppContainer =
        (context.applicationContext as PhotoRouletteApp).container

    /**
     * @param replace true = 用户主动改时间/闹钟已触发,直接按设置重排;
     *                false = 环境性重排(onCreate/onResume),遵循上面的守卫规则。
     */
    fun schedule(context: Context, hour: Int, minute: Int, replace: Boolean = true) {
        val nowMs = System.currentTimeMillis()
        val now = LocalDateTime.now()
        var targetMs: Long
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val armedTarget = prefs.getLong(KEY_TARGET_MS, -1L)
        val sameDayArmed = armedTarget > 0 && LocalDate.ofInstant(
            Instant.ofEpochMilli(armedTarget), ZoneId.systemDefault()
        ) == now.toLocalDate()
        if (!replace && sameDayArmed) {
            // 今天已经有武装过的闹钟:未来的就保持同一目标(不重算,防止多进程/重复 onCreate 把
            // 补发或排期换掉;若刚授权精确闹钟,同一目标的重复注册顺便升级成准点);
            // 已过点的说明没被派发(派发后存储会被接收器换成明天的目标)→ 立即补发。
            targetMs = if (armedTarget > nowMs) armedTarget else nowMs + 15_000
        } else {
            var target = now
                .withHour(hour.coerceIn(0, 23))
                .withMinute(minute.coerceIn(0, 59))
                .withSecond(0).withNano(0)
            if (!target.isAfter(now)) target = target.plusDays(1)
            targetMs = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        Log.d("ReminderScheduler", "schedule replace=$replace armed=$armedTarget sameDay=$sameDayArmed nowMs=$nowMs targetMs=$targetMs")

        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pi)
            } else {
                try {
                    val showIntent = PendingIntent.getActivity(
                        context, REQUEST_NOTIFICATION, openAppIntent(context),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    am.setAlarmClock(AlarmClockInfo(targetMs, showIntent), pi)
                } catch (e: SecurityException) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pi)
                }
            }
            prefs.edit().putLong(KEY_TARGET_MS, targetMs).apply()
        } catch (e: Exception) {
            // 兜底:注册失败不崩溃,下次打开/开机/设置变更会重试。
            Log.w("ReminderScheduler", "schedule failed", e)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }

    fun postNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "每日照片提醒", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("照片轮盘")
            .setContentText("今天还有照片等你整理 📷")
            .setColor(0xFF7A59F7.toInt())
            .setContentIntent(
                PendingIntent.getActivity(
                    context, REQUEST_NOTIFICATION, openAppIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** 点通知/点锁屏闹钟指示时打开 App 并直达整理页。 */
    fun openAppIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_REVIEW, true)
        }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_ALARM,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}