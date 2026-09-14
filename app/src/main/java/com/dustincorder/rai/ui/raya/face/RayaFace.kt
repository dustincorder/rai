package com.dustincorder.rai.ui.raya.face

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.presentation.model.RayaFaceState
import com.dustincorder.rai.presentation.model.RayaGaze
import com.dustincorder.rai.ui.theme.Cyan
import com.dustincorder.rai.ui.theme.CyanSoft
import com.dustincorder.rai.ui.theme.Danger
import com.dustincorder.rai.ui.theme.RayaTheme
import com.dustincorder.rai.ui.theme.Screen
import com.dustincorder.rai.ui.theme.Violet
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun RayaFace(
    state: RayaFaceState,
    modifier: Modifier = Modifier,
) {
    val motion = rememberInfiniteTransition(label = "raya-face-motion")
    val idleDrift by motion.animateFloat(-1f, 1f, infiniteRepeatable(tween(3_600), RepeatMode.Reverse), label = "idle-drift")
    val listeningPulse by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "listening-pulse")
    val thinkingSweep by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1_250), RepeatMode.Restart), label = "thinking-sweep")
    val speakingPulse by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "speaking-pulse")
    val errorPulse by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "error-pulse")
    var automaticBlink by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(Random.nextLong(2_200L, 5_200L))
            automaticBlink = true
            kotlinx.coroutines.delay(105L)
            automaticBlink = false
        }
    }

    val gazeX by animateFloatAsState(state.gaze.horizontalOffset, tween(420), label = "gaze-x")
    val gazeY by animateFloatAsState(state.gaze.verticalOffset, tween(420), label = "gaze-y")
    Canvas(
        modifier = modifier
            .aspectRatio(0.92f)
            .graphicsLayer {
                translationY = if (state.emotion == RayaFaceEmotion.Calm) idleDrift * 1.2f else 0f
            },
    ) {
        drawDisplayFace(
            state = state,
            blinking = state.blinking || automaticBlink,
            gazeX = gazeX,
            gazeY = gazeY,
            listeningPulse = listeningPulse,
            thinkingSweep = thinkingSweep,
            speakingPulse = speakingPulse,
            errorPulse = errorPulse,
        )
    }
}

private val RayaGaze.horizontalOffset: Float
    get() = when (this) {
        RayaGaze.Center, RayaGaze.Up, RayaGaze.Down -> 0f
        RayaGaze.Alert -> 0.018f
        RayaGaze.Wide -> 0f
    }

private val RayaGaze.verticalOffset: Float
    get() = when (this) {
        RayaGaze.Center, RayaGaze.Alert, RayaGaze.Wide -> 0f
        RayaGaze.Up -> -0.025f
        RayaGaze.Down -> 0.025f
    }

private fun DrawScope.drawDisplayFace(
    state: RayaFaceState,
    blinking: Boolean,
    gazeX: Float,
    gazeY: Float,
    listeningPulse: Float,
    thinkingSweep: Float,
    speakingPulse: Float,
    errorPulse: Float,
) {
    val width = size.width
    val height = size.height
    val eyeColor = when (state.emotion) {
        RayaFaceEmotion.Error, RayaFaceEmotion.Concerned, RayaFaceEmotion.Angry -> Danger
        RayaFaceEmotion.Thinking, RayaFaceEmotion.Curious -> Violet
        else -> Cyan
    }
    val displayColor = if (state.emotion == RayaFaceEmotion.Error) {
        Screen.copy(alpha = 0.86f + errorPulse * 0.14f)
    } else {
        Screen
    }
    val displayBorder = if (state.emotion == RayaFaceEmotion.Speaking) {
        Cyan.copy(alpha = 0.55f + speakingPulse * 0.45f)
    } else {
        Color(0xFF3A3970)
    }

    drawRoundRect(
        color = displayColor,
        topLeft = point(width * 0.07f, height * 0.08f),
        size = dimensions(width * 0.86f, height * 0.84f),
        cornerRadius = radius(width * 0.045f),
    )
    drawRoundRect(
        color = displayBorder,
        topLeft = point(width * 0.07f, height * 0.08f),
        size = dimensions(width * 0.86f, height * 0.84f),
        cornerRadius = radius(width * 0.045f),
        style = Stroke(width * 0.012f),
    )
    for (line in 1..8) {
        val y = height * 0.08f + height * 0.84f * line / 9f
        drawLine(
            color = Color(0xFF514B86).copy(alpha = 0.13f),
            start = point(width * 0.07f, y),
            end = point(width * 0.93f, y),
            strokeWidth = width * 0.0025f,
        )
    }

    val eyeY = height * (0.43f + gazeY)
    val eyePulse = if (state.emotion == RayaFaceEmotion.Speaking) 1f + speakingPulse * 0.08f else 1f
    val eyeHeight = when {
        blinking -> height * 0.012f
        state.emotion == RayaFaceEmotion.Listening -> height * (0.078f + listeningPulse * 0.012f)
        state.emotion == RayaFaceEmotion.Surprised -> height * 0.1f
        state.emotion == RayaFaceEmotion.Happy -> height * 0.052f
        state.emotion == RayaFaceEmotion.Curious -> height * 0.08f
        state.emotion == RayaFaceEmotion.Concerned -> height * 0.052f
        state.emotion == RayaFaceEmotion.Error -> height * 0.06f
        else -> height * 0.065f
    } * eyePulse
    val eyeWidth = if (state.emotion == RayaFaceEmotion.Surprised || state.gaze == RayaGaze.Wide) width * 0.13f else width * 0.115f
    drawPixelEye(width * (0.35f + gazeX), eyeY, eyeWidth, eyeHeight, eyeColor, state.emotion)
    drawPixelEye(width * (0.65f + gazeX), eyeY, eyeWidth, eyeHeight, eyeColor, state.emotion)

    when (state.emotion) {
        RayaFaceEmotion.Thinking -> drawThinkingPattern(width, height, eyeColor, thinkingSweep)
        RayaFaceEmotion.Surprised -> drawSurprisedMouth(width, height, eyeColor)
        RayaFaceEmotion.Angry -> drawAngryExpression(width, height, eyeColor)
        else -> Unit
    }
}

