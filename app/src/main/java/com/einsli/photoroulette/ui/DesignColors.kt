package com.einsli.photoroulette.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Shared design palette replicating the main.png look.
 * Picks light or dark variants based on the active Material theme
 * (which already follows the app's dark-mode setting).
 *
 * Brand rules:
 *  - primary accent is lavender/purple everywhere
 *  - green (success) only for keep / completion states
 *  - red (danger) only for delete / destructive actions
 *  - stat badges use low-saturation containers
 */
data class DesignColors(
    val isDark: Boolean,        // current theme is dark
    val pageBg: Color,          // page background
    val ink: Color,             // primary text
    val slate: Color,           // secondary text
    val labelGray: Color,       // stat / caption labels
    val card: Color,            // main card background (lavender)
    val cardSoft: Color,        // softer card (memory)
    val accent: Color,          // accent purple (progress fill, badges, hero button)
    val accentText: Color,      // purple text / links
    val track: Color,           // progress track
    val white: Color,           // photo placeholder / thumbnail backing
    val badgeStreak: Color,     // 连续整理 badge (purple)
    val badgeStreakIcon: Color,
    val badgeSpace: Color,      // 释放空间 badge (teal)
    val badgeSpaceIcon: Color,
    val badgeKeep: Color,       // 保留照片 badge (soft pink)
    val badgeKeepIcon: Color,
    val memoryDate: Color,
    val memoryCount: Color,
    val placeholderIcon: Color,
    val navBar: Color,          // bottom navigation bar background
    val success: Color,         // green — keep / success actions
    val successContainer: Color,
    val danger: Color,          // red — delete / destructive actions
    val dangerContainer: Color, // low-saturation tonal container for danger
    val onDangerContainer: Color,
)

private val Light = DesignColors(
    isDark = false,
    pageBg = Color(0xFFF7F8FC),
    ink = Color(0xFF171B31),
    slate = Color(0xFF858DA0),
    labelGray = Color(0xFF9CA0B3),
    card = Color(0xFFEBE6FC),
    cardSoft = Color(0xFFF1ECFD),
    accent = Color(0xFF7A59F7),
    accentText = Color(0xFF6F5BE0),
    track = Color(0xFFE2E2EC),
    white = Color(0xFFFFFFFF),
    badgeStreak = Color(0xFFEFEAFD),
    badgeStreakIcon = Color(0xFF6F5BE0),
    badgeSpace = Color(0xFFE0F2F1),
    badgeSpaceIcon = Color(0xFF00897B),
    badgeKeep = Color(0xFFF9EAF4),
    badgeKeepIcon = Color(0xFFC24E8A),
    memoryDate = Color(0xFF656581),
    memoryCount = Color(0xFF5E5E76),
    placeholderIcon = Color(0xFFD9D3F5),
    navBar = Color(0xFFFBFBFD),
    success = Color(0xFF2E9E63),
    successContainer = Color(0xFFD9F2E4),
    danger = Color(0xFFB3261E),
    dangerContainer = Color(0xFFF9DEDC),
    onDangerContainer = Color(0xFF8C1D18),
)

private val Dark = DesignColors(
    isDark = true,
    pageBg = Color(0xFF14161D),
    ink = Color(0xFFE6E5F2),
    slate = Color(0xFFA7ABC0),
    labelGray = Color(0xFF8B90A5),
    card = Color(0xFF242735),
    cardSoft = Color(0xFF1E212E),
    accent = Color(0xFFA78BFA),
    accentText = Color(0xFFB49BFE),
    track = Color(0xFF353A4C),
    white = Color(0xFF2B2F3D),
    badgeStreak = Color(0xFF2A2440),
    badgeStreakIcon = Color(0xFF9C8BF8),
    badgeSpace = Color(0xFF10312E),
    badgeSpaceIcon = Color(0xFF4DB6AC),
    badgeKeep = Color(0xFF34212E),
    badgeKeepIcon = Color(0xFFDB8AB5),
    memoryDate = Color(0xFF9A9DB2),
    memoryCount = Color(0xFF8B8FA3),
    placeholderIcon = Color(0xFF3A3E52),
    navBar = Color(0xFF1A1D26),
    success = Color(0xFF4DCF9A),
    successContainer = Color(0xFF1B4A33),
    danger = Color(0xFFFF7A7A),
    dangerContainer = Color(0xFF3A2428),
    onDangerContainer = Color(0xFFFF8A8A),
)

@Composable
fun designColors(): DesignColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Dark else Light
