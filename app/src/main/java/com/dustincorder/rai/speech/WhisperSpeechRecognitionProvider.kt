package com.dustincorder.rai.speech

import com.dustincorder.rai.domain.AudioCapture
import com.dustincorder.rai.domain.RecognitionRequest
import com.dustincorder.rai.domain.SpeechRecognitionErrorReason
import com.dustincorder.rai.domain.SpeechRecognitionEvent
import com.dustincorder.rai.domain.SpeechRecognitionProvider
import com.dustincorder.rai.domain.SpeechTranscriptionProvider
import com.dustincorder.rai.domain.VoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Real utterance-final STT v2 adapter:
 * AudioRecord -> VoiceActivityDetector -> Groq Whisper -> Final event.
 * No synthetic partials are emitted because Whisper only returns after endpointing.
 */
class WhisperSpeechRecognitionProvider(
    private val scope: CoroutineScope,
    private val audioCapture: AudioCapture,
    private val transcription: SpeechTranscriptionProvider,
    private val model: () -> String,
    private val languageHint: () -> String?,
    private val diagnostics: (String) -> Unit = {},
) : SpeechRecognitionProvider {
    private val _events = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpeechRecognitionEvent> = _events.asSharedFlow()
    private var captureJob: Job? = null

    override suspend fun startListening(request: RecognitionRequest) {
        if (captureJob?.isActive == true) return
        captureJob = scope.launch {
            val started = System.currentTimeMillis()
            try {
                val audio = audioCapture.recordUtterance(VoiceActivityDetector())
                val endpointAt = System.currentTimeMillis()
                diagnostics("stt.engine=groq-whisper model=${model()} endpointReason=vad endpointLatencyMs=${endpointAt - started}")
                if (audio.confirmedSpeechMs < 200L || audio.voicedRatio < 0.10) {
                    diagnostics("stt.discardedAsNoise=true confirmedSpeechMs=${audio.confirmedSpeechMs} voicedRatio=${audio.voicedRatio}")
                    _events.emit(SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.NoSpeech, "Audio did not contain confirmed speech."))
                    return@launch
                }
                val result = transcription.transcribe(audio, model(), languageHint())
                diagnostics("stt.engine=groq-whisper model=${model()} detectedLanguage=${result.languageTag ?: "null"} transcriptionLatencyMs=${System.currentTimeMillis() - endpointAt}")
                if (result.text.isNotBlank()) {
                    _events.emit(SpeechRecognitionEvent.Final(result.text, result.languageTag))
                } else {
                    _events.emit(SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.NoMatch, "Whisper returned no transcript."))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                diagnostics("stt.engine=groq-whisper cancellation=cancelled")
                throw cancelled
            } catch (failure: Throwable) {
                diagnostics("stt.engine=groq-whisper error=${failure::class.java.simpleName}")
                _events.emit(SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.Network, "Whisper transcription failed."))
            }
        }
    }

    override fun cancel() {
        captureJob?.cancel()
        captureJob = null
    }

    override fun release() = cancel()
}

/** Runtime selector. Cloud failures are surfaced; fallback happens only when Groq is unavailable. */
class RuntimeSpeechRecognitionProvider(
    private val scope: CoroutineScope,
    private val settings: com.dustincorder.rai.data.settings.SettingsRepository,
    private val keys: com.dustincorder.rai.data.secrets.ApiKeyStore,
    private val groq: SpeechRecognitionProvider,
    private val system: SpeechRecognitionProvider,
    private val diagnostics: (String) -> Unit = {},
) : SpeechRecognitionProvider {
    private val _events = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpeechRecognitionEvent> = _events.asSharedFlow()
    private var active: SpeechRecognitionProvider? = null
    private var forwardJob: Job? = null

    override suspend fun startListening(request: RecognitionRequest) {
        val configured = settings.settings.first()
        val selected = if (configured.sttEngine == com.dustincorder.rai.data.settings.SttEngine.GroqWhisper) {
            val key = runCatching { keys.read(com.dustincorder.rai.data.settings.LlmProviderPreset.Groq) }.getOrNull()
            if (key.isNullOrBlank()) {
                diagnostics("stt.engine=system fallbackReason=groq-key-missing")
                system
            } else {
                diagnostics("stt.engine=groq-whisper model=${configured.sttModelId}")
                groq
            }
        } else {
            diagnostics("stt.engine=system selectedBySettings=true")
            system
        }
        active?.cancel()
        forwardJob?.cancel()
        active = selected
        forwardJob = scope.launch {
            selected.events.collect { _events.emit(it) }
        }
        kotlinx.coroutines.yield()
        selected.startListening(request)
    }

    override fun cancel() {
        active?.cancel()
        forwardJob?.cancel()
        active = null
        forwardJob = null
    }

    override fun release() {
        cancel()
        system.release()
        groq.release()
    }
}
