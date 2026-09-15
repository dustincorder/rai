package com.dustincorder.rai.domain

import kotlin.math.sqrt

/** Audio-only STT seam. Implementations own microphone permissions and platform APIs. */
interface AudioCapture {
    suspend fun recordUtterance(
        endpointDetector: VoiceActivityDetector,
        sampleRateHz: Int = 16_000,
        channels: Int = 1,
    ): AudioUtterance
}

data class AudioUtterance(
    val pcm16: ByteArray,
    val sampleRateHz: Int,
    val channels: Int,
)

data class TranscriptionResult(
    val text: String,
    val languageTag: String?,
)

interface SpeechTranscriptionProvider {
    suspend fun transcribe(
        audio: AudioUtterance,
        model: String,
        languageHint: String? = null,
    ): TranscriptionResult
}

enum class EndpointDecision {
    Continue,
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
    private val trailingSilenceMs: Long = 850,
    private val maximumUtteranceMs: Long = 30_000,
    private val rmsThreshold: Double = 0.015,
) {
    private var elapsedMs = 0L
    private var speechMs = 0L
    private var silenceMs = 0L
    private var sawSpeech = false

    fun acceptPcm16(frame: ShortArray): EndpointDecision {
        val frameMs = ((frame.size * 1_000L) / sampleRateHz).coerceAtLeast(1L)
        elapsedMs += frameMs
        val rms = rms(frame)
        if (rms >= rmsThreshold) {
            sawSpeech = true
            speechMs += frameMs
            silenceMs = 0L
        } else if (sawSpeech) {
            silenceMs += frameMs
        }
        if (elapsedMs >= maximumUtteranceMs) return EndpointDecision.EndUtterance
        if (sawSpeech && silenceMs >= trailingSilenceMs) {
            return if (speechMs >= minimumSpeechMs) EndpointDecision.EndUtterance else EndpointDecision.DropTooShort
        }
        return EndpointDecision.Continue
    }

    fun reset() {
        elapsedMs = 0L
        speechMs = 0L
        silenceMs = 0L
        sawSpeech = false
    }

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
