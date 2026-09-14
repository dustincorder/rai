package com.dustincorder.rai.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RayaOrchestrator(
    private val scope: CoroutineScope,
    private val listeningDelayMs: Long = 700L,
    private val thinkingDelayMs: Long = 900L,
    private val speakingDelayMs: Long = 1_600L,
) {
    private val _state = MutableStateFlow<RayaState>(RayaState.Idle)
    val state: StateFlow<RayaState> = _state.asStateFlow()

    private var demoJob: Job? = null

    fun startMockDemo() {
        demoJob?.cancel()
        demoJob = scope.launch {
            try {
                _state.value = RayaState.Listening
                delay(listeningDelayMs)

                _state.value = RayaState.Thinking
                delay(thinkingDelayMs)

                _state.value = RayaState.Speaking("Я здесь. Системы работают нормально.")
                delay(speakingDelayMs)

                _state.value = RayaState.Idle
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _state.value = RayaState.Error(
                    throwable.message ?: "Демонстрация завершилась с ошибкой.",
                )
            }
        }
    }

    fun reset() {
        demoJob?.cancel()
        demoJob = null
        _state.value = RayaState.Idle
    }
}
