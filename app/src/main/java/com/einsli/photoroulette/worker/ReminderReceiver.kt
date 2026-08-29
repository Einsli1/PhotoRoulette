package com.einsli.photoroulette.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 每日提醒到点后的接收器:立刻发通知,再异步重排下一天的闹钟。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return
        Log.d("ReminderScheduler", "ReminderReceiver action=${intent.action}")
        // 先同步发通知(一次 binder 调用,很快),保证用户一定看到;重排走 goAsync 异步做。
        ReminderScheduler.postNotification(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 通知已发出、今天的闹钟已消费:按 SET 语义排明天(守卫对"今天已过点"会误判为
                // 未派发而无限补发,所以这里必须 replace=true)。
                runCatching { ReminderScheduler.reschedule(context, replace = true) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** 开机 / 应用更新 / 时区与时间变化后恢复每日闹钟,不依赖用户打开 App。 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.MY_PACKAGE_REPLACED",
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.d("ReminderScheduler", "ReminderBootReceiver action=${intent.action}")
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        // 环境性恢复走守卫(replace=false),别覆盖今天仍待发的排期。
                        runCatching { ReminderScheduler.reschedule(context, replace = false) }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}