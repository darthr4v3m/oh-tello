package io.github.darthr4v3m.ohtello.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark only, on purpose: this is a screen you look at outdoors with a drone in
 * the air, and a white background in daylight is both harder to read and worse
 * for the battery. High contrast beats matching the system theme here.
 */
private val OhTelloColors = darkColorScheme(
    primary = Color(0xFF6FD3FF),
    onPrimary = Color(0xFF00303F),
    primaryContainer = Color(0xFF1F4A5C),
    onPrimaryContainer = Color(0xFFB8E8FF),
    secondary = Color(0xFF9FCFA8),
    onSecondary = Color(0xFF0C2812),
    secondaryContainer = Color(0xFF244A2C),
    onSecondaryContainer = Color(0xFFC6ECCC),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE2E7EC),
    surface = Color(0xFF171C22),
    onSurface = Color(0xFFE2E7EC),
    surfaceVariant = Color(0xFF232A31),
    onSurfaceVariant = Color(0xFFB6C0C9),
    error = Color(0xFFFF7A70),
    onError = Color(0xFF4A0B06),
    errorContainer = Color(0xFF6B1A13),
    onErrorContainer = Color(0xFFFFD9D5),
    outline = Color(0xFF3A434C),
)

@Composable
fun OhTelloTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OhTelloColors,
        typography = Typography(),
        content = content,
    )
}
