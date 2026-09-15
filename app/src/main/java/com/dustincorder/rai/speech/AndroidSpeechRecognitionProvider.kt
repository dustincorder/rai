package com.dustincorder.rai.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.dustincorder.rai.BuildConfig
import com.dustincorder.rai.domain.RecognitionAttemptAction
import com.dustincorder.rai.domain.RecognitionAttemptLifecycle
import com.dustincorder.rai.domain.RecognitionRequest
import com.dustincorder.rai.domain.SpeechRecognitionErrorReason
import com.dustincorder.rai.domain.SpeechRecognitionEvent
import com.dustincorder.rai.domain.SpeechRecognitionProvider
import com.dustincorder.rai.domain.toLanguagePlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Android STT adapter with serialized recognition-attempt ownership.
 *
 * The single shared [SpeechRecognizer] listener is untagged: callbacks caused by a `cancel()`
 * of one attempt can arrive after the next attempt was requested. Instead of guessing which
 * generation an error belongs to, this provider never lets a new attempt overlap with a
 * cancelled one (see [RecognitionAttemptLifecycle]):
 *
 *  - the pending attempt request is held back until the cancelled attempt acknowledges
 *    (terminal callback) - its callbacks are swallowed at the boundary meanwhile;
 *  - if no acknowledgement arrives within [CANCEL_SETTLE_TIMEOUT_MS], the recognizer instance
 *    is destroyed and recreated so no callback of the old source can ever reach the new
 *    attempt, and only then is the pending attempt started.
 *
 * All `SpeechRecognizer` lifecycle calls happen on the Android main thread.
 */
class AndroidSpeechRecognitionProvider(context: Context) : SpeechRecognitionProvider {
    private val appContext = context.applicationContext
    private val lifecycle = RecognitionAttemptLifecycle()
    private val lifecycleMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _events = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpeechRecognitionEvent> = _events.asSharedFlow()
    private var detectedLanguageTag: String? = null
    private var detectionSupported: Boolean? = null

    private val supportExecutor = Executors.newSingleThreadExecutor()

    private lateinit var recognizer: SpeechRecognizer
    private var settleAck: CompletableDeferred<Unit>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            debug("stt.onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            debug("stt.onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            debug("stt.onEndOfSpeech")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (partialResults?.firstText() != null) {
                debug("stt.onPartialResults")
                if (lifecycle.shouldForwardPartial) {
                    _events.tryEmit(SpeechRecognitionEvent.Partial(partialResults.firstText()!!))
                } else {
                    debug("stt.onPartialResults CANCELLED_ATTEMPT -> muted")
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val event = if (results?.firstText() != null) {
                debug("stt.onResults detectedLanguageTag=${detectedLanguageTag ?: "null"}")
                SpeechRecognitionEvent.Final(results.firstText()!!, detectedLanguageTag)
            } else {
                debug("stt.onResults EMPTY -> NoMatch")
                SpeechRecognitionEvent.Error(
                    SpeechRecognitionErrorReason.NoMatch,
                    "Речь не распознана.",
                )
            }
            onTerminal(event)
        }

        override fun onLanguageDetection(results: Bundle) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                detectedLanguageTag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                debug("stt.onLanguageDetection detectedLanguageTag=$detectedLanguageTag")
            }
        }

        override fun onError(error: Int) {
            val event = errorMessage(error)
            debug(
                "stt.onError code=$error reason=${event.reason} message=${event.message}",
            )
            onTerminal(event)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    init {
        recognizer = createRecognizer()
    }

    private fun createRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(listener)
        }

