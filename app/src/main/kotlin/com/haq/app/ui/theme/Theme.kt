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

// Avatar palette — one colour per profile, derived deterministically from the
// profile name so the same name always gets the same colour across sessions.
// Chosen to be distinct, readable with white text, and consistent with the dark theme.
private val AvatarPalette = listOf(
    Color(0xFF1D6F42),   // forest green  (HaqGreen)
    Color(0xFF1565C0),   // deep blue
    Color(0xFF6A1B9A),   // purple
    Color(0xFF00838F),   // teal
    Color(0xFFAD1457),   // rose
    Color(0xFF4E342E),   // brown
    Color(0xFF00695C),   // dark teal
    Color(0xFF283593),   // indigo
)

/** Returns a consistent avatar background colour for [name] from the palette. */
fun avatarColor(name: String): Color =
    AvatarPalette[kotlin.math.abs(name.trim().lowercase().hashCode()) % AvatarPalette.size]

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
