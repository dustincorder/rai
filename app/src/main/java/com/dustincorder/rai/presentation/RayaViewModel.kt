package com.dustincorder.rai.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dustincorder.rai.domain.RayaOrchestrator
import com.dustincorder.rai.domain.RayaState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class RayaViewModel : ViewModel() {
    private val orchestrator = RayaOrchestrator(viewModelScope)

    val uiState: StateFlow<RayaUiState> = orchestrator.state
        .map(::toUiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RayaUiState(),
        )

    fun startDemo() {
        orchestrator.startMockDemo()
    }

    fun reset() {
        orchestrator.reset()
    }

    private fun toUiState(state: RayaState): RayaUiState = when (state) {
        RayaState.Idle -> RayaUiState()
        RayaState.Listening -> RayaUiState(
            emotion = RayaEmotion.Listening,
            status = "Слушаю",
            userText = "Я слушаю тебя...",
            responseText = "",
            isBusy = true,
        )
        RayaState.Thinking -> RayaUiState(
            emotion = RayaEmotion.Thinking,
            status = "Размышляю",
            userText = "Привет, Райя",
            responseText = "Собираю ответ...",
            isBusy = true,
        )
        is RayaState.Speaking -> RayaUiState(
            emotion = RayaEmotion.Speaking,
            status = "Говорю",
            userText = "Привет, Райя",
            responseText = state.text,
            isBusy = true,
        )
        is RayaState.Error -> RayaUiState(
            emotion = RayaEmotion.Error,
            status = "Сбой системы",
            userText = "Привет, Райя",
            responseText = state.message,
        )
    }
}
