package com.dustincorder.rai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dustincorder.rai.presentation.RayaUiState
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.ui.raya.face.RayaFace
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
    val canCancel = state.face.emotion == RayaFaceEmotion.Listening
    val buttonEnabled = !state.isBusy || canCancel

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .navigationBarsPadding()
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
                    text = "PRIME CORE / VOICE MODE",
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.4.sp,
                )
            }
            Text(
                text = state.status.uppercase(),
                color = statusColor(state),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        RayaFace(
            state = state.face,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
        )
        Spacer(Modifier.height(10.dp))
        StatusPill(state)
        Spacer(Modifier.height(18.dp))

        ConversationCard(state)
        Spacer(Modifier.height(18.dp))

        Button(
            onClick = onTalkClick,
            enabled = buttonEnabled,
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
                text = if (canCancel) "ОТМЕНИТЬ СЛУШАНИЕ" else "ГОВОРИТЬ",
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Распознавание: русский язык • LLM пока не подключена",
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusPill(state: RayaUiState) {
    val color = statusColor(state)
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(7.dp)
                .background(color, RoundedCornerShape(50)),
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
private fun ConversationCard(state: RayaUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Panel,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("ТЫ", color = Cyan, style = MaterialTheme.typography.labelSmall)
            Text(
                text = state.userText.ifBlank { "Нажми кнопку и скажи что-нибудь." },
                color = CyanSoft,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (state.responseText.isNotBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = Color.White.copy(alpha = 0.08f),
                )
                Text(
                    text = if (state.face.emotion == RayaFaceEmotion.Error) "СИСТЕМА" else "РАЙЯ",
                    color = if (state.face.emotion == RayaFaceEmotion.Error) Danger else Violet,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = state.responseText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

private fun statusColor(state: RayaUiState): Color = when (state.face.emotion) {
    RayaFaceEmotion.Calm -> Cyan
    RayaFaceEmotion.Listening -> CyanSoft
    RayaFaceEmotion.Thinking -> Violet
    RayaFaceEmotion.Speaking -> Cyan
    RayaFaceEmotion.Happy -> Cyan
    RayaFaceEmotion.Curious -> Violet
    RayaFaceEmotion.Concerned -> Danger
    RayaFaceEmotion.Surprised -> CyanSoft
    RayaFaceEmotion.Angry -> Danger
    RayaFaceEmotion.Error -> Danger
}
