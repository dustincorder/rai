package com.dustincorder.rai.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dustincorder.rai.domain.MockReplyProvider
import com.dustincorder.rai.domain.RayaOrchestrator
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
) : ViewModel() {
    private val orchestrator = RayaOrchestrator(
        scope = viewModelScope,
        speechRecognition = speechRecognition,
        speechSynthesis = speechSynthesis,
        replyProvider = MockReplyProvider(),
    )

    val uiState: StateFlow<RayaUiState> = combine(
        orchestrator.state,
        orchestrator.userText,
    ) { state, userText -> rayaUiStateFor(state, userText) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RayaUiState(),
        )

    fun startVoiceFlow() {
        orchestrator.startVoiceFlow()
    }

    fun cancelListening() {
        orchestrator.cancelListening()
    }

    fun showError(message: String) {
        orchestrator.reportError(message)
    }

    override fun onCleared() {
        orchestrator.close()
        super.onCleared()
    }
}

class RayaViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val applicationContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RayaViewModel::class.java))
        return RayaViewModel(
            speechRecognition = AndroidSpeechRecognitionProvider(applicationContext),
            speechSynthesis = AndroidSpeechSynthesisProvider(applicationContext),
        ) as T
    }
}
