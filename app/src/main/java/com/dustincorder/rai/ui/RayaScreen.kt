package com.dustincorder.rai.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dustincorder.rai.R
import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import com.dustincorder.rai.domain.InteractionMode
import com.dustincorder.rai.domain.RayaErrorCode
import com.dustincorder.rai.domain.RayaNoticeCode
import com.dustincorder.rai.presentation.RayaUiState
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.ui.raya.face.RayaFace
import com.dustincorder.rai.ui.theme.RayaTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect

private val FACE_SCRIM_HEIGHT_DP = 60.dp
private val EMPTY_CONVERSATION_TOP_PADDING_DP = 360.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RayaScreen(
    state: RayaUiState,
    onSubmitText: (String) -> Unit,
    onVoiceChatClick: () -> Unit,
    onEndVoiceSession: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onInterruptSpeech: () -> Unit,
    onSettingsClick: () -> Unit,
    onClearConversation: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val canClear = state.conversation.isNotEmpty() &&
        !state.voiceSessionActive &&
        !state.isBusy
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.assistant_name), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.assistant_subtitle),
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
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.clear_conversation))
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            ) {
                ConversationArea(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                )
                FaceHeader(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
            }
            BottomControls(
                state = state,
                draft = draft,
                onDraftChange = { draft = it },
                onSubmitText = {
                    onSubmitText(draft)
                    draft = ""
                },
                onVoiceChatClick = onVoiceChatClick,
                onEndVoiceSession = onEndVoiceSession,
                onToggleMicrophone = onToggleMicrophone,
                onInterruptSpeech = onInterruptSpeech,
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_conversation_title)) },
            text = { Text(stringResource(R.string.clear_conversation_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearConversation()
                    showClearDialog = false
                }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun FaceHeader(
    state: RayaUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                ErrorBanner(state.errorMessage, state.errorCode)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FACE_SCRIM_HEIGHT_DP)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to MaterialTheme.colorScheme.background,
                            1f to MaterialTheme.colorScheme.background.copy(alpha = 0f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun BottomControls(
    state: RayaUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmitText: () -> Unit,
    onVoiceChatClick: () -> Unit,
    onEndVoiceSession: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onInterruptSpeech: () -> Unit,
) {
    if (!state.voiceSessionActive) {
        TextComposer(
            draft = draft,
            onDraftChange = onDraftChange,
            onSubmitText = onSubmitText,
            onVoiceChatClick = onVoiceChatClick,
            busy = state.isBusy,
        )
    } else {
        VoiceControls(
            state = state,
            onEndVoiceSession = onEndVoiceSession,
            onToggleMicrophone = onToggleMicrophone,
            onInterruptSpeech = onInterruptSpeech,
        )
    }
}

@Composable
private fun TextComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmitText: () -> Unit,
    onVoiceChatClick: () -> Unit,
    busy: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 1.dp,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text(stringResource(R.string.message_placeholder)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    enabled = !busy,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        if (!busy && draft.isNotBlank()) onSubmitText()
                    }),
                )
                IconButton(
                    onClick = if (draft.isBlank()) onVoiceChatClick else onSubmitText,
                    enabled = !busy,
                ) {
                    if (draft.isBlank()) {
                        Icon(Icons.Outlined.MicNone, contentDescription = stringResource(R.string.voice_chat))
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.Send,
                            contentDescription = stringResource(R.string.send_message),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceControls(
    state: RayaUiState,
    onEndVoiceSession: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onInterruptSpeech: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 1.dp,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f, fill = true)) {
                    Text(stringResource(R.string.voice_chat), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(state.statusResId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isSpeaking) {
                    IconButton(onClick = onInterruptSpeech) {
                        Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.stop_speech))
                    }
                }
                IconButton(onClick = onToggleMicrophone) {
                    Icon(
                        if (state.microphoneEnabled) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                        contentDescription = if (state.microphoneEnabled) {
                            stringResource(R.string.mic_off)
                        } else {
                            stringResource(R.string.mic_on)
                        },
                    )
                }
                IconButton(onClick = onEndVoiceSession) {
                    Icon(Icons.Outlined.CallEnd, contentDescription = stringResource(R.string.end_voice_chat))
                }
            }
        }
    }
}

private data class TransientSpeech(val text: String)

private data class StreamingAssistant(val text: String)

