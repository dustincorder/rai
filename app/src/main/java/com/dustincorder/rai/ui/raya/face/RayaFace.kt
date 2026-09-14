package com.dustincorder.rai.ui.raya.face

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
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
    val idleDrift by motion.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_600), RepeatMode.Reverse),
        label = "idle-drift",
    )
    val pulse by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "listening-pulse",
    )
    val thinkingSweep by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_250), RepeatMode.Restart),
        label = "thinking-sweep",
    )
    val speakingFrame by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(210), RepeatMode.Reverse),
        label = "speaking-frame",
    )
    val errorPulse by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "error-pulse",
    )
    var automaticBlink by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(Random.nextLong(2_200L, 5_200L))
            automaticBlink = true
            kotlinx.coroutines.delay(105L)
            automaticBlink = false
        }
    }

    val gazeX by animateFloatAsState(
        targetValue = state.gaze.horizontalOffset,
        animationSpec = tween(420),
        label = "gaze-x",
    )
    val gazeY by animateFloatAsState(
        targetValue = state.gaze.verticalOffset,
        animationSpec = tween(420),
        label = "gaze-y",
    )
    val errorOffset = if (state.emotion == RayaFaceEmotion.Error) {
        sin(errorPulse * Math.PI * 2).toFloat() * 2.2f
    } else {
        0f
    }

    Canvas(
        modifier = modifier
            .aspectRatio(0.92f)
            .graphicsLayer {
                translationX = errorOffset
                translationY = if (state.emotion == RayaFaceEmotion.Calm) idleDrift else 0f
            },
    ) {
        drawRayaFace(
            state = state,
            blinking = state.blinking || automaticBlink,
            gazeX = gazeX,
            gazeY = gazeY,
            pulse = pulse,
            thinkingSweep = thinkingSweep,
            speakingFrame = speakingFrame,
            errorPulse = errorPulse,
        )
    }
}

private val RayaGaze.horizontalOffset: Float
    get() = when (this) {
        RayaGaze.Center -> 0f
        RayaGaze.Alert -> 0f
        RayaGaze.Up -> 0f
        RayaGaze.Down -> 0f
        RayaGaze.Wide -> 0f
    }

private val RayaGaze.verticalOffset: Float
    get() = when (this) {
        RayaGaze.Center -> 0f
        RayaGaze.Alert -> 0f
        RayaGaze.Up -> -0.025f
        RayaGaze.Down -> 0.025f
        RayaGaze.Wide -> 0f
    }

