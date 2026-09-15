package com.dustincorder.rai.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.dustincorder.rai.domain.BargeInMonitor
import com.dustincorder.rai.domain.VoiceActivityDetector
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Conservative speaking-time monitor. VOICE_COMMUNICATION plus platform AEC/NS
 * reject most speaker leakage; 250ms VAD confirmation prevents clicks stopping TTS.
 */
class AndroidBargeInMonitor : BargeInMonitor {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null

    override fun start(onConfirmedSpeech: () -> Unit) {
        stop()
        running.set(true)
        worker = thread(name = "raya-barge-in", start = true) {
            val sampleRate = 16_000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(sampleRate / 2)
            val localRecorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }.getOrNull() ?: run {
                running.set(false)
                return@thread
            }
            recorder = localRecorder
            val echo = AcousticEchoCanceler.create(localRecorder.audioSessionId)
            val noise = NoiseSuppressor.create(localRecorder.audioSessionId)
            val detector = VoiceActivityDetector(
                sampleRateHz = sampleRate,
                minimumSpeechMs = 250,
                trailingSilenceMs = 1_400,
            )
            val buffer = ShortArray(bufferSize / 2)
            try {
                localRecorder.startRecording()
                while (running.get()) {
                    val count = localRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    if (detector.acceptPcm16(buffer.copyOf(count)) == com.dustincorder.rai.domain.EndpointDecision.SpeechConfirmed) {
                        onConfirmedSpeech()
                        detector.reset()
                    }
                }
            } finally {
                runCatching { localRecorder.stop() }
                localRecorder.release()
                echo?.release()
                noise?.release()
                recorder = null
            }
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        worker?.interrupt()
        worker = null
    }
}
