package com.dustincorder.rai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dustincorder.rai.presentation.RayaUiState
import com.dustincorder.rai.presentation.model.RayaAntennaMotion
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.presentation.model.RayaFaceState
import com.dustincorder.rai.presentation.model.RayaGaze
import com.dustincorder.rai.ui.raya.face.RayaFace
import com.dustincorder.rai.ui.theme.CyanSoft
import com.dustincorder.rai.ui.theme.Muted
import com.dustincorder.rai.ui.theme.Void

@Composable
fun RayaScreen(state: RayaUiState) {
    var selectedEmotion by remember { mutableStateOf(state.face.emotion) }
    var mouthAmplitude by remember { mutableStateOf(0.72f) }
    var selectedGaze by remember { mutableStateOf(RayaGaze.Center) }

    val faceState = RayaFaceState(
        emotion = selectedEmotion,
        mouthAmplitude = if (selectedEmotion == RayaFaceEmotion.Speaking) mouthAmplitude else 0f,
        gaze = selectedGaze,
        antennaMotion = when (selectedEmotion) {
            RayaFaceEmotion.Listening -> RayaAntennaMotion.Responsive
            RayaFaceEmotion.Thinking -> RayaAntennaMotion.Thinking
            RayaFaceEmotion.Error -> RayaAntennaMotion.Alert
            RayaFaceEmotion.Speaking, RayaFaceEmotion.Happy -> RayaAntennaMotion.Responsive
            else -> RayaAntennaMotion.Resting
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "РАЙЯ",
                    color = CyanSoft,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp,
                )
                Text(
                    text = "FACE GALLERY / DEMO BUILD",
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.4.sp,
                )
            }
            Text(
                text = "LOCAL",
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        RayaFace(
            state = faceState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = selectedEmotion.name,
            color = CyanSoft,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
        )
        Spacer(Modifier.height(16.dp))

        EmotionGallery(
            selected = selectedEmotion,
            onSelected = { selectedEmotion = it },
        )

        Spacer(Modifier.height(14.dp))
        GazeGallery(
            selected = selectedGaze,
            onSelected = { selectedGaze = it },
        )

        if (selectedEmotion == RayaFaceEmotion.Speaking) {
            Spacer(Modifier.height(14.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("MOUTH AMPLITUDE", color = Muted, style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = "%.2f".format(mouthAmplitude),
                        color = CyanSoft,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Slider(
                    value = mouthAmplitude,
                    onValueChange = { mouthAmplitude = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmotionGallery(
    selected: RayaFaceEmotion,
    onSelected: (RayaFaceEmotion) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RayaFaceEmotion.entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { emotion ->
                    FilterChip(
                        selected = selected == emotion,
                        onClick = { onSelected(emotion) },
                        label = { Text(emotion.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GazeGallery(
    selected: RayaGaze,
    onSelected: (RayaGaze) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(RayaGaze.Center, RayaGaze.Up, RayaGaze.Down).forEach { gaze ->
            FilterChip(
                selected = selected == gaze,
                onClick = { onSelected(gaze) },
                label = { Text(gaze.name) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
