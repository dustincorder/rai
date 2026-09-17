package com.dustincorder.rai.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dustincorder.rai.BuildConfig
import com.dustincorder.rai.RayaApplication
import com.dustincorder.rai.domain.ChatSession
import com.dustincorder.rai.domain.ChatSessionCoordinator
import com.dustincorder.rai.domain.ChatSessionRepository
import com.dustincorder.rai.domain.ChatTitleGenerator
import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ActiveConversation
import com.dustincorder.rai.domain.BargeInMonitor
import com.dustincorder.rai.domain.InteractionMode
import com.dustincorder.rai.domain.RayaEmotion
import com.dustincorder.rai.domain.RayaOrchestrator
import com.dustincorder.rai.domain.RayaRoutingDiagnostics
import com.dustincorder.rai.domain.RayaState
import com.dustincorder.rai.domain.RayaVoiceDiagnostics
import com.dustincorder.rai.domain.ReplyProvider
import com.dustincorder.rai.domain.SpeechRecognitionProvider
import com.dustincorder.rai.domain.SpeechSynthesisProvider
import com.dustincorder.rai.speech.AndroidSpeechRecognitionProvider
import com.dustincorder.rai.speech.AndroidSpeechSynthesisProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class RayaViewModel(
    speechRecognition: SpeechRecognitionProvider,
    speechSynthesis: SpeechSynthesisProvider,
    replyProvider: ReplyProvider,
    bargeInMonitor: BargeInMonitor? = null,
    chatRepository: ChatSessionRepository? = null,
    titleGenerator: ChatTitleGenerator? = null,
    routingDiagnostics: RayaRoutingDiagnostics = RayaRoutingDiagnostics { _, _, _ -> },
    voiceDiagnostics: RayaVoiceDiagnostics = RayaVoiceDiagnostics { },
    toolTurnRunner: com.dustincorder.rai.domain.tools.ToolTurnRunner? = null,
    toolRegistry: com.dustincorder.rai.domain.tools.ToolRegistry? = null,
) : ViewModel() {
    private val orchestrator = RayaOrchestrator(
        scope = viewModelScope,
        speechRecognition = speechRecognition,
        speechSynthesis = speechSynthesis,
        replyProvider = replyProvider,
        bargeInMonitor = bargeInMonitor,
        routingDiagnostics = routingDiagnostics,
        voiceDiagnostics = voiceDiagnostics,
        toolTurnRunner = toolTurnRunner,
        toolRegistry = toolRegistry,
    )

    private val chatCoordinator = chatRepository?.let {
        ChatSessionCoordinator(viewModelScope, it, orchestrator, titleGenerator)
    }
    val chatSessions: StateFlow<List<ChatSession>> = chatCoordinator?.sessions
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    val activeConversation: StateFlow<ActiveConversation> = chatCoordinator?.activeConversation
        ?: kotlinx.coroutines.flow.MutableStateFlow(ActiveConversation.NewDraft)
    val activeChatId: StateFlow<String?> = activeConversation.map { active ->
        (active as? ActiveConversation.Persistent)?.sessionId
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init { chatCoordinator?.start() }

    val uiState: StateFlow<RayaUiState> = combine(
        combine(
            orchestrator.state,
            orchestrator.userText,
            orchestrator.conversation,
            orchestrator.semanticEmotion,
            orchestrator.userTurnRevision,
        ) { state, userText, conversation, semanticEmotion, userTurnRevision ->
            RayaUiFlux(state, userText, conversation, semanticEmotion, userTurnRevision)
        },
        combine(
            orchestrator.interactionMode,
            orchestrator.voiceSessionActive,
            orchestrator.microphoneEnabled,
            orchestrator.streamingText,
        ) { interactionMode, voiceSessionActive, microphoneEnabled, streamingText ->
            SessionFlux(interactionMode, voiceSessionActive, microphoneEnabled, streamingText)
        },
    ) { chat, session ->
        rayaUiStateFor(
            state = chat.state,
            recognizedText = chat.userText,
            conversation = chat.conversation,
            interactionMode = session.interactionMode,
            voiceSessionActive = session.voiceSessionActive,
            microphoneEnabled = session.microphoneEnabled,
            semanticEmotion = chat.semanticEmotion,
            userTurnRevision = chat.userTurnRevision,
            streamingText = session.streamingText,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RayaUiState())

    private data class RayaUiFlux(
        val state: RayaState,
        val userText: String,
        val conversation: List<ConversationMessage>,
        val semanticEmotion: RayaEmotion,
        val userTurnRevision: Long,
    )

    private data class SessionFlux(
        val interactionMode: InteractionMode,
        val voiceSessionActive: Boolean,
        val microphoneEnabled: Boolean,
        val streamingText: String,
    )

    fun submitText(text: String) = orchestrator.submitText(text)
    fun startVoiceSession() = orchestrator.startVoiceSession()
    fun endVoiceSession() = orchestrator.endVoiceSession()
    fun toggleMicrophone() = orchestrator.toggleMicrophone()
    fun interruptSpeech() = orchestrator.interruptSpeech()
    fun clearConversation() = orchestrator.clearConversation()
    fun showError(message: String) = orchestrator.reportError(message)

    fun createNewChat() = chatCoordinator?.createNewChat()

    fun startTemporaryChat() = chatCoordinator?.startTemporaryChat()

    fun toggleTemporaryChat() = chatCoordinator?.toggleTemporaryChat()

    fun saveTemporaryChat() = chatCoordinator?.saveTemporaryChat()

    fun openChat(id: String) = chatCoordinator?.openChat(id)

    fun deleteChat(id: String) = chatCoordinator?.deleteChat(id)

    val pendingToolConfirmation = orchestrator.pendingToolConfirmation
    fun confirmPendingTool(token: com.dustincorder.rai.domain.tools.ActionConfirmationToken) = orchestrator.confirmPendingTool(token)
    fun rejectPendingTool() = orchestrator.rejectPendingTool()

    override fun onCleared() {
        orchestrator.close()
        super.onCleared()
    }
}

class RayaViewModelFactory(private val application: RayaApplication) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RayaViewModel::class.java))
        return RayaViewModel(
            speechRecognition = application.runtimeSpeechRecognitionProvider(),
            speechSynthesis = application.runtimeSpeechSynthesisProvider(),
            bargeInMonitor = application.bargeInMonitor,
            chatRepository = application.chatRepository,
            titleGenerator = application.replyProvider,
            replyProvider = application.replyProvider,
            routingDiagnostics = AndroidRayaRoutingDiagnostics(),
            voiceDiagnostics = AndroidRayaVoiceDiagnostics(),
            toolTurnRunner = application.toolTurnRunner,
            toolRegistry = application.toolRegistry,
        ) as T
    }
}

class AndroidRayaVoiceDiagnostics : RayaVoiceDiagnostics {
    override fun record(event: String) {
        if (!BuildConfig.DEBUG) return
        try {
            Log.d("Raya-Voice", event)
        } catch (_: RuntimeException) {
            // android.util.Log is not mocked in JVM unit tests.
        }
    }
}
