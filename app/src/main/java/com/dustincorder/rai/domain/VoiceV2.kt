package com.dustincorder.rai.domain

import kotlin.math.sqrt

/** Audio-only STT seam. Implementations own microphone permissions and platform APIs. */
interface AudioCapture {
    suspend fun recordUtterance(
        endpointDetector: VoiceActivityDetector,
        sampleRateHz: Int = 16_000,
        channels: Int = 1,
        initialPcm16: ByteArray = ByteArray(0),
    ): AudioUtterance
}

data class AudioUtterance(
    val pcm16: ByteArray,
    val sampleRateHz: Int,
    val channels: Int,
    val confirmedSpeechMs: Long = 0L,
    val voicedRatio: Double = 0.0,
)

data class TranscriptionResult(
    val text: String,
    val languageTag: String?,
)

fun interface SpeechFrameClassifier {
    fun isSpeech(frame: ShortArray): Boolean
}

interface SpeechTranscriptionProvider {
    suspend fun transcribe(
        audio: AudioUtterance,
        model: String,
        languageHint: String? = null,
    ): TranscriptionResult
}

enum class EndpointDecision {
    Continue,
    SpeechConfirmed,
    EndUtterance,
    DropTooShort,
}

/**
 * Small deterministic endpoint state machine. Short pauses remain inside an
 * utterance; trailing silence ends only after configured consecutive frames.
 */
class VoiceActivityDetector(
    private val sampleRateHz: Int = 16_000,
    private val minimumSpeechMs: Long = 250,
    private val trailingSilenceMs: Long = 1_400,
    private val maximumUtteranceMs: Long = 30_000,
    private val rmsThreshold: Double = 0.003,
    private val speechConfirmationMs: Long = 200,
    private val speechClassifier: SpeechFrameClassifier? = null,
) {
    private var elapsedMs = 0L
    private var speechMs = 0L
    private var silenceMs = 0L
    private var sawSpeech = false
    private var confirmedSpeech = false
    private var voicedFrames = 0L
    private var totalFrames = 0L

    fun acceptPcm16(frame: ShortArray): EndpointDecision {
        val frameMs = ((frame.size * 1_000L) / sampleRateHz).coerceAtLeast(1L)
        elapsedMs += frameMs
        val rms = rms(frame)
        totalFrames++
        var justConfirmed = false
        // Runtime Android supplies a real offline classifier; energy remains only the
        // deterministic fallback for platform/tests where no classifier is available.
        val speechFrame = speechClassifier?.isSpeech(frame) ?: (rms >= rmsThreshold)
        if (speechFrame && rms >= rmsThreshold) {
            voicedFrames++
            sawSpeech = true
            speechMs += frameMs
            silenceMs = 0L
            if (speechMs >= speechConfirmationMs && !confirmedSpeech) {
                confirmedSpeech = true
                justConfirmed = true
            }
        } else if (sawSpeech) {
            silenceMs += frameMs
        }
        if (elapsedMs >= maximumUtteranceMs) return EndpointDecision.EndUtterance
        if (sawSpeech && silenceMs >= trailingSilenceMs) {
            return if (confirmedSpeech && speechMs >= minimumSpeechMs) {
                EndpointDecision.EndUtterance
            } else {
                EndpointDecision.DropTooShort
            }
        }
        return if (justConfirmed) EndpointDecision.SpeechConfirmed else EndpointDecision.Continue
    }

    fun reset() {
        elapsedMs = 0L
        speechMs = 0L
        silenceMs = 0L
        sawSpeech = false
        confirmedSpeech = false
        voicedFrames = 0L
        totalFrames = 0L
    }

    fun confirmedSpeechMs(): Long = speechMs.takeIf { confirmedSpeech } ?: 0L
    fun voicedRatio(): Double = if (totalFrames == 0L) 0.0 else voicedFrames.toDouble() / totalFrames

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        frame.forEach { sample ->
            val normalized = sample / 32_768.0
            sum += normalized * normalized
        }
        return sqrt(sum / frame.size)
    }
}
