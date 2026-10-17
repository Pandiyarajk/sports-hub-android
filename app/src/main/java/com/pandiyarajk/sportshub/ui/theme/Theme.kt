// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Status colours that stay fixed whatever the dynamic palette is, so live is always red. */
object SportColors {
    val Live = Color(0xFFE53935)
    val Win = Color(0xFF2E9E4F)
    val Draw = Color(0xFF8A8F98)
    val Loss = Color(0xFFD93025)
    val Champions = Color(0xFF1E6FE0)
    val Europa = Color(0xFFF28C28)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5ED7),
    secondary = Color(0xFF4F5B73),
    background = Color(0xFFF4F6FA),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFFB4BDD0),
    background = Color(0xFF10131A),
    surface = Color(0xFF1A1E27),
)

@Composable
fun SportsHubTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
