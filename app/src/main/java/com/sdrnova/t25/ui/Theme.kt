package com.sdrnova.t25.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Scanner-display palette: black canvas, blue-grey panels, one cyan accent, and colours that mean something. */
val Canvas = Color(0xFF000000)
val Panel = Color(0xFF1B262C)
val PanelDeep = Color(0xFF0E1519)
val CardSurface = Color(0xFF141C21)
val Hairline = Color(0xFF20282D)
val Outline = Color(0xFF37474F)
val Ink = Color(0xFFECEFF1)
val Muted = Color(0xFF8A9399)

val Cyan = Color(0xFF26C6DA)
val SkyBlue = Color(0xFF42A5F5)
val Teal = Color(0xFF26A69A)
val Violet = Color(0xFFAB47BC)

/** Running, locked, audio playing. */
val Good = Color(0xFF4CAF50)
val GoodText = Color(0xFF81C784)

/** Searching, held, encrypted. */
val Amber = Color(0xFFFFA726)

/** Errors and emergencies. */
val Alert = Color(0xFFEF5350)

private val colors = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFFB2EBF2),
    secondary = SkyBlue,
    onSecondary = Color(0xFF002F5C),
    secondaryContainer = Color(0xFF16303F),
    onSecondaryContainer = Color(0xFFCFE6F7),
    background = Canvas,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = CardSurface,
    onSurfaceVariant = Color(0xFFB0BEC5),
    surfaceContainer = Panel,
    surfaceContainerHigh = Color(0xFF223038),
    surfaceContainerHighest = Color(0xFF2A3942),
    outline = Outline,
    outlineVariant = Hairline,
    error = Alert,
)

@Composable
fun SdrNovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
