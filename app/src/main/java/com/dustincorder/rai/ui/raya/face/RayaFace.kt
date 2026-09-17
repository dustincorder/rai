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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.presentation.model.RayaFaceState
import com.dustincorder.rai.presentation.model.RayaGaze
import com.dustincorder.rai.ui.theme.RayaTheme
import kotlin.math.min
import kotlin.random.Random

private val BlushPink = Color(0xFFE66F88)
private const val EYE_CORNER_FRACTION = 0.24f

enum class RayaFaceRenderMode {
    Normal,
    Temporary,
}

private enum class EyeShape {
    Block,
    Chevron,
    Open,
    Excited,
    Wink,
    PlayfulWink,
    Attention,
    Thinking,
    Flat,
    SmallBlock,
    ConfusedLeft,
    ConfusedRight,
    ConcernedLeft,
    ConcernedRight,
    SadLeft,
    SadRight,
    AngryLeft,
    AngryRight,
    Tired,
}

private val semanticFaceGallery = listOf(
    RayaFaceEmotion.Calm,
    RayaFaceEmotion.Happy,
    RayaFaceEmotion.Excited,
    RayaFaceEmotion.Playful,
    RayaFaceEmotion.Curious,
    RayaFaceEmotion.SemanticThinking,
    RayaFaceEmotion.Skeptical,
    RayaFaceEmotion.Confused,
    RayaFaceEmotion.Concerned,
    RayaFaceEmotion.Sad,
    RayaFaceEmotion.Embarrassed,
    RayaFaceEmotion.Surprised,
    RayaFaceEmotion.Angry,
    RayaFaceEmotion.Annoyed,
    RayaFaceEmotion.Tired,
)

