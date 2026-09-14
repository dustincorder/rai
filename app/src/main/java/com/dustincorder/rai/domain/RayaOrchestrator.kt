package com.dustincorder.rai.domain

import android.util.Log
import com.dustincorder.rai.BuildConfig
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
    private val languageProvider: ConversationLanguageProvider = object : ConversationLanguageProvider {
        override suspend fun currentLanguage(): ConversationLanguage = ConversationLanguage.System
    },
    private val systemLanguageTag: () -> String = { Locale.getDefault().toLanguageTag() },
) {
    private val _state = MutableStateFlow<RayaState>(RayaState.Idle)
    val state: StateFlow<RayaState> = _state.asStateFlow()

    private val _userText = MutableStateFlow("")
    val userText: StateFlow<String> = _userText.asStateFlow()

    private var voiceJob: Job? = null

    fun startVoiceFlow() {
        if (voiceJob?.isActive == true) return
        if (_state.value is RayaState.Error) {
            _state.value = RayaState.Idle
        }
        if (_state.value != RayaState.Idle) return

        voiceJob = scope.launch {
            val finalResult = CompletableDeferred<SpeechRecognitionEvent.Final>()
            val recognitionJob = launch {
                speechRecognition.events.collect { event ->
                    when (event) {
                        is SpeechRecognitionEvent.Partial -> _userText.value = event.text
                        is SpeechRecognitionEvent.Final -> {
                            _userText.value = event.text
                            finalResult.complete(event)
                        }
                        is SpeechRecognitionEvent.Error -> {
                            finalResult.completeExceptionally(RecognitionException(event.message))
                        }
                    }
                }
            }

            try {
                _userText.value = ""
                _state.value = RayaState.Listening
                val language = languageProvider.currentLanguage()
                val systemTag = systemLanguageTag()
                speechRecognition.startListening(RecognitionRequest(language, systemTag))
                val result = finalResult.await()
                val recognizedText = result.text.trim()
                recognitionJob.cancelAndJoin()

                if (recognizedText.isEmpty()) {
                    throw RecognitionException("Не удалось распознать речь.")
                }

                _state.value = RayaState.Thinking
                val addressing = RayaAddressingParser.parse(recognizedText)
                val resolvedLanguageTag = language.resolveLanguageTag(result.detectedLanguageTag, systemTag)
                val queryBlank = addressing.query.isBlank()
                val localResponse = addressing.addressed && queryBlank
                logRouting(addressing.addressed, queryBlank, localResponse)
                val response = if (localResponse) {
                    localNameResponse(resolvedLanguageTag)
                } else {
                    replyProvider.reply(addressing.query.ifBlank { recognizedText }, resolvedLanguageTag)
                }
                _state.value = RayaState.Speaking(response)
                speechSynthesis.speak(response, Locale.forLanguageTag(resolvedLanguageTag))
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

    private fun logRouting(addressed: Boolean, queryBlank: Boolean, localResponse: Boolean) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            Log.d("Raya-Routing", "addressed=$addressed queryBlank=$queryBlank localResponse=$localResponse")
        }
    }
}