private fun DrawScope.drawPixelEye(x: Float, y: Float, eyeWidth: Float, eyeHeight: Float, color: Color, emotion: RayaFaceEmotion) {
    drawRect(color, point(x - eyeWidth / 2f, y - eyeHeight / 2f), dimensions(eyeWidth, eyeHeight.coerceAtLeast(1f)))
    val pupilSize = eyeWidth * 0.52f
    drawRect(
        color = Color(0xFF10132D),
        topLeft = point(x - pupilSize / 2f, y - pupilSize / 2f),
        size = dimensions(pupilSize, pupilSize),
    )
    if (emotion == RayaFaceEmotion.Angry) {
        drawRect(Color(0xFF0A0B1D), point(x - pupilSize / 2f, y - pupilSize / 2f), dimensions(pupilSize, pupilSize))
    }
}

private fun DrawScope.drawThinkingPattern(width: Float, height: Float, color: Color, sweep: Float) {
    repeat(3) { index ->
        val alpha = 0.25f + ((sweep * 3f - index).coerceIn(0f, 1f) * 0.75f)
        drawRect(color.copy(alpha = alpha), point(width * (0.43f + index * 0.07f), height * 0.57f), dimensions(width * 0.035f, width * 0.035f))
    }
}

private fun DrawScope.drawSurprisedMouth(width: Float, height: Float, color: Color) {
    drawRoundRect(color, point(width * 0.45f, height * 0.55f), dimensions(width * 0.1f, height * 0.085f), radius(width * 0.02f))
    drawRect(Color(0xFF10132D), point(width * 0.475f, height * 0.57f), dimensions(width * 0.05f, height * 0.045f))
}

private fun DrawScope.drawAngryExpression(width: Float, height: Float, color: Color) {
    drawLine(color, point(width * 0.27f, height * 0.36f), point(width * 0.43f, height * 0.4f), width * 0.018f, StrokeCap.Square)
    drawLine(color, point(width * 0.73f, height * 0.36f), point(width * 0.57f, height * 0.4f), width * 0.018f, StrokeCap.Square)
    drawLine(color, point(width * 0.42f, height * 0.6f), point(width * 0.5f, height * 0.57f), width * 0.016f, StrokeCap.Square)
    drawLine(color, point(width * 0.5f, height * 0.57f), point(width * 0.58f, height * 0.6f), width * 0.016f, StrokeCap.Square)
}

private fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
private fun dimensions(width: Float, height: Float) = androidx.compose.ui.geometry.Size(width, height)
private fun radius(value: Float) = androidx.compose.ui.geometry.CornerRadius(value)

@Preview(showBackground = true, backgroundColor = 0xFF080914)
@Composable
private fun RayaFacePreview() {
    RayaTheme {
        Box(modifier = Modifier.fillMaxWidth().widthIn(max = 390.dp)) {
            RayaFace(state = RayaFaceState(emotion = RayaFaceEmotion.Surprised))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF080914, widthDp = 480, heightDp = 1_400)
@Composable
private fun RayaFaceExpressionGridPreview() {
    RayaTheme {
        Column {
            RayaFaceEmotion.entries.chunked(2).forEach { row ->
                Row(modifier = Modifier.height(260.dp)) {
                    row.forEach { emotion ->
                        RayaFace(
                            state = RayaFaceState(emotion = emotion),
                            modifier = Modifier.size(240.dp),
                        )
                    }
                }
            }
        }
    }
}
