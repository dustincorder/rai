package com.dustincorder.rai.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dustincorder.rai.RayaApplication
import com.dustincorder.rai.domain.RayaOrchestrator
import com.dustincorder.rai.domain.RayaRoutingDiagnostics
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
) : ViewModel() {
    private val orchestrator = RayaOrchestrator(
        scope = viewModelScope,
        speechRecognition = speechRecognition,
        speechSynthesis = speechSynthesis,
        replyProvider = replyProvider,
        routingDiagnostics = routingDiagnostics,
    )

    val uiState: StateFlow<RayaUiState> = combine(
        combine(
            orchestrator.state,
            orchestrator.userText,
            orchestrator.conversation,
        ) { state, userText, conversation -> Triple(state, userText, conversation) },
        combine(
            orchestrator.interactionMode,
            orchestrator.voiceSessionActive,
            orchestrator.microphoneEnabled,
        ) { interactionMode, voiceSessionActive, microphoneEnabled ->
            Triple(interactionMode, voiceSessionActive, microphoneEnabled)
        },
    ) { chat, session ->
        rayaUiStateFor(
            state = chat.first,
            recognizedText = chat.second,
            conversation = chat.third,
            interactionMode = session.first,
            voiceSessionActive = session.second,
            microphoneEnabled = session.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RayaUiState())

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
        ) as T
    }
}