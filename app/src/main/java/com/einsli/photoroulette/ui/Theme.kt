package com.einsli.photoroulette.ui

import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ── Fallback palette (used when dynamic color is unavailable or user overrides) ──
// Purple / lavender brand: the app's primary color is a soft purple everywhere.
private val LightFallback = lightColorScheme(
    primary = Color(0xFF6F5BE0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9E1FD),
    onPrimaryContainer = Color(0xFF251A55),
    secondary = Color(0xFF6A5E8F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E0FF),
    onSecondaryContainer = Color(0xFF251A48),
    tertiary = Color(0xFF9B5E87),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8ED),
    onTertiaryContainer = Color(0xFF3D162F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E0F0),
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C4D4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFECF5),
    surfaceContainerHigh = Color(0xFFE9E6EF),
    surfaceContainerHighest = Color(0xFFE4E0E9),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFB3A0FF),
    onPrimary = Color(0xFF2E1F66),
    primaryContainer = Color(0xFF4A3780),
    onPrimaryContainer = Color(0xFFE9E1FD),
    secondary = Color(0xFFCBC2DB),
    onSecondary = Color(0xFF332D43),
    secondaryContainer = Color(0xFF4A435A),
    onSecondaryContainer = Color(0xFFE9E0FF),
    tertiary = Color(0xFFEFB8D9),
    onTertiary = Color(0xFF552B45),
    tertiaryContainer = Color(0xFF6F415C),
    onTertiaryContainer = Color(0xFFFFD8ED),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF14161D),
    onBackground = Color(0xFFE4E2E9),
    surface = Color(0xFF14161D),
    onSurface = Color(0xFFE4E2E9),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C4D4),
    outline = Color(0xFF918F99),
    outlineVariant = Color(0xFF47464F),
    surfaceContainerLowest = Color(0xFF0F1117),
    surfaceContainerLow = Color(0xFF1C1E25),
    surfaceContainer = Color(0xFF20222A),
    surfaceContainerHigh = Color(0xFF2A2C35),
    surfaceContainerHighest = Color(0xFF353741),
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
