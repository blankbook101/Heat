package com.heat.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val AuroraDark = listOf(Color(0xFF5EF0C8), Color(0xFF4DB8FF), Color(0xFFB39DFF), Color(0xFFFF8FD8))
val AuroraLight = listOf(Color(0xFF00B894), Color(0xFF2D8CFF), Color(0xFF7B5CFF), Color(0xFFE0409A))

@Composable
fun auroraColors(): List<Color> =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) AuroraDark else AuroraLight

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5EF0C8),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF0F3B4A),
    onPrimaryContainer = Color(0xFFBFFBEA),
    secondary = Color(0xFFB39DFF),
    onSecondary = Color(0xFF2A1670),
    tertiary = Color(0xFFFF8FD8),
    background = Color(0xFF0A0F1E),
    onBackground = Color(0xFFE8EEFF),
    surface = Color(0xFF0F1630),
    onSurface = Color(0xFFE8EEFF),
    surfaceVariant = Color(0xFF18223F),
    onSurfaceVariant = Color(0xFFA9B6D8),
    outline = Color(0xFF3A4A78),
    error = Color(0xFFFF6B7A)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF007A66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF6EC),
    onPrimaryContainer = Color(0xFF00382B),
    secondary = Color(0xFF6A4FD6),
    tertiary = Color(0xFFC2307F),
    background = Color(0xFFF3F6FC),
    onBackground = Color(0xFF0F1630),
    surface = Color.White,
    onSurface = Color(0xFF0F1630),
    surfaceVariant = Color(0xFFE4EAF7),
    onSurfaceVariant = Color(0xFF4A5675),
    outline = Color(0xFFB6C2DC),
    error = Color(0xFFB3261E)
)

/** mode: 0 = system, 1 = light, 2 = dark */
@Composable
fun HeatTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as Activity).window
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = Typography(), content = content)
}
