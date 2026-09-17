package com.dustincorder.rai.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object RayaSpacing {
    val Screen = 20.dp
    val Section = 16.dp
    val Compact = 8.dp
}

object RayaShapes {
    val Surface = RoundedCornerShape(24.dp)
    val Control = RoundedCornerShape(16.dp)
}

@Composable
fun RayaSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RayaShapes.Surface,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        content = { Column(modifier = Modifier.padding(RayaSpacing.Section), content = content) },
    )
}

@Composable
fun RayaSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(RayaSpacing.Compact)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
fun RayaPrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RayaShapes.Control,
        contentPadding = PaddingValues(vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) { Text(text) }
}

@Composable
fun RayaChoiceRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(RayaSpacing.Compact), content = { content() })
}
