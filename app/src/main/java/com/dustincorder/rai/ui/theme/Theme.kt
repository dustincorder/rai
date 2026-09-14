package com.dustincorder.rai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RayaColors = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    secondary = Color(0xFF675080),
    onSecondary = Color.White,
    background = Color(0xFFF9F9FD),
    onBackground = Color(0xFF191B20),
    surface = Color.White,
    onSurface = Color(0xFF191B20),
    surfaceVariant = Color(0xFFE0E3E9),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFBA1A1A),
)

@Composable
fun RayaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RayaColors,
        content = content,
    )
}