@Composable
fun RayaFace(
    state: RayaFaceState,
    modifier: Modifier = Modifier,
    renderMode: RayaFaceRenderMode = RayaFaceRenderMode.Normal,
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
    val eyeColor = MaterialTheme.colorScheme.primary
    val expressionLift by animateFloatAsState(
        targetValue = if (state.emotion in listOf(RayaFaceEmotion.Happy, RayaFaceEmotion.Excited, RayaFaceEmotion.Playful)) -0.025f else 0f,
        animationSpec = tween(360),
        label = "expression-lift",
    )
    Canvas(
        modifier = modifier
            .aspectRatio(1.15f),
    ) {
        drawFace(
            state = state,
            color = eyeColor,
            renderMode = renderMode,
            blinking = state.blinking || automaticBlink,
            gazeX = gazeX,
            gazeY = gazeY + expressionLift,
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
        RayaGaze.Side -> 0.045f
    }

private val RayaGaze.verticalOffset: Float
    get() = when (this) {
        RayaGaze.Center, RayaGaze.Alert, RayaGaze.Side, RayaGaze.Wide -> 0f
        RayaGaze.Up -> -0.025f
        RayaGaze.Down -> 0.025f
    }

private fun DrawScope.drawFace(
    state: RayaFaceState,
    color: Color,
    renderMode: RayaFaceRenderMode,
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
    val listeningScale = if (state.emotion == RayaFaceEmotion.Listening) 1f + listeningPulse * 0.08f else 1f
    val speakingScale = if (state.speaking) 1f + speakingPulse * 0.06f else 1f
    val shapeScale = listeningScale * speakingScale
    val eyeHeight = when (state.emotion) {
        RayaFaceEmotion.Surprised -> eyeSize * 1.35f
        RayaFaceEmotion.Happy -> eyeSize * 1.05f
        RayaFaceEmotion.Excited -> eyeSize * 1.25f
        RayaFaceEmotion.Playful -> eyeSize * 1.0f
        RayaFaceEmotion.Curious -> eyeSize * 0.82f
        RayaFaceEmotion.SemanticThinking -> eyeSize * 0.78f
        RayaFaceEmotion.Skeptical -> eyeSize * 0.92f
        RayaFaceEmotion.Confused -> eyeSize * 1.0f
        RayaFaceEmotion.Concerned -> eyeSize * 1.0f
        RayaFaceEmotion.Sad -> eyeSize * 0.92f
        RayaFaceEmotion.Embarrassed -> eyeSize * 0.72f
        RayaFaceEmotion.Annoyed -> eyeSize * 0.62f
        RayaFaceEmotion.Tired -> eyeSize * 0.46f
        RayaFaceEmotion.Angry -> eyeSize * 1.0f
        RayaFaceEmotion.Error -> eyeSize * (0.72f + errorPulse * 0.18f)
        else -> eyeSize
    } * shapeScale
    val eyeWidth = when (state.emotion) {
        RayaFaceEmotion.Surprised -> eyeSize * 1.25f
        RayaFaceEmotion.Happy -> eyeSize * 1.18f
        RayaFaceEmotion.Excited -> eyeSize * 1.35f
        RayaFaceEmotion.Playful -> eyeSize * 1.12f
        RayaFaceEmotion.Curious -> eyeSize * 1.12f
        RayaFaceEmotion.SemanticThinking -> eyeSize * 1.08f
        RayaFaceEmotion.Skeptical -> eyeSize * 1.18f
        RayaFaceEmotion.Confused -> eyeSize * 1.08f
        RayaFaceEmotion.Concerned -> eyeSize * 1.04f
        RayaFaceEmotion.Sad -> eyeSize * 1.08f
        RayaFaceEmotion.Embarrassed -> eyeSize * 0.92f
        RayaFaceEmotion.Annoyed -> eyeSize * 1.22f
        RayaFaceEmotion.Tired -> eyeSize * 1.12f
        RayaFaceEmotion.Error -> eyeSize * (0.9f + errorPulse * 0.1f)
        else -> eyeSize
    }
    val errorOffset = if (state.emotion == RayaFaceEmotion.Error) (errorPulse - 0.5f) * eyeSize * 0.18f else 0f
    val eyeY = centerY + when (state.emotion) {
        RayaFaceEmotion.Excited -> -height * 0.012f
        RayaFaceEmotion.SemanticThinking -> height * 0.018f
        else -> 0f
    }
    val (leftShape, rightShape) = when (state.emotion) {
        RayaFaceEmotion.Happy -> EyeShape.Chevron to EyeShape.Chevron
        RayaFaceEmotion.Excited -> EyeShape.Excited to EyeShape.Excited
        RayaFaceEmotion.Playful -> EyeShape.Open to EyeShape.Wink
        RayaFaceEmotion.Curious -> EyeShape.Flat to EyeShape.Block
        RayaFaceEmotion.SemanticThinking -> EyeShape.Thinking to EyeShape.Thinking
        RayaFaceEmotion.Skeptical -> EyeShape.Attention to EyeShape.Attention
        RayaFaceEmotion.Confused -> EyeShape.Open to EyeShape.SmallBlock
        RayaFaceEmotion.Concerned -> EyeShape.ConcernedLeft to EyeShape.ConcernedRight
        RayaFaceEmotion.Sad -> EyeShape.SadLeft to EyeShape.SadRight
        RayaFaceEmotion.Embarrassed -> EyeShape.Wink to EyeShape.Wink
        RayaFaceEmotion.Angry -> EyeShape.AngryLeft to EyeShape.AngryRight
        RayaFaceEmotion.Annoyed -> EyeShape.Flat to EyeShape.Wink
        RayaFaceEmotion.Tired -> EyeShape.Tired to EyeShape.Tired
        RayaFaceEmotion.Surprised -> EyeShape.Open to EyeShape.Open
        else -> EyeShape.Block to EyeShape.Block
    }

    val leftEyeX = width * (0.36f + gazeX) - errorOffset
    val rightEyeX = width * (0.64f + gazeX) + errorOffset
    drawEye(leftEyeX, eyeY, eyeWidth, eyeHeight, color, leftShape, blinking, renderMode)
    drawEye(rightEyeX, eyeY, eyeWidth, eyeHeight, color, rightShape, blinking, renderMode)

    when (state.emotion) {
        RayaFaceEmotion.Thinking -> drawThinkingDots(width, height, color, thinkingSweep)
        RayaFaceEmotion.Confused -> drawConfusedMouth(width, height, color)
        RayaFaceEmotion.Embarrassed -> drawEmbarrassmentMarks(leftEyeX, rightEyeX, eyeY, eyeWidth, width)
        RayaFaceEmotion.Surprised -> drawSurprisedMouth(width, height, color)
        RayaFaceEmotion.Angry -> drawAngryMouth(width, height, color)
        RayaFaceEmotion.Annoyed -> drawAnnoyedMouth(width, height, color)
        RayaFaceEmotion.Tired -> drawTiredMouth(width, height, color)
        else -> Unit
    }

}

private fun DrawScope.drawEye(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    color: Color,
    shape: EyeShape,
    blinking: Boolean,
    renderMode: RayaFaceRenderMode,
) {
    val closedHeight = size.height * 0.012f
    if (blinking) {
        drawRoundRect(
            color = color,
            topLeft = point(x - width / 2f, y - closedHeight / 2f),
            size = dimensions(width, closedHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(closedHeight / 2f),
        )
        return
    }
    val left = x - width / 2f
    val right = x + width / 2f
    val top = y - height / 2f
    val bottom = y + height / 2f
    val thick = height * 0.28f
    fun roundedRect(centerX: Float, centerY: Float, rectWidth: Float, rectHeight: Float) {
        drawRoundRect(
            color = color,
            topLeft = point(centerX - rectWidth / 2f, centerY - rectHeight / 2f),
            size = dimensions(rectWidth, rectHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                min(rectHeight * EYE_CORNER_FRACTION, rectHeight / 2f),
            ),
            style = if (renderMode == RayaFaceRenderMode.Temporary) Stroke(size.height * 0.012f) else androidx.compose.ui.graphics.drawscope.Fill,
        )
    }
    fun path(points: List<Pair<Float, Float>>) = Path().apply {
        moveTo(points.first().first, points.first().second)
        points.drop(1).forEach { lineTo(it.first, it.second) }
        close()
    }
    val pathStyle = if (renderMode == RayaFaceRenderMode.Temporary) Stroke(size.height * 0.012f) else androidx.compose.ui.graphics.drawscope.Fill
    when (shape) {
        EyeShape.Block,
        EyeShape.Open,
        -> roundedRect(x, y, width, height)
        EyeShape.SmallBlock -> roundedRect(x, y, width * 0.6f, height * 0.6f)
        EyeShape.Excited -> roundedRect(x, y, width, height)
        EyeShape.Flat -> roundedRect(x, y, width, thick)
        EyeShape.Wink -> roundedRect(x, y, width * 0.72f, thick)
        EyeShape.PlayfulWink -> drawPath(
            path(listOf(
                (left + width * 0.12f) to (y + thick * 0.1f),
                (right - width * 0.08f) to (y - thick * 0.55f),
                right to (y + thick * 0.15f),
                left to (y + thick * 0.8f),
            )),
            color = color,
            style = pathStyle,
        )
        EyeShape.Tired -> roundedRect(x, y, width, thick)
        EyeShape.Chevron -> drawPath(
            path(listOf(
                left to bottom,
                x to top,
                right to bottom,
                (right - width * 0.18f) to bottom,
                x to (top + height * 0.32f),
                (left + width * 0.18f) to bottom,
            )),
            color = color,
            style = pathStyle,
        )
        EyeShape.Attention -> drawPath(
            path(listOf(left to (top + thick), (left + width * 0.2f) to top, right to top, right to bottom, left to bottom)),
            color = color,
            style = pathStyle,
        )
        EyeShape.Thinking -> roundedRect(x + width * 0.09f, y - height * 0.0f, width * 0.82f, height * 0.4f)
        EyeShape.ConfusedLeft -> roundedRect(x + width * 0.0f, y - height * 0.05f, width * 0.84f, height * 0.62f)
        EyeShape.ConfusedRight -> roundedRect(x + width * 0.0f, y + height * 0.05f, width * 0.84f, height * 0.62f)
        EyeShape.ConcernedLeft -> drawPath(
            path(listOf(left to (top + height * 0.1f), right to top, right to bottom, left to bottom)),
            color = color,
            style = pathStyle,
        )
        EyeShape.ConcernedRight -> drawPath(
            path(listOf(left to top, right to (top + height * 0.1f), right to bottom, left to bottom)),
            color = color,
            style = pathStyle,
        )
        EyeShape.SadLeft -> drawPath(
            path(listOf(left to (top + height * 0.2f), right to top, right to bottom, left to (bottom - thick))),
            color = color,
            style = pathStyle,
        )
        EyeShape.SadRight -> drawPath(
            path(listOf(left to top, right to (top + height * 0.2f), right to (bottom - thick), left to bottom)),
            color = color,
            style = pathStyle,
        )
        EyeShape.AngryLeft -> drawPath(
            path(listOf(left to top, right to (top + height * 0.3f), right to bottom, left to (bottom - thick))),
            color = color,
            style = pathStyle,
        )
        EyeShape.AngryRight -> drawPath(
            path(listOf(left to (top + height * 0.3f), right to top, right to (bottom - thick), left to bottom)),
            color = color,
            style = pathStyle,
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
    drawRect(color, point(width * 0.47f, height * 0.63f), dimensions(width * 0.06f, height * 0.11f))
}

private fun DrawScope.drawAngryMouth(width: Float, height: Float, color: Color) {
    drawLine(
        color,
        point(width * 0.465f, height * 0.68f),
        point(width * 0.535f, height * 0.68f),
        width * 0.014f,
        StrokeCap.Square,
    )
}

private fun DrawScope.drawEmbarrassmentMarks(
    leftEyeX: Float,
    rightEyeX: Float,
    eyeY: Float,
    eyeWidth: Float,
    faceWidth: Float,
) {
    repeat(3) { index ->
        val shift = index * faceWidth * 0.014f
        drawLine(
            BlushPink,
            point(leftEyeX - eyeWidth * 0.68f + shift, eyeY + faceWidth * 0.045f),
            point(leftEyeX - eyeWidth * 0.58f + shift, eyeY + faceWidth * 0.075f),
            faceWidth * 0.007f,
            StrokeCap.Square,
        )
        drawLine(
            BlushPink,
            point(rightEyeX + eyeWidth * 0.68f - shift, eyeY + faceWidth * 0.045f),
            point(rightEyeX + eyeWidth * 0.58f - shift, eyeY + faceWidth * 0.075f),
            faceWidth * 0.007f,
            StrokeCap.Square,
        )
    }
}

private fun DrawScope.drawConfusedMouth(width: Float, height: Float, color: Color) {
    drawLine(
        color,
        point(width * 0.485f, height * 0.68f),
        point(width * 0.525f, height * 0.68f),
        width * 0.009f,
        StrokeCap.Square,
    )
}

private fun DrawScope.drawAnnoyedMouth(width: Float, height: Float, color: Color) {
    drawLine(
        color,
        point(width * 0.47f, height * 0.68f),
        point(width * 0.53f, height * 0.68f),
        width * 0.012f,
        StrokeCap.Square,
    )
}

private fun DrawScope.drawTiredMouth(width: Float, height: Float, color: Color) {
    drawLine(
        color,
        point(width * 0.48f, height * 0.69f),
        point(width * 0.52f, height * 0.69f),
        width * 0.009f,
        StrokeCap.Square,
    )
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

@Preview(showBackground = true, backgroundColor = 0xFFF9F9FD, widthDp = 480, heightDp = 2_100)
@Composable
private fun RayaFaceExpressionGridPreview() {
    RayaTheme {
        Column {
            semanticFaceGallery.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.forEach { emotion ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            RayaFace(
                                state = RayaFaceState(emotion = emotion),
                                modifier = Modifier.size(220.dp),
                            )
                            androidx.compose.material3.Text(emotion.name)
                        }
                    }
                }
            }
        }
    }
}
