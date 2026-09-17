package com.dustincorder.rai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
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
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.dustincorder.rai.R
import com.dustincorder.rai.domain.ActiveConversation
import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import com.dustincorder.rai.domain.RayaErrorCode
import com.dustincorder.rai.presentation.RayaUiState
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.presentation.model.RayaFaceState
import com.dustincorder.rai.ui.raya.face.RayaFace
import kotlinx.coroutines.flow.collect
import com.dustincorder.rai.ui.theme.RayaTheme

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RayaScreen(
    state: RayaUiState,
    activeConversation: ActiveConversation,
    title: String,
    modelLabel: String,
    onMenuClick: () -> Unit,
    onTemporaryToggle: () -> Unit,
    onFaceClick: () -> Unit,
    onSubmitText: (String) -> Unit,
    onVoiceChatClick: () -> Unit,
    onEndVoiceSession: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onInterruptSpeech: () -> Unit,
    onClearConversation: () -> Unit,
    onSaveTemporary: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var showEmotionSheet by remember { mutableStateOf(false) }
    var identityZoneHeightPx by remember { mutableIntStateOf(0) }
    val canClear = state.conversation.isNotEmpty() && !state.isBusy
    val temporary = activeConversation is ActiveConversation.Temporary
    val emotionPreviewDescription = androidx.compose.ui.res.stringResource(R.string.open_emotion_preview)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            RayaIdentityBar(
                title = title,
                modelLabel = modelLabel,
                temporary = temporary,
                onMenuClick = onMenuClick,
                onTemporaryToggle = onTemporaryToggle,
                temporaryToggleEnabled = !state.isBusy && !state.voiceSessionActive,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ConversationArea(
                    state = state,
                    chatKey = activeConversation,
                    transcriptTopInset = with(LocalDensity.current) { identityZoneHeightPx.toDp() },
                    modifier = Modifier.fillMaxSize(),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .onGloballyPositioned { identityZoneHeightPx = it.size.height }
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .padding(horizontal = 42.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        RayaFace(
                            state = state.face,
                            renderMode = faceRenderMode(activeConversation),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        )
                        IconButton(
                            onClick = { showEmotionSheet = true; onFaceClick() },
                            modifier = Modifier.align(Alignment.Center).size(200.dp).semantics {
                                contentDescription = emotionPreviewDescription
                            },
                        ) { Text("", modifier = Modifier.size(1.dp), color = MaterialTheme.colorScheme.background) }
                    }
                    Text(
                        text = when {
                            temporary -> androidx.compose.ui.res.stringResource(R.string.temporary_mode_active)
                            state.errorMessage != null -> androidx.compose.ui.res.stringResource(R.string.status_attention)
                            else -> androidx.compose.ui.res.stringResource(state.statusResId)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).background(
                            Brush.verticalGradient(
                                listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background.copy(alpha = 0f)),
                            ),
                        ),
                    )
                }
            }
            RayaBottomDock(
                state = state,
                draft = draft,
                temporary = temporary,
                onDraftChange = { draft = it },
                onSubmit = { onSubmitText(draft); draft = "" },
                onVoice = onVoiceChatClick,
                onEndVoice = onEndVoiceSession,
                onToggleMic = onToggleMicrophone,
                onInterrupt = onInterruptSpeech,
                onSaveTemporary = onSaveTemporary,
                onClear = { showClearDialog = true },
                canClear = canClear,
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.clear_conversation_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(R.string.clear_conversation_message)) },
            confirmButton = { TextButton(onClick = { onClearConversation(); showClearDialog = false }) { Text(androidx.compose.ui.res.stringResource(R.string.clear)) } },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text(androidx.compose.ui.res.stringResource(R.string.cancel)) } },
        )
    }
    if (showEmotionSheet) {
        ModalBottomSheet(onDismissRequest = { showEmotionSheet = false }) {
            EmotionPreviewSheet(onDismiss = { showEmotionSheet = false })
        }
    }
}

