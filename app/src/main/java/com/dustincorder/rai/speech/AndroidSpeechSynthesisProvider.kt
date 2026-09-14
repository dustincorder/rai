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
                activeSpeech?.resume(Unit)
                activeSpeech = null
            }

            override fun onError(utteranceId: String?) {
                activeSpeech?.resumeWithException(IllegalStateException("Ошибка синтеза речи."))
                activeSpeech = null
            }
        })
    }

    override suspend fun speak(text: String, locale: Locale) {
        ready.await()
        val languageResult = textToSpeech.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            error("Русский язык синтеза речи недоступен.")
        }

        suspendCancellableCoroutine { continuation ->
            activeSpeech = continuation
            val utteranceId = "raya-${utteranceCounter++}"
            val result = textToSpeech.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId,
            )
            if (result == TextToSpeech.ERROR) {
                activeSpeech = null
                continuation.resumeWithException(IllegalStateException("Не удалось запустить синтез речи."))
            }
            continuation.invokeOnCancellation {
                if (activeSpeech === continuation) {
                    activeSpeech = null
                    textToSpeech.stop()
                }
            }
        }
    }

    override fun stop() {
        textToSpeech.stop()
        activeSpeech?.cancel()
        activeSpeech = null
    }

    override fun shutdown() {
        textToSpeech.stop()
        textToSpeech.shutdown()
        activeSpeech?.cancel()
        activeSpeech = null
    }
}