private fun DrawScope.drawRayaFace(
    state: RayaFaceState,
    blinking: Boolean,
    gazeX: Float,
    gazeY: Float,
    pulse: Float,
    thinkingSweep: Float,
    speakingFrame: Float,
    errorPulse: Float,
) {
    val width = size.width
    val height = size.height
    val metal = Color(0xFF66718D)
    val metalLight = Color(0xFFA7B2CA)
    val metalDark = Color(0xFF303A58)
    val eyeColor = when (state.emotion) {
        RayaFaceEmotion.Error, RayaFaceEmotion.Concerned -> Danger
        RayaFaceEmotion.Thinking, RayaFaceEmotion.Curious -> Violet
        else -> Cyan
    }

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.45f),
        topLeft = point(width * 0.08f, height * 0.1f),
        size = dimensions(width * 0.84f, height * 0.78f),
        cornerRadius = radius(width * 0.06f),
    )
    drawLine(
        color = metal,
        start = point(width * 0.29f, height * 0.17f),
        end = point(width * 0.23f, height * 0.04f),
        strokeWidth = width * 0.025f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = metal,
        start = point(width * 0.71f, height * 0.17f),
        end = point(width * 0.77f, height * 0.04f),
        strokeWidth = width * 0.025f,
        cap = StrokeCap.Round,
    )
    drawAntennaBlock(width * 0.19f, height * 0.01f, metalLight)
    drawAntennaBlock(width * 0.74f, height * 0.01f, metalLight)

    val antennaLift = when (state.antennaMotion) {
        RayaAntennaMotion.Resting -> 0f
        RayaAntennaMotion.Responsive -> pulse * height * 0.012f
        RayaAntennaMotion.Thinking -> sin(thinkingSweep * Math.PI * 2).toFloat() * height * 0.014f
        RayaAntennaMotion.Alert -> height * 0.018f + errorPulse * height * 0.01f
    }
    drawRect(
        color = eyeColor.copy(alpha = 0.65f),
        topLeft = point(width * 0.19f, height * 0.01f - antennaLift),
        size = dimensions(width * 0.07f, width * 0.045f),
    )
    drawRect(
        color = eyeColor.copy(alpha = 0.65f),
        topLeft = point(width * 0.74f, height * 0.01f + antennaLift),
        size = dimensions(width * 0.07f, width * 0.045f),
    )

    drawRoundRect(
        color = metalDark,
        topLeft = point(width * 0.1f, height * 0.14f),
        size = dimensions(width * 0.8f, height * 0.74f),
        cornerRadius = radius(width * 0.045f),
    )
    drawRoundRect(
        color = metal,
        topLeft = point(width * 0.13f, height * 0.17f),
        size = dimensions(width * 0.74f, height * 0.68f),
        cornerRadius = radius(width * 0.035f),
        style = Stroke(width * 0.012f),
    )
    drawSideMechanism(width, height, metalLight, pulse, state)

    drawRoundRect(
        color = Screen,
        topLeft = point(width * 0.2f, height * 0.29f),
        size = dimensions(width * 0.6f, height * 0.4f),
        cornerRadius = radius(width * 0.025f),
    )
    drawRoundRect(
        color = Color(0xFF31345F),
        topLeft = point(width * 0.2f, height * 0.29f),
        size = dimensions(width * 0.6f, height * 0.4f),
        cornerRadius = radius(width * 0.025f),
        style = Stroke(width * 0.008f),
    )
    for (line in 1..5) {
        val y = height * 0.29f + height * 0.4f * line / 6f
        drawLine(
            color = Color(0xFF4A4277).copy(alpha = 0.14f),
            start = point(width * 0.2f, y),
            end = point(width * 0.8f, y),
            strokeWidth = width * 0.003f,
        )
    }

    val eyeY = height * (0.43f + gazeY)
    val eyeHeight = when {
        blinking -> height * 0.012f
        state.emotion == RayaFaceEmotion.Listening -> height * (0.078f + pulse * 0.012f)
        state.emotion == RayaFaceEmotion.Happy -> height * 0.052f
        state.emotion == RayaFaceEmotion.Curious -> height * 0.08f
        state.emotion == RayaFaceEmotion.Concerned -> height * 0.052f
        state.emotion == RayaFaceEmotion.Error -> height * 0.06f
        else -> height * 0.065f
    }
    val leftEyeX = width * (0.35f + gazeX)
    val rightEyeX = width * (0.65f + gazeX)
    drawPixelEye(leftEyeX, eyeY, width * 0.115f, eyeHeight, eyeColor, state.emotion)
    drawPixelEye(rightEyeX, eyeY, width * 0.115f, eyeHeight, eyeColor, state.emotion)

    when (state.emotion) {
        RayaFaceEmotion.Error -> drawErrorFace(width, height, eyeColor)
        RayaFaceEmotion.Thinking -> drawThinkingMouth(width, height, eyeColor, thinkingSweep)
        RayaFaceEmotion.Speaking -> drawSpeakingMouth(width, height, eyeColor, state.mouthAmplitude, speakingFrame)
        RayaFaceEmotion.Happy -> drawHappyMouth(width, height, eyeColor)
        RayaFaceEmotion.Curious -> drawCuriousMouth(width, height, eyeColor)
        RayaFaceEmotion.Concerned -> drawConcernedMouth(width, height, eyeColor)
        else -> drawCalmMouth(width, height, eyeColor, state.emotion)
    }
    drawRect(
        color = metalLight.copy(alpha = 0.55f),
        topLeft = point(width * 0.2f, height * 0.75f),
        size = dimensions(width * 0.6f, height * 0.012f),
    )
}

private fun DrawScope.drawAntennaBlock(x: Float, y: Float, color: Color) {
    drawRect(color, point(x, y), dimensions(size.width * 0.07f, size.width * 0.045f))
}

private fun DrawScope.drawSideMechanism(
    width: Float,
    height: Float,
    color: Color,
    pulse: Float,
    state: RayaFaceState,
) {
    val glow = when (state.antennaMotion) {
        RayaAntennaMotion.Responsive -> color.copy(alpha = 0.5f + pulse * 0.4f)
        RayaAntennaMotion.Alert -> Danger.copy(alpha = 0.72f)
        else -> color
    }
    drawRoundRect(glow, point(width * 0.04f, height * 0.39f), dimensions(width * 0.09f, height * 0.15f), radius(width * 0.015f))
    drawRoundRect(glow, point(width * 0.87f, height * 0.39f), dimensions(width * 0.09f, height * 0.15f), radius(width * 0.015f))
    drawRect(Color(0xFF1A213B), point(width * 0.015f, height * 0.44f), dimensions(width * 0.04f, height * 0.05f))
    drawRect(Color(0xFF1A213B), point(width * 0.945f, height * 0.44f), dimensions(width * 0.04f, height * 0.05f))
}

