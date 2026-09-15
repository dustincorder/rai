package com.dustincorder.rai.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.Locale

fun interface RayaRoutingDiagnostics {
    fun record(addressed: Boolean, queryBlank: Boolean, localResponse: Boolean)
}

fun interface RayaVoiceDiagnostics {
    fun record(event: String)
}

enum class InteractionMode {
    Text,
    Voice,
}

internal class RecognitionFailure(
    val reason: SpeechRecognitionErrorReason,
    message: String,
) : RuntimeException(message)

class RayaOrchestrator(
    private val scope: CoroutineScope,
    private val speechRecognition: SpeechRecognitionProvider,
    private val speechSynthesis: SpeechSynthesisProvider,
    private val replyProvider: ReplyProvider,
    private val systemLanguageTag: () -> String = { Locale.getDefault().toLanguageTag() },
    private val routingDiagnostics: RayaRoutingDiagnostics = RayaRoutingDiagnostics { _, _, _ -> },
    private val voiceDiagnostics: RayaVoiceDiagnostics = RayaVoiceDiagnostics { },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val inactivityTimeoutMs: () -> Long = { USER_INACTIVITY_TIMEOUT_MS },
) {
    private val _state = MutableStateFlow<RayaState>(RayaState.Idle)
    val state: StateFlow<RayaState> = _state.asStateFlow()

    private val _userText = MutableStateFlow("")
    val userText: StateFlow<String> = _userText.asStateFlow()

    private val _conversation = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val conversation: StateFlow<List<ConversationMessage>> = _conversation.asStateFlow()

    private val _interactionMode = MutableStateFlow(InteractionMode.Text)
    val interactionMode: StateFlow<InteractionMode> = _interactionMode.asStateFlow()

    private val _voiceSessionActive = MutableStateFlow(false)
    val voiceSessionActive: StateFlow<Boolean> = _voiceSessionActive.asStateFlow()

    private val _microphoneEnabled = MutableStateFlow(true)
    val microphoneEnabled: StateFlow<Boolean> = _microphoneEnabled.asStateFlow()

    private val _semanticEmotion = MutableStateFlow(RayaEmotion.Calm)
    val semanticEmotion: StateFlow<RayaEmotion> = _semanticEmotion.asStateFlow()

    private val _lastResponseLanguageTag = MutableStateFlow<String?>(null)
    val lastResponseLanguageTag: StateFlow<String?> = _lastResponseLanguageTag.asStateFlow()

    private val _userTurnRevision = MutableStateFlow(0L)
    val userTurnRevision: StateFlow<Long> = _userTurnRevision.asStateFlow()

    private var sessionJob: Job? = null
    private var activeTurnJob: Job? = null
    private var turnEpoch = 0
    private var lastUserActivityAt = Long.MAX_VALUE
    private var textTurnInFlight = false

    private fun record(event: String) {
        voiceDiagnostics.record(event)
    }

    private fun stateName(): String = _state.value::class.simpleName ?: _state.value.javaClass.name

    private fun isStaleEpoch(epoch: Int, stage: String): Boolean {
        if (epoch == turnEpoch) return false
        record("voice.staleEpochRejected stage=$stage epoch=$epoch currentTurnEpoch=$turnEpoch")
        return true
    }

    fun submitText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (_voiceSessionActive.value) return
        if (textTurnInFlight) return
        val languageTag: String? = null
        markUserTurnIntent()
        textTurnInFlight = true
        scope.launch {
            try {
                _conversation.value = appendMessage(ConversationMessage(ConversationRole.User, clean))
                _state.value = RayaState.Thinking
                try {
                    val response = replyProvider.reply(conversationContext(), languageTag)
                    if (_voiceSessionActive.value) return@launch
                    if (!textTurnInFlight) return@launch
                    _lastResponseLanguageTag.value = response.languageTag?.takeIf { it.isValidLanguageTag() }
                    _semanticEmotion.value = response.emotion
                    _conversation.value = appendMessage(ConversationMessage(ConversationRole.Assistant, response.text))
                    _state.value = RayaState.Idle
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    if (textTurnInFlight) {
                        _state.value = RayaState.Error(
                            failure.message ?: "Не удалось получить ответ.",
                        )
                    }
                }
            } finally {
                textTurnInFlight = false
            }
        }
    }

    fun startVoiceSession() {
        if (_voiceSessionActive.value) return
        if (textTurnInFlight) return
        if (_state.value is RayaState.Error) {
            _state.value = RayaState.Idle
        }
        _voiceSessionActive.value = true
        _microphoneEnabled.value = true
        _interactionMode.value = InteractionMode.Voice
        _semanticEmotion.value = RayaEmotion.Calm
        _lastResponseLanguageTag.value = null
        sessionJob = SupervisorJob(scope.coroutineContext[Job] ?: Job())
        record(
            "voice.startSession turnEpoch=$turnEpoch voiceSessionActive=${_voiceSessionActive.value} " +
                "microphoneEnabled=${_microphoneEnabled.value} state=${stateName()}",
        )
        launchInactivityMonitor()
        beginVoiceTurn(resetActivity = true, explicitIntent = true)
    }

    fun endVoiceSession() = endVoiceSessionInternal(notice = false, reason = "user")

    fun toggleMicrophone() {
        if (!_voiceSessionActive.value) return
        val enable = !_microphoneEnabled.value
        _microphoneEnabled.value = enable
        record(
            if (enable) {
                "voice.micOn turnEpoch=$turnEpoch state=${stateName()} voiceSessionActive=${_voiceSessionActive.value}"
            } else {
                "voice.micOff turnEpoch=$turnEpoch state=${stateName()} voiceSessionActive=${_voiceSessionActive.value}"
            },
        )
        if (enable) {
            when {
                _state.value == RayaState.Listening ||
                    _state.value == RayaState.Idle -> beginVoiceTurn(resetActivity = true, explicitIntent = true)
            }
        } else {
            if (_state.value == RayaState.Listening) {
                _state.value = RayaState.Idle
                turnEpoch++
                activeTurnJob?.cancel()
                record(
                    "voice.micOffTurnInvalidated turnEpoch=$turnEpoch " +
                        "voiceSessionActive=${_voiceSessionActive.value}",
                )
            }
            speechRecognition.cancel()
        }
    }

    fun interruptSpeech() {
        if (!_voiceSessionActive.value) return
        if (_state.value !is RayaState.Speaking) return
        val parent = sessionJob ?: return
        if (!parent.isActive) return
        record("voice.interrupt turnEpoch=$turnEpoch microphoneEnabled=${_microphoneEnabled.value}")
        if (_microphoneEnabled.value) {
            scope.launch(parent) {
                beginVoiceTurn(resetActivity = true)
                delay(INTERRUPT_TTS_STOP_DELAY_MS)
                speechSynthesis.stop()
            }
        } else {
            scope.launch(parent) {
                delay(INTERRUPT_TTS_STOP_DELAY_MS)
                speechSynthesis.stop()
                _state.value = RayaState.Idle
            }
        }
    }

    fun reportError(message: String) {
        endVoiceSessionInternal(notice = false, reason = "reportError")
        _state.value = RayaState.Error(message)
    }

    fun reset() {
        endVoiceSessionInternal(notice = false, reason = "reset")
        textTurnInFlight = false
        _state.value = RayaState.Idle
    }

    fun clearConversation() {
        if (_voiceSessionActive.value) return
        if (textTurnInFlight) return
        _conversation.value = emptyList()
        _semanticEmotion.value = RayaEmotion.Calm
        _lastResponseLanguageTag.value = null
    }

    fun close() {
        endVoiceSessionInternal(notice = false, reason = "agentClose")
        speechRecognition.release()
        speechSynthesis.shutdown()
    }

    private fun beginVoiceTurn(resetActivity: Boolean, explicitIntent: Boolean = false) {
        if (sessionJob?.isActive != true) return
        val parent = sessionJob ?: return
        if (explicitIntent) markUserTurnIntent()
        val epoch = ++turnEpoch
        if (resetActivity) {
            lastUserActivityAt = now()
        }
        record(
            "voice.beginTurn epoch=$epoch resetActivity=$resetActivity " +
                "voiceSessionActive=${_voiceSessionActive.value} microphoneEnabled=${_microphoneEnabled.value}",
        )
        activeTurnJob = scope.launch(parent) {
            runVoiceTurn(epoch)
        }
    }

    private suspend fun CoroutineScope.runVoiceTurn(epoch: Int) {
        val finalResult = CompletableDeferred<SpeechRecognitionEvent.Final>()
        _state.value = RayaState.Listening
        _userText.value = ""
        record(
            "voice.turnStarting epoch=$epoch state=${stateName()} " +
                "voiceSessionActive=${_voiceSessionActive.value} microphoneEnabled=${_microphoneEnabled.value}",
        )
        val collector = launch {
            speechRecognition.events.collect { event ->
                when (event) {
                    is SpeechRecognitionEvent.Partial -> {
                        if (event.text.isNotBlank()) lastUserActivityAt = now()
                        _userText.value = event.text
                    }
                    is SpeechRecognitionEvent.Final -> {
                        record("voice.final epoch=$epoch detectedLanguageTag=${event.detectedLanguageTag ?: "null"}")
                        _userText.value = event.text
                        finalResult.complete(event)
                    }
                    is SpeechRecognitionEvent.Error -> {
                        record(
                            "voice.recognitionError epoch=$epoch reason=${event.reason} " +
                                "message=${event.message}",
                        )
                        if (!finalResult.isCompleted) {
                            finalResult.completeExceptionally(RecognitionFailure(event.reason, event.message))
                        }
                    }
                }
            }
        }
        yield()
        try {
            speechRecognition.startListening(
                RecognitionRequest(
                    language = ConversationLanguage.Auto,
                    systemLanguageTag = systemLanguageTag(),
                ),
            )
            record("voice.turnListeningStarted epoch=$epoch")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            collector.cancel()
            record("voice.startListeningFailure epoch=$epoch class=${failure::class.java.simpleName}")
            if (isStaleEpoch(epoch, "startListening")) return
            failVoiceSession("Сбой инициализации распознавания речи.")
            return
        }
        try {
            val result = finalResult.await()
            collector.cancelAndJoin()
            if (isStaleEpoch(epoch, "final")) return
            onVoiceFinal(result, epoch)
        } catch (failure: RecognitionFailure) {
            collector.cancelAndJoin()
            if (isStaleEpoch(epoch, "recognitionError")) return
            when (failure.reason) {
                SpeechRecognitionErrorReason.NoSpeech,
                SpeechRecognitionErrorReason.NoMatch,
                -> {
                    if (_voiceSessionActive.value && _microphoneEnabled.value) {
                        record("voice.recoverableRestart epoch=$epoch reason=${failure.reason}")
                        beginVoiceTurn(resetActivity = false)
                    } else {
                        _state.value = RayaState.Idle
                    }
                }
                else -> failVoiceSession(failure.message ?: "Сбой распознавания речи.")
            }
        }
    }

    private suspend fun onVoiceFinal(result: SpeechRecognitionEvent.Final, epoch: Int) {
        lastUserActivityAt = now()
        val recognizedText = result.text.trim()
        val resolvedLanguageTag = ConversationLanguage.Auto
            .resolveLanguageTag(result.detectedLanguageTag, systemLanguageTag())
        record(
            "voice.processingFinal epoch=$epoch resolvedLanguageTag=$resolvedLanguageTag " +
                "state=${stateName()}",
        )
        if (recognizedText.isEmpty()) {
            if (_voiceSessionActive.value && _microphoneEnabled.value) {
                beginVoiceTurn(resetActivity = false)
            } else {
                _state.value = RayaState.Idle
            }
            return
        }

        val addressing = RayaAddressingParser.parse(recognizedText)
        val queryBlank = addressing.query.isBlank()
        val localResponse = addressing.addressed && queryBlank
        routingDiagnostics.record(addressing.addressed, queryBlank, localResponse)
        markUserTurnIntent()

        _state.value = RayaState.Thinking
        _conversation.value = appendMessage(
            ConversationMessage(
                role = ConversationRole.User,
                text = recognizedText,
                contextText = addressing.query.ifBlank { recognizedText },
            ),
        )

        val response = try {
            if (localResponse) {
                localNameResponse(resolvedLanguageTag)
            } else {
                replyProvider.reply(conversationContext(), resolvedLanguageTag)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            if (isStaleEpoch(epoch, "reply")) return
            failVoiceSession(failure.message ?: "Голосовой pipeline завершился с ошибкой.")
            return
        }

        if (isStaleEpoch(epoch, "highlight")) return
        _lastResponseLanguageTag.value = response.languageTag?.takeIf { it.isValidLanguageTag() }
        _semanticEmotion.value = response.emotion
        _conversation.value = appendMessage(ConversationMessage(ConversationRole.Assistant, response.text))
        _state.value = RayaState.Speaking(response.text)

        val ttsLanguageTag = response.languageTag
            ?.takeIf { it.isValidLanguageTag() }
            ?: resolvedLanguageTag
        record(
            "voice.speak epoch=$epoch responseLanguageTag=${response.languageTag ?: "null"} " +
                "ttsLanguageTag=$ttsLanguageTag emotion=${response.emotion}",
        )
        val outcome = runCatching {
            speechSynthesis.speak(response.text, Locale.forLanguageTag(ttsLanguageTag))
        }
        if (isStaleEpoch(epoch, "speak")) return
        currentCoroutineContext().ensureActive()
        val speakFailure = outcome.exceptionOrNull()
        if (speakFailure is CancellationException) {
            _state.value = RayaState.Idle
            return
        }
        speakFailure?.let { failure ->
            failVoiceSession(failure.message ?: "Голосовой pipeline завершился с ошибкой.")
            return
        }

        if (!_voiceSessionActive.value || !_microphoneEnabled.value) {
            _state.value = RayaState.Idle
            return
        }
        beginVoiceTurn(resetActivity = true)
    }

    private fun markUserTurnIntent() {
        _userTurnRevision.value += 1
    }

    private fun launchInactivityMonitor() {
        val parent = sessionJob
        if (parent == null || !parent.isActive) return
        scope.launch(parent) {
            while (_voiceSessionActive.value) {
                delay(INACTIVITY_CHECK_INTERVAL_MS)
                if (!_voiceSessionActive.value) break
                val waitingForInput = _state.value == RayaState.Listening || _state.value == RayaState.Idle
                if (waitingForInput && now() - lastUserActivityAt >= inactivityTimeoutMs()) {
                    record(
                        "voice.inactivityTimeout turnEpoch=$turnEpoch " +
                            "inactivityMs=${now() - lastUserActivityAt}",
                    )
                    endVoiceSessionInternal(notice = true, reason = "inactivity")
                    break
                }
            }
        }
    }

    private fun endVoiceSessionInternal(notice: Boolean, reason: String) {
        val wasActive = _voiceSessionActive.value
        turnEpoch++
        sessionJob?.cancel()
        sessionJob = null
        speechRecognition.cancel()
        speechSynthesis.stop()
        _voiceSessionActive.value = false
        _microphoneEnabled.value = true
        _interactionMode.value = InteractionMode.Text
        _semanticEmotion.value = RayaEmotion.Calm
        _lastResponseLanguageTag.value = null
        _state.value = RayaState.Idle
        if (wasActive) {
            record(
                "voice.endSession reason=$reason notice=$notice turnEpoch=$turnEpoch " +
                    "state=${stateName()}",
            )
        }
        if (notice) {
            _conversation.value = appendMessage(
                ConversationMessage(ConversationRole.Notice, "Голосовой чат завершён из-за неактивности."),
            )
        }
    }

    private fun failVoiceSession(message: String) {
        record("voice.fatalSessionFailure error=$message")
        endVoiceSessionInternal(notice = false, reason = "fatal($message)")
        _state.value = RayaState.Error(message)
    }

    private fun appendMessage(message: ConversationMessage): List<ConversationMessage> =
        (_conversation.value + message).takeLast(MAX_CONVERSATION_MESSAGES)

    private fun conversationContext(): List<ConversationMessage> =
        _conversation.value
            .filter { it.role == ConversationRole.User || it.role == ConversationRole.Assistant }
            .takeLast(MAX_LLM_CONTEXT_MESSAGES)

    companion object {
        const val MAX_LLM_CONTEXT_MESSAGES = 20
        const val MAX_CONVERSATION_MESSAGES = 100
        const val USER_INACTIVITY_TIMEOUT_MS = 3 * 60 * 1000L
        const val INTERRUPT_TTS_STOP_DELAY_MS = 300L
        const val INACTIVITY_CHECK_INTERVAL_MS = 1_000L
        const val INACTIVITY_NOTICE_MESSAGE = "Голосовой чат завершён из-за неактивности."
    }
}