    override suspend fun startListening(request: RecognitionRequest) {
        lifecycleMutex.withLock {
            val decision = withContext(Dispatchers.Main.immediate) { lifecycle.onStartRequested() }
            when (decision) {
                RecognitionAttemptAction.StartNow -> startOnRecognizer(request)
                RecognitionAttemptAction.WaitForSettleThenStart -> {
                    val timedOut = withContext(Dispatchers.Main.immediate) {
                        beginCancellation()
                        awaitSettled()
                    }
                    if (timedOut) {
                        withContext(Dispatchers.Main.immediate) {
                            recreateRecognizer()
                            clearCancellation()
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        lifecycle.acknowledgeStart()
                        startOnRecognizer(request)
                    }
                }
            }
        }
    }

    override fun cancel() {
        mainHandler.post {
            debug("stt.cancel lifecycle=${lifecycle.state}")
            if (lifecycle.onCancelRequest()) {
                beginCancellation()
            } else {
                debug("stt.cancel no active attempt -> best-effort cancel")
                recognizer.cancel()
            }
        }
    }

    override fun release() {
        scope.cancel()
        mainHandler.post {
            recognizer.destroy()
            supportExecutor.shutdown()
        }
    }

    /** Must run on the main thread. */
    private fun beginCancellation() {
        if (settleAck == null) {
            settleAck = CompletableDeferred()
            debug("stt.beginCancellation awaiting acknowledgement generation=${lifecycle.generation}")
            recognizer.cancel()
        } else {
            debug("stt.beginCancellation already awaiting")
        }
    }

    private suspend fun awaitSettled(): Boolean {
        val ack = settleAck ?: return false
        val settled = withTimeoutOrNull(CANCEL_SETTLE_TIMEOUT_MS.milliseconds) {
            ack.await()
        }
        return settled == null
    }

    /** Must run on the main thread. */
    private fun completeCancellation() {
        settleAck?.complete(Unit)
        settleAck = null
        debug("stt.cancellationSettled")
    }

    /** Must run on the main thread. */
    private fun clearCancellation() {
        settleAck = null
    }

    /** Must run on the main thread. */
    private fun recreateRecognizer() {
        debug("stt.settleTimeout -> recreating recognizer generation=${lifecycle.generation}")
        recognizer.destroy()
        recognizer = createRecognizer()
    }

    private fun onTerminal(event: SpeechRecognitionEvent) {
        if (lifecycle.onTerminalCallback()) {
            _events.tryEmit(event)
        } else {
            debug("stt.onTerminal CANCELLED_ATTEMPT -> swallowed (cancellation acknowledgement)")
            completeCancellation()
        }
    }

    private suspend fun startOnRecognizer(request: RecognitionRequest) {
        detectedLanguageTag = null
        val supportsDetection = withContext(Dispatchers.Main.immediate) { detectionSupport() }
        val languagePlan = request.toLanguagePlan(supportsDetection)
        debug(
            "stt.startListening languagePlan=${languagePlan.languageTag ?: "null"} " +
                "enableDetection=${languagePlan.enableDetection} supportsDetection=$supportsDetection",
        )
        withContext(Dispatchers.Main.immediate) {
            try {
                recognizer.startListening(recognitionIntent(languagePlan.languageTag, languagePlan.enableDetection))
                debug("stt.startListening.success")
            } catch (unexpected: RuntimeException) {
                debug("stt.startListening.failure error=${unexpected.message ?: unexpected.javaClass.simpleName} -> fallbackAuto")
                val fallbackPlan = request.toLanguagePlan(false)
                recognizer.startListening(recognitionIntent(fallbackPlan.languageTag, enableDetection = false))
                debug("stt.startListening.success (fallbackAuto language=${fallbackPlan.languageTag ?: "null"})")
            }
        }
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
        val supported = withTimeoutOrNull(DETECTION_PROBE_TIMEOUT_MS.milliseconds) {
            suspendCancellableCoroutine<Boolean?> { continuation ->
                mainHandler.post {
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
        } ?: false
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
        const val TAG = "Raya-STT"
        const val DETECTION_PROBE_TIMEOUT_MS = 1_500
        const val CANCEL_SETTLE_TIMEOUT_MS = 250
    }

    private inline fun debug(message: String) {
        if (!BuildConfig.DEBUG) return
        try {
            Log.d(TAG, message)
        } catch (_: RuntimeException) {
            // android.util.Log is not mocked in JVM unit tests.
        }
    }
}