private object ThinkingIndicator

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
    val thinking = state.face.emotion == RayaFaceEmotion.Thinking
    val showStreaming = thinking && state.streamingText.isNotBlank()

    val items: List<Any> = buildList {
        messages.forEach { add(it) }
        if (showTransient) add(TransientSpeech(state.userText))
        if (showStreaming) add(StreamingAssistant(state.streamingText))
        if (thinking && !showStreaming) add(ThinkingIndicator)
    }
    var tailPolicy by remember { mutableStateOf(ConversationTailPolicyState()) }
    val currentItemCount = rememberUpdatedState(items.size)

    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            total to (total == 0 || lastVisible >= total - 2)
        }.collect { (total, atBottom) ->
            tailPolicy = tailPolicy.onViewportSample(total, currentItemCount.value, atBottom)
        }
    }

    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                tailPolicy = tailPolicy.onManualScroll()
            }
        }
    }

    LaunchedEffect(state.userTurnRevision) {
        val nextPolicy = tailPolicy.onUserTurnRevision(state.userTurnRevision)
        val newIntent = nextPolicy.handledUserTurnRevision != tailPolicy.handledUserTurnRevision
        tailPolicy = nextPolicy
        if (newIntent && items.isNotEmpty()) {
            androidx.compose.runtime.withFrameNanos { }
            listState.animateScrollToItem(items.lastIndex)
        }
    }

    LaunchedEffect(items) {
        if (items.isNotEmpty() && tailPolicy.followTail) {
            androidx.compose.runtime.withFrameNanos { }
            listState.animateScrollToItem(items.lastIndex)
        }
    }

    if (messages.isEmpty() && !showTransient) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = EMPTY_CONVERSATION_TOP_PADDING_DP),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.empty_conversation),
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = EMPTY_CONVERSATION_TOP_PADDING_DP,
            bottom = 16.dp,
        ),
    ) {
        itemsIndexed(items) { _, item ->
            when (item) {
                is ConversationMessage -> AppearingBubble {
                    when (item.role) {
                        ConversationRole.Notice -> NoticeChip(item.text, item.noticeCode)
                        else -> MessageBubble(item)
                    }
                }
                is TransientSpeech -> AppearingBubble {
                    MessageBubble(
                        message = ConversationMessage(ConversationRole.User, item.text),
                        transient = true,
                    )
                }
                is StreamingAssistant -> AppearingBubble { StreamingBubble(item.text) }
                ThinkingIndicator -> AppearingBubble {
                    ThinkingBubble()
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    MessageBubble(
        message = ConversationMessage(ConversationRole.Assistant, text),
        transient = true,
    )
}

@Composable
private fun AppearingBubble(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val slideOffset = with(density) { 12.dp.toPx().roundToInt() }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    AnimatedVisibility(
        visible = appeared,
        enter = fadeIn(animationSpec = tween(durationMillis = 180)) +
            slideInVertically(
                initialOffsetY = { slideOffset },
                animationSpec = tween(durationMillis = 180),
            ),
        exit = fadeOut(animationSpec = tween(durationMillis = 120)),
    ) {
        content()
    }
}

@Composable
private fun ThinkingBubble() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dots = List(3) { index ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400, delayMillis = index * 160),
                repeatMode = RepeatMode.Restart,
            ),
            label = "dot$index",
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 4.dp,
                bottomEnd = 18.dp,
            ),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.assistant_name),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                dots.forEach { dot ->
                    Text(
                        "•",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.alpha(dot.value),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeChip(text: String, noticeCode: RayaNoticeCode?) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.large,
        ) {
            Text(
                if (noticeCode == RayaNoticeCode.InactivityEnded) {
                    stringResource(R.string.notice_inactivity)
                } else {
                    text
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, code: RayaErrorCode?) {
    val localizedMessage = when (code) {
        RayaErrorCode.ReplyUnavailable -> stringResource(R.string.error_reply_unavailable)
        RayaErrorCode.RecognitionStartFailed -> stringResource(R.string.error_recognition_start)
        RayaErrorCode.RecognitionFailed -> stringResource(R.string.error_recognition)
        RayaErrorCode.VoicePipelineFailed -> stringResource(R.string.error_voice_pipeline)
        null -> message
    }
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
                localizedMessage,
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
                .alpha(if (transient) 0.7f else 1f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Text(
                if (isUser) stringResource(R.string.user_label) else stringResource(R.string.assistant_name),
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
                stringResource(state.statusResId),
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
            onSubmitText = {},
            onVoiceChatClick = {},
            onEndVoiceSession = {},
            onToggleMicrophone = {},
            onInterruptSpeech = {},
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
            onSubmitText = {},
            onVoiceChatClick = {},
            onEndVoiceSession = {},
            onToggleMicrophone = {},
            onInterruptSpeech = {},
            onSettingsClick = {},
            onClearConversation = {},
        )
    }
}

@Preview(name = "Raya voice controls", showBackground = true)
@Composable
private fun RayaVoiceControlsPreview() {
    RayaTheme {
        RayaScreen(
            state = RayaUiState(
                interactionMode = InteractionMode.Voice,
                voiceSessionActive = true,
            ),
            onSubmitText = {},
            onVoiceChatClick = {},
            onEndVoiceSession = {},
            onToggleMicrophone = {},
            onInterruptSpeech = {},
            onSettingsClick = {},
            onClearConversation = {},
        )
    }
}
