package com.einsli.photoroulette.ui

/**
 * 容量格式化（4.69GB 样式），Home 与统计页共用。规则（舍入/单位/进位阈值）一经锁定不可
 * 单独调整：各页面展示依赖同一输出，改动会同时波及所有调用点。
 * 名字与 App.kt 的私有 formatBytes 错开：同包内同名声明会报 Conflicting overloads，
 * 而 App.kt 不可改动，共享版只能换名。
 */
internal fun formatCapacity(b: Long): String {
    val gb = b / 1_073_741_824.0
    val mb = b / 1_048_576.0
    val kb = b / 1024.0
    return when {
        gb >= 1 -> String.format("%.1fGB", gb)
        mb >= 1 -> String.format("%.0fMB", mb)
        kb >= 1 -> String.format("%.0fKB", kb)
        else -> "0B"
    }
}
