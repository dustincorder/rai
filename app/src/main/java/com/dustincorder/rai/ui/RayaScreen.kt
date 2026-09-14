package com.dustincorder.rai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dustincorder.rai.presentation.RayaUiState
import com.dustincorder.rai.ui.raya.face.RayaFace
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.ui.theme.Cyan
import com.dustincorder.rai.ui.theme.CyanSoft
import com.dustincorder.rai.ui.theme.Danger
import com.dustincorder.rai.ui.theme.Muted
import com.dustincorder.rai.ui.theme.Panel
import com.dustincorder.rai.ui.theme.PanelRaised
import com.dustincorder.rai.ui.theme.Violet
import com.dustincorder.rai.ui.theme.Void

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

            RayaFace(
                state = state.face,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 390.dp),
            )

            Spacer(Modifier.height(12.dp))
            StatusPill(state = state)
            Spacer(Modifier.height(18.dp))

            ConversationCard(
                userText = state.userText,
                responseText = state.responseText,
                emotion = state.face.emotion,
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
    val color = when (state.face.emotion) {
        RayaFaceEmotion.Calm -> Cyan
        RayaFaceEmotion.Listening -> CyanSoft
        RayaFaceEmotion.Thinking -> Violet
        RayaFaceEmotion.Speaking -> Cyan
        RayaFaceEmotion.Happy -> Cyan
        RayaFaceEmotion.Curious -> Violet
        RayaFaceEmotion.Concerned -> Danger
        RayaFaceEmotion.Error -> Danger
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
    emotion: RayaFaceEmotion,
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
                    text = if (emotion == RayaFaceEmotion.Error) "СИСТЕМА" else "РАЙЯ",
                    color = if (emotion == RayaFaceEmotion.Error) Danger else Violet,
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
