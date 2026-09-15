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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.presentation.model.RayaFaceState
import com.dustincorder.rai.presentation.model.RayaGaze
import com.dustincorder.rai.ui.theme.RayaTheme
import kotlin.math.min
import kotlin.random.Random

private val EyeCyan = Color(0xFF00A8B5)

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
            .aspectRatio(1.15f),
    ) {
        drawFace(
            state = state,
            blinking = state.blinking || automaticBlink,
            gazeX = gazeX,
            gazeY = gazeY,
            idleDrift = idleDrift,
            listeningPulse = listeningPulse,
            thinkingSweep = thinkingSweep,
            speakingPulse = speakingPulse,
            errorPulse = errorPulse,
        )
    }
}

private val RayaGaze.horizontalOffset: Float
    get() = when (this) {
        RayaGaze.Center, RayaGaze.Up, RayaGaze.Down, RayaGaze.Wide -> 0f
        RayaGaze.Alert -> 0.018f
    }

private val RayaGaze.verticalOffset: Float
    get() = when (this) {
        RayaGaze.Center, RayaGaze.Alert, RayaGaze.Wide -> 0f
        RayaGaze.Up -> -0.025f
        RayaGaze.Down -> 0.025f
    }

private fun DrawScope.drawFace(
    state: RayaFaceState,
    blinking: Boolean,
    gazeX: Float,
    gazeY: Float,
    idleDrift: Float,
    listeningPulse: Float,
    thinkingSweep: Float,
    speakingPulse: Float,
    errorPulse: Float,
) {
    val width = size.width
    val height = size.height
    val eyeSize = min(width, height) * 0.14f
    val centerY = height * (0.48f + gazeY + if (state.emotion == RayaFaceEmotion.Calm) idleDrift * 0.004f else 0f)
    val color = EyeCyan
    val listeningScale = if (state.emotion == RayaFaceEmotion.Listening) 1f + listeningPulse * 0.08f else 1f
    val speakingScale = if (state.speaking) 1f + speakingPulse * 0.06f else 1f
    val shapeScale = listeningScale * speakingScale
    val eyeHeight = when (state.emotion) {
        RayaFaceEmotion.Surprised -> eyeSize * 1.35f
        RayaFaceEmotion.Happy -> eyeSize * 0.58f
        RayaFaceEmotion.Curious -> eyeSize * 1.12f
        RayaFaceEmotion.Concerned -> eyeSize * 0.62f
        RayaFaceEmotion.Error -> eyeSize * (0.72f + errorPulse * 0.18f)
        else -> eyeSize
    } * shapeScale
    val eyeWidth = when (state.emotion) {
        RayaFaceEmotion.Surprised -> eyeSize * 1.25f
        RayaFaceEmotion.Happy -> eyeSize * 1.15f
        RayaFaceEmotion.Error -> eyeSize * (0.9f + errorPulse * 0.1f)
        else -> eyeSize
    }
    val eyeAngle = when (state.emotion) {
        RayaFaceEmotion.Angry -> -13f
        RayaFaceEmotion.Concerned -> 10f
        RayaFaceEmotion.Curious -> -6f
        else -> 0f
    }
    val errorOffset = if (state.emotion == RayaFaceEmotion.Error) (errorPulse - 0.5f) * eyeSize * 0.18f else 0f

    drawEye(width * (0.36f + gazeX) - errorOffset, centerY, eyeWidth, eyeHeight, color, eyeAngle, blinking)
    drawEye(width * (0.64f + gazeX) + errorOffset, centerY, eyeWidth, eyeHeight, color, -eyeAngle, blinking)

    when (state.emotion) {
        RayaFaceEmotion.Thinking -> drawThinkingDots(width, height, color, thinkingSweep)
        RayaFaceEmotion.Surprised -> drawSurprisedMouth(width, height, color)
        RayaFaceEmotion.Angry -> drawAngryMouth(width, height, color)
        else -> Unit
    }

}

private fun DrawScope.drawEye(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    color: Color,
    angle: Float,
    blinking: Boolean,
) {
    val closedHeight = size.height * 0.012f
    withTransform({ rotate(angle, pivot = point(x, y)) }) {
        drawRect(
            color = color,
            topLeft = point(x - width / 2f, y - if (blinking) closedHeight / 2f else height / 2f),
            size = dimensions(width, if (blinking) closedHeight else height),
        )
    }
}

private fun DrawScope.drawThinkingDots(width: Float, height: Float, color: Color, sweep: Float) {
    repeat(3) { index ->
        val alpha = 0.25f + ((sweep * 3f - index).coerceIn(0f, 1f) * 0.75f)
        drawCircle(color.copy(alpha = alpha), radius = width * 0.012f, center = point(width * (0.46f + index * 0.04f), height * 0.7f))
    }
}

private fun DrawScope.drawSurprisedMouth(width: Float, height: Float, color: Color) {
    drawRect(color, point(width * 0.47f, height * 0.63f), dimensions(width * 0.06f, height * 0.1f))
}

private fun DrawScope.drawAngryMouth(width: Float, height: Float, color: Color) {
    drawLine(color, point(width * 0.44f, height * 0.69f), point(width * 0.5f, height * 0.66f), width * 0.018f, StrokeCap.Square)
    drawLine(color, point(width * 0.5f, height * 0.66f), point(width * 0.56f, height * 0.69f), width * 0.018f, StrokeCap.Square)
}

private fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
private fun dimensions(width: Float, height: Float) = androidx.compose.ui.geometry.Size(width, height)

@Preview(showBackground = true, backgroundColor = 0xFFF9F9FD)
@Composable
private fun RayaFacePreview() {
    RayaTheme {
        Box(modifier = Modifier.fillMaxWidth().widthIn(max = 390.dp)) {
            RayaFace(state = RayaFaceState(emotion = RayaFaceEmotion.Calm))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF9F9FD, name = "Angry cyan eyes")
@Composable
private fun RayaFaceAngryPreview() {
    RayaTheme {
        RayaFace(
            state = RayaFaceState(emotion = RayaFaceEmotion.Angry),
            modifier = Modifier.size(280.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF9F9FD, name = "Error no mouth")
@Composable
private fun RayaFaceErrorPreview() {
    RayaTheme {
        RayaFace(
            state = RayaFaceState(emotion = RayaFaceEmotion.Error),
            modifier = Modifier.size(280.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF9F9FD, widthDp = 480, heightDp = 1_400)
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
