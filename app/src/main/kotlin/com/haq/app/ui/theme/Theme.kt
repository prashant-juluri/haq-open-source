package com.haq.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HaqDarkColors = darkColorScheme(
    primary          = Color(0xFF1E88E5),   // mic button blue
    onPrimary        = Color.White,
    primaryContainer = Color(0xFF1565C0),
    background       = Color(0xFF0A0A0A),
    onBackground     = Color(0xFFE0E0E0),
    surface          = Color(0xFF1A1A1A),
    onSurface        = Color(0xFFE0E0E0),
    error            = Color(0xFFE53935),   // mic active / alert red
    onError          = Color.White,
)

@Composable
fun HaqTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HaqDarkColors,
        content = content,
    )
}
