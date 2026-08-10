package com.einsli.photoroulette.ui

import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ── Fallback palette (used when dynamic color is unavailable or user overrides) ──
private val LightFallback = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF7FF0DD),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E1),
    onSecondaryContainer = Color(0xFF051F1C),
    tertiary = Color(0xFF426277),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC7E4FF),
    onTertiaryContainer = Color(0xFF001E2F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5DF),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBFC9C4),
    surfaceContainerLowest = Color(0xFFF5F7F3),
    surfaceContainerLow = Color(0xFFEFF1ED),
    surfaceContainer = Color(0xFFE9EBE7),
    surfaceContainerHigh = Color(0xFFE3E5E1),
    surfaceContainerHighest = Color(0xFFDEE0DC),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFF64D4C2),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005146),
    onPrimaryContainer = Color(0xFF7FF0DD),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B46),
    onSecondaryContainer = Color(0xFFCCE8E1),
    tertiary = Color(0xFFB6CDE0),
    onTertiary = Color(0xFF203444),
    tertiaryContainer = Color(0xFF2F495C),
    onTertiaryContainer = Color(0xFFC7E4FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1B),
    onBackground = Color(0xFFE0E3E0),
    surface = Color(0xFF191C1B),
    onSurface = Color(0xFFE0E3E0),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C4),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color(0xFF0E1211),
    surfaceContainerLow = Color(0xFF161A19),
    surfaceContainer = Color(0xFF1A1E1D),
    surfaceContainerHigh = Color(0xFF252928),
    surfaceContainerHighest = Color(0xFF303433),
)

/** Returns true when the device supports dynamic wallpaper-based color extraction (Android 12+). */
private val supportsDynamicColor get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable fun PhotoRouletteTheme(
    dark: Boolean,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && supportsDynamicColor -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkFallback
        else -> LightFallback
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