@Composable
private fun EmotionPreviewSheet(onDismiss: () -> Unit) {
    val emotions = listOf(
        RayaFaceEmotion.Calm, RayaFaceEmotion.Happy, RayaFaceEmotion.Excited,
        RayaFaceEmotion.Playful, RayaFaceEmotion.Curious, RayaFaceEmotion.SemanticThinking,
        RayaFaceEmotion.Skeptical, RayaFaceEmotion.Confused, RayaFaceEmotion.Concerned,
        RayaFaceEmotion.Sad, RayaFaceEmotion.Embarrassed, RayaFaceEmotion.Surprised,
        RayaFaceEmotion.Angry, RayaFaceEmotion.Annoyed, RayaFaceEmotion.Tired,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
        Text(androidx.compose.ui.res.stringResource(R.string.raya_emotions), style = MaterialTheme.typography.titleLarge)
        Text(androidx.compose.ui.res.stringResource(R.string.raya_emotions_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(emotions.chunked(2)) { _, row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { emotion ->
                        Column(Modifier.weight(1f).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            RayaFace(RayaFaceState(emotion = emotion), Modifier.size(92.dp))
                            Text(emotionLabel(emotion), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun emotionLabel(emotion: RayaFaceEmotion): String = androidx.compose.ui.res.stringResource(
    when (emotion) {
        RayaFaceEmotion.Calm -> R.string.emotion_calm
        RayaFaceEmotion.Happy -> R.string.emotion_happy
        RayaFaceEmotion.Excited -> R.string.emotion_excited
        RayaFaceEmotion.Playful -> R.string.emotion_playful
        RayaFaceEmotion.Curious -> R.string.emotion_curious
        RayaFaceEmotion.SemanticThinking, RayaFaceEmotion.Thinking -> R.string.emotion_thinking
        RayaFaceEmotion.Skeptical -> R.string.emotion_skeptical
        RayaFaceEmotion.Confused -> R.string.emotion_confused
        RayaFaceEmotion.Concerned -> R.string.emotion_concerned
        RayaFaceEmotion.Sad -> R.string.emotion_sad
        RayaFaceEmotion.Embarrassed -> R.string.emotion_embarrassed
        RayaFaceEmotion.Surprised -> R.string.emotion_surprised
        RayaFaceEmotion.Angry -> R.string.emotion_angry
        RayaFaceEmotion.Annoyed -> R.string.emotion_annoyed
        RayaFaceEmotion.Tired -> R.string.emotion_tired
        RayaFaceEmotion.Listening -> R.string.status_listening
        RayaFaceEmotion.Error -> R.string.status_error
    },
)

@Composable
private fun RayaIdentityBar(
    title: String,
    modelLabel: String,
    temporary: Boolean,
    onMenuClick: () -> Unit,
    onTemporaryToggle: () -> Unit,
    temporaryToggleEnabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(Icons.Outlined.Menu, contentDescription = androidx.compose.ui.res.stringResource(R.string.open_menu))
        }
        IconButton(onClick = onTemporaryToggle, enabled = temporaryToggleEnabled) {
            Icon(
                if (temporary) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = androidx.compose.ui.res.stringResource(if (temporary) R.string.return_to_normal_chat else R.string.start_temporary_chat),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (temporary) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.temporary_short_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private data class TransientSpeech(val text: String)
private data class StreamingAssistant(val text: String)
private object ThinkingIndicator

@Composable
private fun ConversationArea(
    state: RayaUiState,
    chatKey: ActiveConversation,
    transcriptTopInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val messages = state.conversation
    val thinking = state.face.emotion == RayaFaceEmotion.Thinking
    val showTransient = state.face.emotion == RayaFaceEmotion.Listening && state.userText.isNotBlank() && messages.lastOrNull()?.text != state.userText
    val items: List<Any> = buildList {
        messages.forEach(::add)
        if (showTransient) add(TransientSpeech(state.userText))
        if (state.streamingText.isNotBlank()) add(StreamingAssistant(state.streamingText))
        if (thinking && state.streamingText.isBlank()) add(ThinkingIndicator)
    }
    var tailPolicy by remember(chatKey) { mutableStateOf(ConversationTailPolicyState()) }
    val itemCount by rememberUpdatedState(items.size)
    LaunchedEffect(listState) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total to (total == 0 || last >= total - 2)
        }.collect { (total, atBottom) -> tailPolicy = tailPolicy.onViewportSample(total, itemCount, atBottom) }
    }
    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                tailPolicy = tailPolicy.onManualScroll()
            }
        }
    }
    LaunchedEffect(state.userTurnRevision) {
        val next = tailPolicy.onUserTurnRevision(state.userTurnRevision)
        val changed = next.handledUserTurnRevision != tailPolicy.handledUserTurnRevision
        tailPolicy = next
        if (changed && items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }
    LaunchedEffect(items) {
        if (items.isNotEmpty() && tailPolicy.followTail) listState.animateScrollToItem(items.lastIndex)
    }
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.empty_conversation),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = transcriptTopInset + 12.dp, bottom = 20.dp),
    ) {
        itemsIndexed(items) { _, item ->
            when (item) {
                is ConversationMessage -> if (item.role == ConversationRole.Notice) RayaNotice() else RayaMessage(item)
                is TransientSpeech -> RayaMessage(ConversationMessage(ConversationRole.User, item.text), muted = true)
                is StreamingAssistant -> RayaMessage(ConversationMessage(ConversationRole.Assistant, item.text), muted = true)
                ThinkingIndicator -> RayaThinking()
            }
        }
        if (state.errorMessage != null) item { RayaError(state.errorMessage, state.errorCode) }
    }
}

@Composable
private fun RayaMessage(message: ConversationMessage, muted: Boolean = false) {
    val user = message.role == ConversationRole.User
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 340.dp).alpha(if (muted) 0.65f else 1f)) {
            if (!user) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                Text("  "+androidx.compose.ui.res.stringResource(R.string.assistant_name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (user) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 5.dp, bottom = 2.dp, start = if (user) 12.dp else 0.dp, end = if (user) 0.dp else 12.dp),
            )
        }
    }
}

