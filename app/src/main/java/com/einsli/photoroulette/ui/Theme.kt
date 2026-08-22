package com.einsli.photoroulette.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Fixed brand palette (light.png / dark.png look) ──
// Light mode: blue accent on a near-white page; dark mode: teal accent on deep navy.
private val LightFallback = lightColorScheme(
    primary = Color(0xFF3F82EE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDF2FE),
    onPrimaryContainer = Color(0xFF0D1530),
    secondary = Color(0xFF7A859D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9EFFB),
    onSecondaryContainer = Color(0xFF0D1530),
    tertiary = Color(0xFF009866),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8F8F1),
    onTertiaryContainer = Color(0xFF0D1530),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9FAFE),
    onBackground = Color(0xFF0D1530),
    surface = Color(0xFFF9FAFE),
    onSurface = Color(0xFF0D1530),
    surfaceVariant = Color(0xFFE4E9F4),
    onSurfaceVariant = Color(0xFF475063),
    outline = Color(0xFF787F92),
    outlineVariant = Color(0xFFC7CEDD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5FA),
    surfaceContainer = Color(0xFFEDF0F7),
    surfaceContainerHigh = Color(0xFFE8EBF2),
    surfaceContainerHighest = Color(0xFFE2E6EF),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFF27949B),
    onPrimary = Color(0xFF061D1E),
    primaryContainer = Color(0xFF1A2332),
    onPrimaryContainer = Color(0xFF7FE0DB),
    secondary = Color(0xFFAEB4B8),
    onSecondary = Color(0xFF23292B),
    secondaryContainer = Color(0xFF232A2E),
    onSecondaryContainer = Color(0xFFC7CDD0),
    tertiary = Color(0xFF4DCF9A),
    onTertiary = Color(0xFF073122),
    tertiaryContainer = Color(0xFF1B4A33),
    onTertiaryContainer = Color(0xFF9FE8C2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E121D),
    onBackground = Color(0xFFF0F4F3),
    surface = Color(0xFF0E121D),
    onSurface = Color(0xFFF0F4F3),
    surfaceVariant = Color(0xFF2A2F3C),
    onSurfaceVariant = Color(0xFFC0C6CE),
    outline = Color(0xFF8B90A5),
    outlineVariant = Color(0xFF3A4150),
    surfaceContainerLowest = Color(0xFF090C14),
    surfaceContainerLow = Color(0xFF141926),
    surfaceContainer = Color(0xFF171D29),
    surfaceContainerHigh = Color(0xFF1C2331),
    surfaceContainerHighest = Color(0xFF222A38),
)

@Composable fun PhotoRouletteTheme(
    dark: Boolean,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    // The brand palette is fixed (light.png / dark.png); wallpaper-based dynamic color
    // is intentionally not applied so the app always matches the reference design.
    val colorScheme = if (dark) DarkFallback else LightFallback
    MaterialTheme(colorScheme = colorScheme, content = content)
}
