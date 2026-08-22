package com.einsli.photoroulette.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Shared design palette replicating the light.png / dark.png look.
 * Picks light or dark variants based on the active Material theme
 * (which already follows the app's dark-mode setting).
 *
 * Brand rules:
 *  - light mode: blue accent (#3F82EE family), near-white page (#F9FAFE)
 *  - dark mode: teal accent (#27949B family), deep navy page (#0E121D)
 *  - green (success) only for keep / completion states
 *  - red (danger) only for delete / destructive actions
 *  - stat badges use soft containers: blue (streak), green (space), pink (keep)
 */
data class DesignColors(
    val isDark: Boolean,        // current theme is dark
    val pageBg: Color,          // page background
    val ink: Color,             // primary text
    val slate: Color,           // secondary text
    val labelGray: Color,       // stat / caption labels
    val card: Color,            // main card background
    val cardSoft: Color,        // softer card (progress / stats)
    val accent: Color,          // accent (progress fill, badges, hero button)
    val accentText: Color,      // accent text / links
    val track: Color,           // progress track
    val white: Color,           // photo placeholder / thumbnail backing
    val badgeStreak: Color,     // 连续整理 badge (blue)
    val badgeStreakIcon: Color,
    val badgeSpace: Color,      // 释放空间 badge (green)
    val badgeSpaceIcon: Color,
    val badgeKeep: Color,       // 保留照片 badge (soft pink)
    val badgeKeepIcon: Color,
    val memoryDate: Color,
    val memoryCount: Color,
    val placeholderIcon: Color,
    val navBar: Color,          // bottom navigation bar background
    val navIndicator: Color,    // bottom nav selected pill
    val success: Color,         // green — keep / success actions
    val successContainer: Color,
    val danger: Color,          // red — delete / destructive actions
    val dangerContainer: Color, // low-saturation tonal container for danger
    val onDangerContainer: Color,
)

private val Light = DesignColors(
    isDark = false,
    pageBg = Color(0xFFF9FAFE),
    ink = Color(0xFF0D1530),
    slate = Color(0xFF7A859D),
    labelGray = Color(0xFF9CA0B3),
    card = Color(0xFFFEFEFE),
    cardSoft = Color(0xFFF2F4F9),
    accent = Color(0xFF3F82EE),
    accentText = Color(0xFF3F82EE),
    track = Color(0xFFE2E6EF),
    white = Color(0xFFFFFFFF),
    badgeStreak = Color(0xFFEDF2FE),
    badgeStreakIcon = Color(0xFF3F83F5),
    badgeSpace = Color(0xFFE8F8F1),
    badgeSpaceIcon = Color(0xFF009866),
    badgeKeep = Color(0xFFFDECF1),
    badgeKeepIcon = Color(0xFFF05F75),
    memoryDate = Color(0xFF7A859D),
    memoryCount = Color(0xFF8B92A1),
    placeholderIcon = Color(0xFFB9C6E8),
    navBar = Color(0xFFFEFEFE),
    navIndicator = Color(0xFFEDF2FE),
    success = Color(0xFF2E9E63),
    successContainer = Color(0xFFD9F2E4),
    danger = Color(0xFFB3261E),
    dangerContainer = Color(0xFFF9DEDC),
    onDangerContainer = Color(0xFF8C1D18),
)

private val Dark = DesignColors(
    isDark = true,
    pageBg = Color(0xFF0E121D),
    ink = Color(0xFFF0F4F3),
    slate = Color(0xFFAEB4B8),
    labelGray = Color(0xFF8B90A5),
    card = Color(0xFF161C28),
    cardSoft = Color(0xFF171D29),
    accent = Color(0xFF27949B),
    accentText = Color(0xFF3FB3AB),
    track = Color(0xFF2A2F3C),
    white = Color(0xFF202632),
    badgeStreak = Color(0xFF1A2332),
    badgeStreakIcon = Color(0xFF4EA2FE),
    badgeSpace = Color(0xFF213726),
    badgeSpaceIcon = Color(0xFF83C058),
    badgeKeep = Color(0xFF2D202A),
    badgeKeepIcon = Color(0xFFEB6773),
    memoryDate = Color(0xFF9A9DB2),
    memoryCount = Color(0xFF8B8FA3),
    placeholderIcon = Color(0xFF3A4A5C),
    navBar = Color(0xFF131925),
    navIndicator = Color(0xFF1A2230),
    success = Color(0xFF4DCF9A),
    successContainer = Color(0xFF1B4A33),
    danger = Color(0xFFFF7A7A),
    dangerContainer = Color(0xFF3A2428),
    onDangerContainer = Color(0xFFFF8A8A),
)

@Composable
fun designColors(): DesignColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Dark else Light
