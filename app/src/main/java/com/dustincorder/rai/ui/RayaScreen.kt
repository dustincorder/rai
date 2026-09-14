package com.dustincorder.rai.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import com.dustincorder.rai.presentation.RayaUiState
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.ui.raya.face.RayaFace
import com.dustincorder.rai.ui.theme.RayaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RayaScreen(
    state: RayaUiState,
    onTalkClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onClearConversation: () -> Unit,
) {
    val canCancel = state.face.emotion == RayaFaceEmotion.Listening
    val buttonEnabled = !state.isBusy || canCancel
    val canClear = state.conversation.isNotEmpty() && !state.isBusy
    var showClearDialog by remember { mutableStateOf(false) }

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
                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = canClear,
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Очистить диалог")
                    }
                    IconButton(onClick = onSettingsClick) {
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RayaFace(
                state = state.face,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
            )
            Spacer(Modifier.height(4.dp))
            StatusChip(state)
            if (state.errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                ErrorBanner(state.errorMessage)
            }
            Spacer(Modifier.height(18.dp))
            ConversationArea(
                state = state,
                modifier = Modifier.weight(1f, fill = true),
            )
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
                "Язык разговора можно изменить в настройках",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Очистить диалог?") },
            text = { Text("Текущая история разговора будет удалена.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearConversation()
                    showClearDialog = false
                }) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun ConversationArea(
    state: RayaUiState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val messages = state.conversation
    val listening = state.face.emotion == RayaFaceEmotion.Listening
    val showTransient = listening && state.userText.isNotBlank() &&
        messages.lastOrNull()?.text != state.userText

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (messages.isEmpty() && !showTransient) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Начните разговор с Райей.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(messages) { _, message ->
            MessageBubble(message)
        }
        if (showTransient) {
            item(key = "transient") {
                MessageBubble(
                    ConversationMessage(ConversationRole.User, state.userText),
                    transient = true,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = true),
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ConversationMessage,
    transient: Boolean = false,
) {
    val isUser = message.role == ConversationRole.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .alpha(if (transient) 0.6f else 1f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Text(
                if (isUser) "Вы" else "Райя",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isUser) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.height(3.dp))
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp,
                ),
                color = if (isUser) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (isUser) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            ) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(state: RayaUiState) {
    val isError = state.face.emotion == RayaFaceEmotion.Error
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
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

@Preview(name = "Raya screen light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun RayaScreenLightPreview() {
    RayaTheme(darkTheme = false) {
        RayaScreen(
            state = RayaUiState(),
            onTalkClick = {},
            onSettingsClick = {},
            onClearConversation = {},
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
            onSettingsClick = {},
            onClearConversation = {},
        )
    }
}
