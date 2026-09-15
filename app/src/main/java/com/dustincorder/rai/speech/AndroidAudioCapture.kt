package com.dustincorder.rai.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.dustincorder.rai.domain.AudioCapture
import com.dustincorder.rai.domain.AudioUtterance
import com.dustincorder.rai.domain.EndpointDecision
import com.dustincorder.rai.domain.VoiceActivityDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * One-shot PCM16 capture for STT v2. It never writes microphone audio to disk;
 * the returned in-memory buffer is owned by the caller and can be discarded after upload.
 */
class AndroidAudioCapture : AudioCapture {
    override suspend fun recordUtterance(
        endpointDetector: VoiceActivityDetector,
        sampleRateHz: Int,
        channels: Int,
    ): AudioUtterance = withContext(Dispatchers.IO) {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRateHz / 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer,
        )
        val bytes = ByteArrayOutputStream()
        val samples = ShortArray(minBuffer / 2)
        val echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)
        val noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)
        try {
            recorder.startRecording()
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) continue
                for (index in 0 until count) {
                    val sample = samples[index].toInt()
                    bytes.write(sample and 0xff)
                    bytes.write((sample ushr 8) and 0xff)
                }
                when (endpointDetector.acceptPcm16(samples.copyOf(count))) {
                    EndpointDecision.EndUtterance -> break
                    EndpointDecision.DropTooShort -> {
                        bytes.reset()
                        endpointDetector.reset()
                    }
                    EndpointDecision.Continue -> Unit
                }
            }
            AudioUtterance(
                bytes.toByteArray(),
                sampleRateHz,
                channels,
                endpointDetector.confirmedSpeechMs(),
                endpointDetector.voicedRatio(),
            )
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            echoCanceler?.release()
            noiseSuppressor?.release()
            bytes.reset()
        }
    }
}
