package com.dustincorder.rai.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dustincorder.rai.domain.SpeechSynthesisProvider
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidSpeechSynthesisProvider(context: Context) : SpeechSynthesisProvider {
    private val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var activeSpeech: CancellableContinuation<Unit>? = null
    private var activeUtteranceId: String? = null
    private var utteranceCounter = 0

    private val textToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready.complete(Unit)
        } else {
            ready.completeExceptionally(IllegalStateException("Не удалось инициализировать синтез речи."))
        }
    }.also { tts ->
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                completeActive(utteranceId) { it.resume(Unit) }
            }

            override fun onError(utteranceId: String?) {
                completeActive(utteranceId) {
                    it.resumeWithException(IllegalStateException("Ошибка синтеза речи."))
                }
            }
        })
    }

    override suspend fun speak(text: String, locale: Locale) {
        ready.await()
        val languageResult = textToSpeech.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            error("Язык синтеза речи ${locale.toLanguageTag()} недоступен.")
        }

        suspendCancellableCoroutine { continuation ->
            val utteranceId = "raya-${utteranceCounter++}"
            activeSpeech = continuation
            activeUtteranceId = utteranceId
            val result = textToSpeech.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId,
            )
            if (result == TextToSpeech.ERROR) {
                clearActive(continuation, utteranceId)
                continuation.resumeWithException(IllegalStateException("Не удалось запустить синтез речи."))
            }
            continuation.invokeOnCancellation {
                if (activeSpeech === continuation && activeUtteranceId == utteranceId) {
                    clearActive(continuation, utteranceId)
                    textToSpeech.stop()
                }
            }
        }
    }

    override fun stop() {
        textToSpeech.stop()
        activeSpeech?.cancel()
        clearActive()
    }

    override fun shutdown() {
        textToSpeech.stop()
        textToSpeech.shutdown()
        activeSpeech?.cancel()
        clearActive()
    }

    private fun completeActive(utteranceId: String?, completion: (CancellableContinuation<Unit>) -> Unit) {
        if (utteranceId == null || utteranceId != activeUtteranceId) return
        val continuation = activeSpeech ?: return
        clearActive()
        completion(continuation)
    }

    private fun clearActive(
        continuation: CancellableContinuation<Unit>? = null,
        utteranceId: String? = null,
    ) {
        if (continuation == null || (activeSpeech === continuation && activeUtteranceId == utteranceId)) {
            activeSpeech = null
            activeUtteranceId = null
        }
    }
}
