package com.dustincorder.rai.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dustincorder.rai.BuildConfig
import com.dustincorder.rai.RayaApplication
import com.dustincorder.rai.domain.ConversationMessage
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
import kotlinx.coroutines.flow.stateIn

class RayaViewModel(
    speechRecognition: SpeechRecognitionProvider,
    speechSynthesis: SpeechSynthesisProvider,
    replyProvider: ReplyProvider,
    routingDiagnostics: RayaRoutingDiagnostics = RayaRoutingDiagnostics { _, _, _ -> },
    voiceDiagnostics: RayaVoiceDiagnostics = RayaVoiceDiagnostics { },
) : ViewModel() {
    private val orchestrator = RayaOrchestrator(
        scope = viewModelScope,
        speechRecognition = speechRecognition,
        speechSynthesis = speechSynthesis,
        replyProvider = replyProvider,
        routingDiagnostics = routingDiagnostics,
        voiceDiagnostics = voiceDiagnostics,
    )

    val uiState: StateFlow<RayaUiState> = combine(
        combine(
            orchestrator.state,
            orchestrator.userText,
            orchestrator.conversation,
            orchestrator.semanticEmotion,
        ) { state, userText, conversation, semanticEmotion ->
            RayaUiFlux(state, userText, conversation, semanticEmotion)
        },
        combine(
            orchestrator.interactionMode,
            orchestrator.voiceSessionActive,
            orchestrator.microphoneEnabled,
        ) { interactionMode, voiceSessionActive, microphoneEnabled ->
            Triple(interactionMode, voiceSessionActive, microphoneEnabled)
        },
    ) { chat, session ->
        rayaUiStateFor(
            state = chat.state,
            recognizedText = chat.userText,
            conversation = chat.conversation,
            interactionMode = session.first,
            voiceSessionActive = session.second,
            microphoneEnabled = session.third,
            semanticEmotion = chat.semanticEmotion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RayaUiState())

    private data class RayaUiFlux(
        val state: RayaState,
        val userText: String,
        val conversation: List<ConversationMessage>,
        val semanticEmotion: RayaEmotion,
    )

    fun submitText(text: String) = orchestrator.submitText(text)
    fun startVoiceSession() = orchestrator.startVoiceSession()
    fun endVoiceSession() = orchestrator.endVoiceSession()
    fun toggleMicrophone() = orchestrator.toggleMicrophone()
    fun interruptSpeech() = orchestrator.interruptSpeech()
    fun clearConversation() = orchestrator.clearConversation()
    fun showError(message: String) = orchestrator.reportError(message)

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
            speechRecognition = AndroidSpeechRecognitionProvider(application),
            speechSynthesis = AndroidSpeechSynthesisProvider(application),
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