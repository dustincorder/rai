package com.dustincorder.rai.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dustincorder.rai.BuildConfig
import com.dustincorder.rai.RayaApplication
import com.dustincorder.rai.domain.ChatSession
import com.dustincorder.rai.domain.ChatSessionRepository
import com.dustincorder.rai.domain.ChatTitleGenerator
import com.dustincorder.rai.domain.shouldGenerateChatTitle
import com.dustincorder.rai.domain.ConversationMessage
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RayaViewModel(
    speechRecognition: SpeechRecognitionProvider,
    speechSynthesis: SpeechSynthesisProvider,
    replyProvider: ReplyProvider,
    bargeInMonitor: BargeInMonitor? = null,
    private val chatRepository: ChatSessionRepository? = null,
    private val titleGenerator: ChatTitleGenerator? = null,
    routingDiagnostics: RayaRoutingDiagnostics = RayaRoutingDiagnostics { _, _, _ -> },
    voiceDiagnostics: RayaVoiceDiagnostics = RayaVoiceDiagnostics { },
) : ViewModel() {
    private val orchestrator = RayaOrchestrator(
        scope = viewModelScope,
        speechRecognition = speechRecognition,
        speechSynthesis = speechSynthesis,
        replyProvider = replyProvider,
        bargeInMonitor = bargeInMonitor,
        routingDiagnostics = routingDiagnostics,
        voiceDiagnostics = voiceDiagnostics,
    )

    private val _chatSessions = kotlinx.coroutines.flow.MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<ChatSession>> = _chatSessions
    private val _activeChatId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val activeChatId: StateFlow<String?> = _activeChatId
    private val chatMutex = Mutex()

    init {
        chatRepository?.let { repository ->
            viewModelScope.launch {
                val existing = repository.sessions.first()
                _chatSessions.value = existing
                val active = existing.firstOrNull() ?: repository.createSession().also {
                    _chatSessions.value = repository.sessions.first()
                }
                _activeChatId.value = active.id
                repository.loadSession(active.id)?.let { orchestrator.replaceConversation(it.messages) }
                orchestrator.conversation.collect { messages ->
                    var titleRequest: Pair<String, List<ConversationMessage>>? = null
                    chatMutex.withLock {
                        val id = _activeChatId.value
                        if (id != null) {
                            repository.saveMessages(id, messages)
                            _chatSessions.value = repository.sessions.first()
                            val session = _chatSessions.value.firstOrNull { it.id == id }
                            if (titleGenerator != null && session != null && shouldGenerateChatTitle(session, messages)) {
                                repository.markTitleGenerationAttempted(id)
                                _chatSessions.value = repository.sessions.first()
                                titleRequest = id to messages
                            }
                        }
                    }
                    titleRequest?.let { (id, snapshot) ->
                        val generator = titleGenerator ?: return@let
                        viewModelScope.launch {
                            runCatching { generator.generate(snapshot) }.getOrNull()?.let { title ->
                                chatMutex.withLock {
                                    repository.updateTitle(id, title, com.dustincorder.rai.domain.ChatTitleSource.Generated)
                                    _chatSessions.value = repository.sessions.first()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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

    fun createNewChat() {
        val repository = chatRepository ?: return
        viewModelScope.launch {
            chatMutex.withLock {
                val session = repository.createSession()
                _activeChatId.value = session.id
                _chatSessions.value = repository.sessions.first()
                orchestrator.replaceConversation(emptyList())
            }
        }
    }

    fun openChat(id: String) {
        val repository = chatRepository ?: return
        viewModelScope.launch {
            chatMutex.withLock {
                if (repository.sessions.first().none { it.id == id }) return@withLock
                val messages = repository.loadSession(id)?.messages.orEmpty()
                _activeChatId.value = id
                _chatSessions.value = repository.sessions.first()
                orchestrator.replaceConversation(messages)
            }
        }
    }

    fun deleteChat(id: String) {
        val repository = chatRepository ?: return
        viewModelScope.launch {
            chatMutex.withLock {
                repository.deleteSession(id)
                var sessions = repository.sessions.first()
                val next = if (_activeChatId.value == id) sessions.firstOrNull() ?: repository.createSession() else null
                sessions = repository.sessions.first()
                _chatSessions.value = sessions
                if (next != null) {
                    _activeChatId.value = next.id
                    orchestrator.replaceConversation(repository.loadSession(next.id)?.messages.orEmpty())
                }
            }
        }
    }

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
