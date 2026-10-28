package com.sdrnova.t25.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Teal = Color(0xFF4FD1C5)
val Amber = Color(0xFFF6AD55)
val Alert = Color(0xFFFC5C65)
val Good = Color(0xFF48BB78)
val Muted = Color(0xFF8A94A6)

private val colors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201D),
    secondary = Amber,
    onSecondary = Color(0xFF2B1700),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF151A21),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1E252E),
    onSurfaceVariant = Color(0xFFB4BDCA),
    error = Alert,
)

@Composable
fun SdrNovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
