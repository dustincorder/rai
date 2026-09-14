package com.dustincorder.rai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RayaColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Void,
    secondary = Violet,
    background = Void,
    onBackground = CyanSoft,
    surface = Panel,
    onSurface = CyanSoft,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = Muted,
    error = Danger,
)

@Composable
fun RayaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RayaColors,
        content = content,
    )
}
