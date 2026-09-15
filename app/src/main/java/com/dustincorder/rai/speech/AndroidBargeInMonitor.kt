package com.dustincorder.rai.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.dustincorder.rai.domain.BargeInMonitor
import com.dustincorder.rai.domain.BargeInHandoff
import com.dustincorder.rai.domain.VoiceActivityDetector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * Conservative speaking-time monitor. VOICE_COMMUNICATION plus platform AEC/NS
 * reject most speaker leakage; 250ms VAD confirmation prevents clicks stopping TTS.
 */
class AndroidBargeInMonitor(
    context: android.content.Context,
    private val diagnostics: (String) -> Unit = {},
) : BargeInMonitor {
    private val speechClassifier = runCatching { SherpaSileroSpeechFrameClassifier(context.assets) }.getOrNull()
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null

    override fun start(onConfirmedSpeech: (BargeInHandoff) -> Unit) {
        stop()
        running.set(true)
        val callbackConsumed = AtomicBoolean(false)
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
            val echo = runCatching { AcousticEchoCanceler.create(localRecorder.audioSessionId) }.getOrNull()
            val noise = runCatching { NoiseSuppressor.create(localRecorder.audioSessionId) }.getOrNull()
            diagnostics("bargeIn.aecAvailable=${echo != null} aecEnabled=${echo?.enabled == true} " +
                "nsAvailable=${noise != null} nsEnabled=${noise?.enabled == true}")
            val detector = VoiceActivityDetector(
                sampleRateHz = sampleRate,
                minimumSpeechMs = 250,
                trailingSilenceMs = 1_400,
                speechClassifier = speechClassifier,
            )
            val buffer = ShortArray(bufferSize / 2)
            val preRoll = ArrayDeque<Short>()
            val maxPreRollSamples = sampleRate / 2
            try {
                localRecorder.startRecording()
                while (running.get()) {
                    val count = localRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    val frame = buffer.copyOf(count)
                    frame.forEach {
                        preRoll.addLast(it)
                        if (preRoll.size > maxPreRollSamples) preRoll.removeFirst()
                    }
                    val decision = detector.acceptPcm16(frame)
                    if (decision == com.dustincorder.rai.domain.EndpointDecision.SpeechConfirmed &&
                        callbackConsumed.compareAndSet(false, true)
                    ) {
                        val handoffBytes = ByteArray(preRoll.size * 2)
                        preRoll.forEachIndexed { index, sample ->
                            handoffBytes[index * 2] = (sample.toInt() and 0xff).toByte()
                            handoffBytes[index * 2 + 1] = (sample.toInt() ushr 8).toByte()
                        }
                        val handoff = BargeInHandoff(handoffBytes, sampleRate, 1)
                        onConfirmedSpeech(handoff)
                        return@thread
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
