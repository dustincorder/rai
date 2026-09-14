package com.dustincorder.rai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dustincorder.rai.presentation.RayaEmotion
import com.dustincorder.rai.presentation.RayaUiState
import com.dustincorder.rai.ui.theme.Cyan
import com.dustincorder.rai.ui.theme.CyanSoft
import com.dustincorder.rai.ui.theme.Danger
import com.dustincorder.rai.ui.theme.Muted
import com.dustincorder.rai.ui.theme.Panel
import com.dustincorder.rai.ui.theme.PanelRaised
import com.dustincorder.rai.ui.theme.Screen
import com.dustincorder.rai.ui.theme.Violet
import com.dustincorder.rai.ui.theme.Void

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RayaScreen(
    state: RayaUiState,
    onTalkClick: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Void,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, start = 22.dp, end = 14.dp),
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
                        text = "PRIME CORE / DEMO BUILD",
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.4.sp,
                    )
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Настройки",
                        tint = Muted,
                    )
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = state.emotion,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
                label = "raya-face-transition",
            ) { emotion ->
                RayaFace(
                    emotion = emotion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 390.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            StatusPill(state = state)
            Spacer(Modifier.height(18.dp))

            ConversationCard(
                userText = state.userText,
                responseText = state.responseText,
                emotion = state.emotion,
            )

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onTalkClick,
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cyan,
                    contentColor = Void,
                    disabledContainerColor = PanelRaised,
                    disabledContentColor = Muted,
                ),
            ) {
                Icon(Icons.Outlined.MicNone, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text(
                    text = if (state.isBusy) "ДЕМО ВЫПОЛНЯЕТСЯ" else "НАЧАТЬ РАЗГОВОР",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Пока работает только локальная демонстрация",
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Настройки") },
            text = { Text("Раздел подключений и поведения Райи появится позже.") },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("ПОНЯТНО")
                }
            },
        )
    }
}

@Composable
private fun StatusPill(state: RayaUiState) {
    val color = when (state.emotion) {
        RayaEmotion.Calm -> Cyan
        RayaEmotion.Listening -> CyanSoft
        RayaEmotion.Thinking -> Violet
        RayaEmotion.Speaking -> Cyan
        RayaEmotion.Error -> Danger
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Text(
            text = state.status.uppercase(),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        )
    }
}

@Composable
private fun ConversationCard(
    userText: String,
    responseText: String,
    emotion: RayaEmotion,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "ПОСЛЕДНИЙ ДИАЛОГ",
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(text = "ТЫ", color = Cyan, style = MaterialTheme.typography.labelSmall)
            Text(
                text = userText,
                color = CyanSoft,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (responseText.isNotBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = Color.White.copy(alpha = 0.08f),
                )
                Text(
                    text = if (emotion == RayaEmotion.Error) "СИСТЕМА" else "РАЙЯ",
                    color = if (emotion == RayaEmotion.Error) Danger else Violet,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = responseText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun RayaFace(
    emotion: RayaEmotion,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "raya-face-motion")
    val listeningPulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "listening-pulse",
    )
    val thinkingSweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_250), RepeatMode.Restart),
        label = "thinking-sweep",
    )
    val speakingFrame by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(210), RepeatMode.Reverse),
        label = "speaking-frame",
    )
    val errorJitter by infinite.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(85), RepeatMode.Reverse),
        label = "error-jitter",
    )
    var isBlinking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3_200L)
            isBlinking = true
            kotlinx.coroutines.delay(110L)
            isBlinking = false
        }
    }

    Canvas(
        modifier = modifier
            .aspectRatio(0.92f)
            .graphicsLayer {
                translationX = if (emotion == RayaEmotion.Error) errorJitter else 0f
            },
    ) {
        drawRayaFace(
            emotion = emotion,
            blink = isBlinking && emotion != RayaEmotion.Error,
            listeningPulse = listeningPulse,
            thinkingSweep = thinkingSweep,
            speakingFrame = speakingFrame,
        )
    }
}

