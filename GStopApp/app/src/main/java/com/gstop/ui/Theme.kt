package com.gstop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = EnneagramOrange,
    onPrimary = Color.Black,
    secondary = PhraseGreen,
    onSecondary = Color.Black,
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFE8E4DE),
    surface = Color(0xFF151515),
    onSurface = Color(0xFFE8E4DE),
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFFB9B4AD),
    error = Color(0xFFFF6B5E),
    onError = Color.Black
)

@Composable
fun GStopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
