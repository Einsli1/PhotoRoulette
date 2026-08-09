package com.einsli.photoroulette.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
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
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5DF),
    onSurfaceVariant = Color(0xFF3F4946),
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF191C1B),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF6F7976),
)

private val Dark = darkColorScheme(
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
    surface = Color(0xFF191C1B),
    onSurface = Color(0xFFE0E3E0),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C4),
    background = Color(0xFF191C1B),
    onBackground = Color(0xFFE0E3E0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF89938F),
)

@Composable fun PhotoRouletteTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
