package com.einsli.photoroulette

import android.content.Context
import com.einsli.photoroulette.data.PhotoDatabase
import com.einsli.photoroulette.data.PhotoRepository
import com.einsli.photoroulette.data.SettingsRepository
import com.einsli.photoroulette.media.MediaScanner

/**
 * 应用级依赖容器(手写 DI,无框架):进程唯一持有 数据库 / 设置 / 媒体扫描器 / 照片仓库
 * 四个单例,挂在 [PhotoRouletteApp] 上,MainActivity 与 worker 层统一从这里取——
 * 替代原先各自 lazy new 的分散组装(MainActivity 一套、ReminderScheduler.reschedule
 * 每次重排又 new 一个 SettingsRepository,评审 中-8:DI 分散,测试无从下手)。
 *
 * 全部惰性初始化:首个取用点才真正构建(Room 开库/DataStore 读盘延后到首帧需要时)。
 * 各组件的构造签名保持原样,这里只统一「谁持有实例」;语义上的唯一差异是 MediaScanner
 * 改用 applicationContext 的 ContentResolver(与 Activity 的是同一 binder 门面,无状态差异)。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: PhotoDatabase by lazy { PhotoDatabase.create(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val mediaScanner: MediaScanner by lazy { MediaScanner(appContext.contentResolver) }
    val repository: PhotoRepository by lazy { PhotoRepository(database.photoDao(), mediaScanner, settings) }
}