@Composable
private fun RayaThinking() {
    Text("•••", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun RayaNotice() {
    Text(
        androidx.compose.ui.res.stringResource(R.string.notice_inactivity),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RayaError(message: String, code: RayaErrorCode?) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                when (code) {
                    RayaErrorCode.ReplyUnavailable -> androidx.compose.ui.res.stringResource(R.string.error_reply_unavailable)
                    RayaErrorCode.RecognitionStartFailed -> androidx.compose.ui.res.stringResource(R.string.error_recognition_start)
                    RayaErrorCode.RecognitionFailed -> androidx.compose.ui.res.stringResource(R.string.error_recognition)
                    RayaErrorCode.VoicePipelineFailed -> androidx.compose.ui.res.stringResource(R.string.error_voice_pipeline)
                    null -> message
                },
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun RayaBottomDock(
    state: RayaUiState,
    draft: String,
    temporary: Boolean,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onVoice: () -> Unit,
    onEndVoice: () -> Unit,
    onToggleMic: () -> Unit,
    onInterrupt: () -> Unit,
    onSaveTemporary: () -> Unit,
    onClear: () -> Unit,
    canClear: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), tonalElevation = 4.dp) {
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!state.voiceSessionActive) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(androidx.compose.ui.res.stringResource(R.string.message_placeholder)) },
                        enabled = !state.isBusy,
                        maxLines = 4,
                        shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) onSubmit() }),
                    )
                    IconButton(onClick = if (draft.isBlank()) onVoice else onSubmit, enabled = !state.isBusy) {
                        Icon(if (draft.isBlank()) Icons.Outlined.Mic else Icons.AutoMirrored.Outlined.Send, contentDescription = androidx.compose.ui.res.stringResource(if (draft.isBlank()) R.string.voice_chat else R.string.send_message))
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(androidx.compose.ui.res.stringResource(state.statusResId), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(androidx.compose.ui.res.stringResource(R.string.voice_chat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.isSpeaking) IconButton(onClick = onInterrupt) { Icon(Icons.Outlined.Stop, androidx.compose.ui.res.stringResource(R.string.stop_speech)) }
                    IconButton(onClick = onToggleMic) { Icon(if (state.microphoneEnabled) Icons.Outlined.Mic else Icons.Outlined.MicOff, androidx.compose.ui.res.stringResource(if (state.microphoneEnabled) R.string.mic_off else R.string.mic_on)) }
                    IconButton(onClick = onEndVoice) { Icon(Icons.Outlined.CallEnd, androidx.compose.ui.res.stringResource(R.string.end_voice_chat)) }
                }
            }
            if (temporary || canClear) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (temporary) TextButton(onClick = onSaveTemporary) { Text(androidx.compose.ui.res.stringResource(R.string.save_chat)) }
                    if (canClear) TextButton(onClick = onClear) { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(16.dp)); Text("  "+androidx.compose.ui.res.stringResource(R.string.clear)) }
                }
            }
        }
    }
}

@Preview(name = "Raya emotions sheet", showBackground = true)
@Composable
private fun RayaEmotionSheetPreview() {
    RayaTheme { EmotionPreviewSheet(onDismiss = {}) }
}

@Preview(name = "Raya main", showBackground = true)
@Composable
private fun RayaMainPreview() {
    RayaTheme {
        RayaScreen(
            state = RayaUiState(),
            activeConversation = ActiveConversation.NewDraft,
            title = "New chat",
            modelLabel = "Gemini · gemini-2.0-flash",
            onMenuClick = {}, onFaceClick = {}, onSubmitText = {}, onVoiceChatClick = {},
            onTemporaryToggle = {},
            onEndVoiceSession = {}, onToggleMicrophone = {}, onInterruptSpeech = {},
            onClearConversation = {}, onSaveTemporary = {},
        )
    }
}

@Preview(name = "Temporary listening", showBackground = true)
@Composable
private fun RayaTemporaryPreview() {
    RayaTheme {
        RayaScreen(
            state = RayaUiState(voiceSessionActive = true, microphoneEnabled = true),
            activeConversation = ActiveConversation.Temporary,
            title = "Temporary chat",
            modelLabel = "Groq · llama",
            onMenuClick = {}, onFaceClick = {}, onSubmitText = {}, onVoiceChatClick = {},
            onTemporaryToggle = {},
            onEndVoiceSession = {}, onToggleMicrophone = {}, onInterruptSpeech = {},
            onClearConversation = {}, onSaveTemporary = {},
        )
    }
}
