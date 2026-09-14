package com.dustincorder.rai.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dustincorder.rai.presentation.RayaUiState
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.ui.raya.face.RayaFace
import com.dustincorder.rai.ui.theme.RayaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RayaScreen(
    state: RayaUiState,
    onTalkClick: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    val canCancel = state.face.emotion == RayaFaceEmotion.Listening
    val buttonEnabled = !state.isBusy || canCancel

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Райя", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Голосовой ассистент",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RayaFace(
                state = state.face,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
            )
            Spacer(Modifier.height(4.dp))
            StatusChip(state)
            Spacer(Modifier.height(18.dp))
            ConversationCard(state)
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onTalkClick,
                enabled = buttonEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Outlined.MicNone, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text(
                    if (canCancel) "ОТМЕНИТЬ СЛУШАНИЕ" else "ГОВОРИТЬ",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Распознавание речи на русском языке",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Настройки") },
            text = { Text("Настройки голосовых провайдеров появятся на следующем этапе.") },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("ПОНЯТНО")
                }
            },
        )
    }
}

@Composable
private fun StatusChip(state: RayaUiState) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                state.status,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ConversationCard(state: RayaUiState) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Вы",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.userText.ifBlank { "Скажите что-нибудь, чтобы начать разговор." },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (state.face.emotion == RayaFaceEmotion.Error) "Система" else "Райя",
                    color = if (state.face.emotion == RayaFaceEmotion.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.responseText.ifBlank { "Я внимательно слушаю." },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Preview(name = "Raya screen light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun RayaScreenLightPreview() {
    RayaTheme(darkTheme = false) {
        RayaScreen(
            state = RayaUiState(),
            onTalkClick = {},
        )
    }
}

@Preview(name = "Raya screen dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RayaScreenDarkPreview() {
    RayaTheme(darkTheme = true) {
        RayaScreen(
            state = RayaUiState(),
            onTalkClick = {},
        )
    }
}