private fun DrawScope.drawRayaFace(
    emotion: RayaEmotion,
    blink: Boolean,
    listeningPulse: Float,
    thinkingSweep: Float,
    speakingFrame: Float,
) {
    val width = size.width
    val height = size.height
    val metal = Color(0xFF66718D)
    val metalLight = Color(0xFFA7B2CA)
    val metalDark = Color(0xFF303A58)
    val eyeColor = when (emotion) {
        RayaEmotion.Error -> Danger
        RayaEmotion.Thinking -> Violet
        else -> Cyan
    }

    // Mechanical silhouette: ears, side brackets, and a heavy square shell.
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.45f),
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.08f, height * 0.1f),
        size = androidx.compose.ui.geometry.Size(width * 0.84f, height * 0.78f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.06f),
    )
    drawLine(
        color = metal,
        start = androidx.compose.ui.geometry.Offset(width * 0.29f, height * 0.17f),
        end = androidx.compose.ui.geometry.Offset(width * 0.23f, height * 0.04f),
        strokeWidth = width * 0.025f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = metal,
        start = androidx.compose.ui.geometry.Offset(width * 0.71f, height * 0.17f),
        end = androidx.compose.ui.geometry.Offset(width * 0.77f, height * 0.04f),
        strokeWidth = width * 0.025f,
        cap = StrokeCap.Round,
    )
    drawAntennaBlock(width * 0.19f, height * 0.01f, metalLight)
    drawAntennaBlock(width * 0.74f, height * 0.01f, metalLight)

    drawRoundRect(
        color = metalDark,
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.1f, height * 0.14f),
        size = androidx.compose.ui.geometry.Size(width * 0.8f, height * 0.74f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.045f),
    )
    drawRoundRect(
        color = metal,
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.13f, height * 0.17f),
        size = androidx.compose.ui.geometry.Size(width * 0.74f, height * 0.68f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.035f),
        style = Stroke(width * 0.012f),
    )

    drawSideMechanism(width, height, metalLight, listeningPulse, emotion)

    // Dark violet face screen.
    drawRoundRect(
        color = Screen,
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.29f),
        size = androidx.compose.ui.geometry.Size(width * 0.6f, height * 0.4f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.025f),
    )
    drawRoundRect(
        color = Color(0xFF31345F),
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.29f),
        size = androidx.compose.ui.geometry.Size(width * 0.6f, height * 0.4f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.025f),
        style = Stroke(width * 0.008f),
    )

    val screenLeft = width * 0.2f
    val screenTop = height * 0.29f
    val screenWidth = width * 0.6f
    val screenHeight = height * 0.4f
    for (line in 1..5) {
        val y = screenTop + screenHeight * line / 6f
        drawLine(
            color = Color(0xFF4A4277).copy(alpha = 0.14f),
            start = androidx.compose.ui.geometry.Offset(screenLeft, y),
            end = androidx.compose.ui.geometry.Offset(screenLeft + screenWidth, y),
            strokeWidth = width * 0.003f,
        )
    }

    val eyeHeight = when {
        blink -> height * 0.012f
        emotion == RayaEmotion.Listening -> height * (0.078f + listeningPulse * 0.012f)
        else -> height * 0.065f
    }
    drawPixelEye(width * 0.35f, height * 0.43f, width * 0.115f, eyeHeight, eyeColor)
    drawPixelEye(width * 0.65f, height * 0.43f, width * 0.115f, eyeHeight, eyeColor)

    when (emotion) {
        RayaEmotion.Error -> drawErrorMouth(width, height, eyeColor)
        RayaEmotion.Thinking -> drawThinkingMouth(width, height, eyeColor, thinkingSweep)
        RayaEmotion.Speaking -> drawSpeakingMouth(width, height, eyeColor, speakingFrame)
        else -> drawCalmMouth(width, height, eyeColor, emotion)
    }

    drawRect(
        color = metalLight.copy(alpha = 0.55f),
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.75f),
        size = androidx.compose.ui.geometry.Size(width * 0.6f, height * 0.012f),
    )
}

