package com.dustincorder.rai.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dustincorder.rai.domain.RayaOrchestrator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class RayaViewModel : ViewModel() {
    private val orchestrator = RayaOrchestrator(viewModelScope)

    val uiState: StateFlow<RayaUiState> = orchestrator.state
        .map(::rayaUiStateFor)
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
}
