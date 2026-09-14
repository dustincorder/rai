package com.dustincorder.rai.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dustincorder.rai.RayaApplication
import com.dustincorder.rai.domain.RayaOrchestrator
import com.dustincorder.rai.domain.ReplyProvider
import com.dustincorder.rai.domain.SpeechRecognitionProvider
import com.dustincorder.rai.domain.SpeechSynthesisProvider
import com.dustincorder.rai.domain.ConversationLanguageProvider
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
    languageProvider: ConversationLanguageProvider,
) : ViewModel() {
    private val orchestrator = RayaOrchestrator(
        scope = viewModelScope,
        speechRecognition = speechRecognition,
        speechSynthesis = speechSynthesis,
        replyProvider = replyProvider,
        languageProvider = languageProvider,
    )

    val uiState: StateFlow<RayaUiState> = combine(
        orchestrator.state,
        orchestrator.userText,
    ) { state, userText -> rayaUiStateFor(state, userText) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RayaUiState())

    fun startVoiceFlow() = orchestrator.startVoiceFlow()
    fun cancelListening() = orchestrator.cancelListening()
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
            languageProvider = application.settingsRepository,
        ) as T
    }
}
