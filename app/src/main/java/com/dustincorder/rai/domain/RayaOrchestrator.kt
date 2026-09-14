package com.dustincorder.rai.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.Locale

class RayaOrchestrator(
    private val scope: CoroutineScope,
    private val speechRecognition: SpeechRecognitionProvider,
    private val speechSynthesis: SpeechSynthesisProvider,
    private val replyProvider: ReplyProvider,
) {
    private val _state = MutableStateFlow<RayaState>(RayaState.Idle)
    val state: StateFlow<RayaState> = _state.asStateFlow()

    private val _userText = MutableStateFlow("")
    val userText: StateFlow<String> = _userText.asStateFlow()

    private var voiceJob: Job? = null

    fun startVoiceFlow(locale: Locale = Locale("ru", "RU")) {
        if (voiceJob?.isActive == true) return
        if (_state.value is RayaState.Error) {
            _state.value = RayaState.Idle
        }
        if (_state.value != RayaState.Idle) return

        voiceJob = scope.launch {
            val finalText = CompletableDeferred<String>()
            val recognitionJob = launch {
                speechRecognition.events.collect { event ->
                    when (event) {
                        is SpeechRecognitionEvent.Partial -> _userText.value = event.text
                        is SpeechRecognitionEvent.Final -> {
                            _userText.value = event.text
                            finalText.complete(event.text)
                        }
                        is SpeechRecognitionEvent.Error -> {
                            finalText.completeExceptionally(RecognitionException(event.message))
                        }
                    }
                }
            }

            try {
                _userText.value = ""
                _state.value = RayaState.Listening
                speechRecognition.startListening(locale)
                val recognizedText = finalText.await().trim()
                recognitionJob.cancelAndJoin()

                if (recognizedText.isEmpty()) {
                    throw RecognitionException("Не удалось распознать речь.")
                }

                _state.value = RayaState.Thinking
                val response = replyProvider.reply(recognizedText)
                _state.value = RayaState.Speaking(response)
                speechSynthesis.speak(response, locale)
                _state.value = RayaState.Idle
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _state.value = RayaState.Error(
                    throwable.message ?: "Голосовой pipeline завершился с ошибкой.",
                )
            } finally {
                recognitionJob.cancel()
                if (_state.value == RayaState.Listening) {
                    speechRecognition.cancel()
                }
            }
        }
    }

    fun cancelListening() {
        if (_state.value == RayaState.Listening) {
            speechRecognition.cancel()
            voiceJob?.cancel()
            voiceJob = null
            _state.value = RayaState.Idle
        }
    }

    fun reportError(message: String) {
        voiceJob?.cancel()
        speechRecognition.cancel()
        speechSynthesis.stop()
        _state.value = RayaState.Error(message)
    }

    fun reset() {
        voiceJob?.cancel()
        voiceJob = null
        speechRecognition.cancel()
        speechSynthesis.stop()
        _state.value = RayaState.Idle
    }

    fun close() {
        voiceJob?.cancel()
        speechRecognition.cancel()
        speechRecognition.release()
        speechSynthesis.stop()
        speechSynthesis.shutdown()
    }

    class RecognitionException(message: String) : RuntimeException(message)
}
