package com.dustincorder.rai.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.dustincorder.rai.domain.SpeechRecognitionEvent
import com.dustincorder.rai.domain.SpeechRecognitionProvider
import com.dustincorder.rai.domain.ConversationLanguage
import com.dustincorder.rai.domain.RecognitionRequest
import com.dustincorder.rai.domain.toLanguagePlan
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

class AndroidSpeechRecognitionProvider(context: Context) : SpeechRecognitionProvider {
    private val _events = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpeechRecognitionEvent> = _events.asSharedFlow()
    private var detectedLanguageTag: String? = null

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
                    ?: _events.tryEmit(SpeechRecognitionEvent.Error("Речь не распознана."))
            }

            override fun onLanguageDetection(results: Bundle) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    detectedLanguageTag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                }
            }

            override fun onError(error: Int) {
                _events.tryEmit(SpeechRecognitionEvent.Error(errorMessage(error)))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    override fun startListening(request: RecognitionRequest) {
        detectedLanguageTag = null
        val supportsDetection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val languagePlan = request.toLanguagePlan(supportsDetection)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            languagePlan.languageTag?.let {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it)
            }
            if (languagePlan.enableDetection) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            }
        }
        recognizer.startListening(intent)
    }

    override fun cancel() {
        recognizer.cancel()
    }

    override fun release() {
        recognizer.destroy()
    }

    private fun Bundle.firstText(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Не удалось распознать речь."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не удалось услышать речь."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Сервис распознавания недоступен."
        else -> "Ошибка распознавания речи ($error)."
    }
}
