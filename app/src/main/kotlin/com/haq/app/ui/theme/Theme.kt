package com.haq.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colours
val HaqGreen       = Color(0xFF1D6F42)   // mic button, active accents
val HaqGreenDim    = Color(0xFF145230)   // pressed / container
val HaqBackground  = Color(0xFF0D0D0D)   // full-screen background
val HaqSurface     = Color(0xFF1A1A1A)   // response card
val HaqOnSurface   = Color(0xFFE0E0E0)
val HaqMuted       = Color(0xFF5C5C5C)   // placeholder / status text
val HaqError       = Color(0xFFCF4444)   // error state

private val HaqDarkColors = darkColorScheme(
    primary          = HaqGreen,
    onPrimary        = Color.White,
    primaryContainer = HaqGreenDim,
    background       = HaqBackground,
    onBackground     = HaqOnSurface,
    surface          = HaqSurface,
    onSurface        = HaqOnSurface,
    surfaceVariant   = Color(0xFF222222),
    error            = HaqError,
    onError          = Color.White,
)

@Composable
fun HaqTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HaqDarkColors,
        content = content,
    )
}