private fun DrawScope.drawPixelEye(x: Float, y: Float, eyeWidth: Float, eyeHeight: Float, color: Color, emotion: RayaFaceEmotion) {
    drawRect(color, point(x - eyeWidth / 2f, y - eyeHeight / 2f), dimensions(eyeWidth, eyeHeight.coerceAtLeast(1f)))
    if (eyeHeight > 4f && emotion != RayaFaceEmotion.Error) {
        drawRect(Color.White.copy(alpha = 0.7f), point(x - eyeWidth * 0.28f, y - eyeHeight * 0.32f), dimensions(eyeWidth * 0.16f, eyeHeight * 0.22f))
    }
}

private fun DrawScope.drawCalmMouth(width: Float, height: Float, color: Color, emotion: RayaFaceEmotion) {
    drawLine(if (emotion == RayaFaceEmotion.Listening) CyanSoft else color, point(width * 0.42f, height * 0.57f), point(width * 0.58f, height * 0.57f), width * 0.012f, StrokeCap.Square)
}

private fun DrawScope.drawThinkingMouth(width: Float, height: Float, color: Color, sweep: Float) {
    repeat(3) { index ->
        val alpha = 0.28f + ((sweep * 3f - index).coerceIn(0f, 1f) * 0.72f)
        drawRect(color.copy(alpha = alpha), point(width * (0.43f + index * 0.07f), height * 0.56f), dimensions(width * 0.035f, width * 0.035f))
    }
}

private fun DrawScope.drawSpeakingMouth(width: Float, height: Float, color: Color, amplitude: Float, frame: Float) {
    val opening = height * (0.025f + amplitude.coerceIn(0f, 1f) * (0.035f + frame * 0.06f))
    drawRoundRect(color, point(width * 0.4f, height * 0.54f), dimensions(width * 0.2f, opening), radius(width * 0.015f))
}

private fun DrawScope.drawHappyMouth(width: Float, height: Float, color: Color) {
    drawLine(color, point(width * 0.41f, height * 0.555f), point(width * 0.5f, height * 0.59f), width * 0.014f, StrokeCap.Round)
    drawLine(color, point(width * 0.5f, height * 0.59f), point(width * 0.59f, height * 0.555f), width * 0.014f, StrokeCap.Round)
}

private fun DrawScope.drawCuriousMouth(width: Float, height: Float, color: Color) {
    drawLine(color, point(width * 0.43f, height * 0.57f), point(width * 0.57f, height * 0.55f), width * 0.012f, StrokeCap.Square)
}

private fun DrawScope.drawConcernedMouth(width: Float, height: Float, color: Color) {
    drawLine(color, point(width * 0.42f, height * 0.59f), point(width * 0.5f, height * 0.56f), width * 0.014f, StrokeCap.Round)
    drawLine(color, point(width * 0.5f, height * 0.56f), point(width * 0.58f, height * 0.59f), width * 0.014f, StrokeCap.Round)
}

private fun DrawScope.drawErrorFace(width: Float, height: Float, color: Color) {
    drawLine(color, point(width * 0.42f, height * 0.54f), point(width * 0.58f, height * 0.6f), width * 0.018f, StrokeCap.Square)
    drawLine(color, point(width * 0.58f, height * 0.54f), point(width * 0.42f, height * 0.6f), width * 0.018f, StrokeCap.Square)
}

private fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
private fun dimensions(width: Float, height: Float) = androidx.compose.ui.geometry.Size(width, height)
private fun radius(value: Float) = androidx.compose.ui.geometry.CornerRadius(value)

@Preview(showBackground = true, backgroundColor = 0xFF080914)
@Composable
private fun RayaFacePlaygroundPreview() {
    RayaTheme {
        Box(modifier = Modifier.fillMaxWidth().widthIn(max = 390.dp)) {
            RayaFace(state = RayaFaceState(emotion = RayaFaceEmotion.Happy))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF080914, widthDp = 480, heightDp = 1_120)
@Composable
private fun RayaFaceExpressionGridPreview() {
    RayaTheme {
        Column {
            listOf(
                RayaFaceEmotion.Calm,
                RayaFaceEmotion.Listening,
                RayaFaceEmotion.Thinking,
                RayaFaceEmotion.Speaking,
                RayaFaceEmotion.Happy,
                RayaFaceEmotion.Curious,
                RayaFaceEmotion.Concerned,
                RayaFaceEmotion.Error,
            ).chunked(2).forEach { row ->
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
