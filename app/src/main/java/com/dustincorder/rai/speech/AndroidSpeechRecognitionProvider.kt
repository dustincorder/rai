package com.dustincorder.rai.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.dustincorder.rai.domain.RecognitionRequest
import com.dustincorder.rai.domain.SpeechRecognitionErrorReason
import com.dustincorder.rai.domain.SpeechRecognitionEvent
import com.dustincorder.rai.domain.SpeechRecognitionProvider
import com.dustincorder.rai.domain.toLanguagePlan
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class AndroidSpeechRecognitionProvider(context: Context) : SpeechRecognitionProvider {
    private val _events = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpeechRecognitionEvent> = _events.asSharedFlow()
    private var detectedLanguageTag: String? = null
    private var detectionSupported: Boolean? = null

    private val supportExecutor = Executors.newSingleThreadExecutor()

    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
        setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.firstText()?.let { _events.tryEmit(SpeechRecognitionEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                results?.firstText()?.let { _events.tryEmit(SpeechRecognitionEvent.Final(it, detectedLanguageTag)) }
                    ?: _events.tryEmit(
                        SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.NoMatch, "Речь не распознана."),
                    )
            }

            override fun onLanguageDetection(results: Bundle) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    detectedLanguageTag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                }
            }

            override fun onError(error: Int) {
                _events.tryEmit(errorMessage(error))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    override suspend fun startListening(request: RecognitionRequest) {
        detectedLanguageTag = null
        val supportsDetection = detectionSupport()
        val languagePlan = request.toLanguagePlan(supportsDetection)
        val intent = recognitionIntent(languagePlan.languageTag, languagePlan.enableDetection)
        try {
            recognizer.startListening(intent)
        } catch (unexpected: RuntimeException) {
            val fallbackPlan = request.toLanguagePlan(false)
            recognizer.startListening(recognitionIntent(fallbackPlan.languageTag, enableDetection = false))
        }
    }

    override fun cancel() {
        recognizer.cancel()
    }

    override fun release() {
        recognizer.destroy()
        supportExecutor.shutdown()
    }

    private fun recognitionIntent(languageTag: String?, enableDetection: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            languageTag?.let {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it)
            }
            if (enableDetection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            }
        }

    private suspend fun detectionSupport(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        detectionSupported?.let { return it }
        val supported = runCatching {
            withTimeout(DETECTION_PROBE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        recognizer.checkRecognitionSupport(
                            recognitionIntent(languageTag = null, enableDetection = true),
                            supportExecutor,
                            object : RecognitionSupportCallback {
                                override fun onSupportResult(support: RecognitionSupport) {
                                    if (continuation.isActive) continuation.resume(true)
                                }

                                override fun onError(error: Int) {
                                    if (continuation.isActive) continuation.resume(false)
                                }
                            },
                        )
                    } catch (unexpected: Throwable) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
        }.getOrDefault(false)
        detectionSupported = supported
        return supported
    }

    private fun Bundle.firstText(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun errorMessage(error: Int): SpeechRecognitionEvent.Error = when (error) {
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechRecognitionEvent.Error(
            SpeechRecognitionErrorReason.NoSpeech,
            "Не удалось услышать речь.",
        )
        SpeechRecognizer.ERROR_NO_MATCH -> SpeechRecognitionEvent.Error(
            SpeechRecognitionErrorReason.NoMatch,
            "Не удалось распознать речь.",
        )
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechRecognitionEvent.Error(
            SpeechRecognitionErrorReason.Permission,
            "Нет разрешения на микрофон.",
        )
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> SpeechRecognitionEvent.Error(
            SpeechRecognitionErrorReason.Network,
            "Сервис распознавания недоступен.",
        )
        else -> SpeechRecognitionEvent.Error(
            SpeechRecognitionErrorReason.Other,
            "Ошибка распознавания речи ($error).",
        )
    }

    private companion object {
        const val DETECTION_PROBE_TIMEOUT_MS = 1_500L
    }
}