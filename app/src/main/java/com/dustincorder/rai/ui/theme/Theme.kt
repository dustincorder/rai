package com.dustincorder.rai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val RayaLightColors = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF0FF),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF006874),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8EAF0),
    onSecondaryContainer = Color(0xFF001F24),
    background = Color(0xFFF9F9FD),
    onBackground = Color(0xFF191B20),
    surface = Color.White,
    onSurface = Color(0xFF191B20),
    surfaceVariant = Color(0xFFE0E3E9),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val RayaDarkColors = darkColorScheme(
    primary = Color(0xFF4FD8E8),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF9CF0FF),
    secondary = Color(0xFF8DD5DF),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF285C64),
    onSecondaryContainer = Color(0xFFB8EAF0),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E8),
    surface = Color(0xFF191C21),
    onSurface = Color(0xFFE2E2E8),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C7CF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun RayaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) RayaDarkColors else RayaLightColors,
        content = content,
    )
}