private fun DrawScope.drawAntennaBlock(x: Float, y: Float, color: Color) {
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(x, y),
        size = androidx.compose.ui.geometry.Size(size.width * 0.07f, size.width * 0.045f),
    )
}

private fun DrawScope.drawSideMechanism(
    width: Float,
    height: Float,
    color: Color,
    pulse: Float,
    emotion: RayaEmotion,
) {
    val glow = if (emotion == RayaEmotion.Listening) color.copy(alpha = 0.5f + pulse * 0.4f) else color
    drawRoundRect(
        color = glow,
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.04f, height * 0.39f),
        size = androidx.compose.ui.geometry.Size(width * 0.09f, height * 0.15f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.015f),
    )
    drawRoundRect(
        color = glow,
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.87f, height * 0.39f),
        size = androidx.compose.ui.geometry.Size(width * 0.09f, height * 0.15f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.015f),
    )
    drawRect(
        color = Color(0xFF1A213B),
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.015f, height * 0.44f),
        size = androidx.compose.ui.geometry.Size(width * 0.04f, height * 0.05f),
    )
    drawRect(
        color = Color(0xFF1A213B),
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.945f, height * 0.44f),
        size = androidx.compose.ui.geometry.Size(width * 0.04f, height * 0.05f),
    )
}

private fun DrawScope.drawPixelEye(x: Float, y: Float, eyeWidth: Float, eyeHeight: Float, color: Color) {
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(x - eyeWidth / 2f, y - eyeHeight / 2f),
        size = androidx.compose.ui.geometry.Size(eyeWidth, eyeHeight.coerceAtLeast(1f)),
    )
    if (eyeHeight > 4f) {
        drawRect(
            color = Color.White.copy(alpha = 0.7f),
            topLeft = androidx.compose.ui.geometry.Offset(x - eyeWidth * 0.28f, y - eyeHeight * 0.32f),
            size = androidx.compose.ui.geometry.Size(eyeWidth * 0.16f, eyeHeight * 0.22f),
        )
    }
}

private fun DrawScope.drawCalmMouth(width: Float, height: Float, color: Color, emotion: RayaEmotion) {
    val mouthColor = if (emotion == RayaEmotion.Listening) CyanSoft else color
    drawLine(
        color = mouthColor,
        start = androidx.compose.ui.geometry.Offset(width * 0.42f, height * 0.57f),
        end = androidx.compose.ui.geometry.Offset(width * 0.58f, height * 0.57f),
        strokeWidth = width * 0.012f,
        cap = StrokeCap.Square,
    )
}

private fun DrawScope.drawThinkingMouth(width: Float, height: Float, color: Color, sweep: Float) {
    repeat(3) { index ->
        val alpha = 0.28f + (((sweep * 3f - index).coerceIn(0f, 1f)) * 0.72f)
        drawRect(
            color = color.copy(alpha = alpha),
            topLeft = androidx.compose.ui.geometry.Offset(width * (0.43f + index * 0.07f), height * 0.56f),
            size = androidx.compose.ui.geometry.Size(width * 0.035f, width * 0.035f),
        )
    }
}

private fun DrawScope.drawSpeakingMouth(width: Float, height: Float, color: Color, frame: Float) {
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(width * 0.4f, height * (0.54f - frame * 0.01f)),
        size = androidx.compose.ui.geometry.Size(width * 0.2f, height * (0.035f + frame * 0.07f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.015f),
    )
}

private fun DrawScope.drawErrorMouth(width: Float, height: Float, color: Color) {
    drawLine(
        color = color,
        start = androidx.compose.ui.geometry.Offset(width * 0.42f, height * 0.54f),
        end = androidx.compose.ui.geometry.Offset(width * 0.58f, height * 0.6f),
        strokeWidth = width * 0.018f,
        cap = StrokeCap.Square,
    )
    drawLine(
        color = color,
        start = androidx.compose.ui.geometry.Offset(width * 0.58f, height * 0.54f),
        end = androidx.compose.ui.geometry.Offset(width * 0.42f, height * 0.6f),
        strokeWidth = width * 0.018f,
        cap = StrokeCap.Square,
    )
}
