package com.dustincorder.rai.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.dustincorder.rai.domain.LocalNeuralTtsEngine
import com.dustincorder.rai.domain.TtsModelPack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Concrete sherpa-onnx OfflineTts VITS engine. Model files are supplied by an
 * explicitly installed pack; this class never downloads or substitutes Android TTS.
 */
class SherpaOnnxLocalNeuralTtsEngine(
    private val context: Context,
) : LocalNeuralTtsEngine {
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var activeTts: OfflineTts? = null

    override suspend fun speak(text: String, locale: Locale, pack: TtsModelPack) = withContext(Dispatchers.Default) {
        val root = pack.rootPath ?: error("TTS model pack root is missing.")
        val model = pack.modelPath ?: error("TTS model path is missing.")
        val tokens = pack.tokensPath ?: error("TTS tokens path is missing.")
        val modelConfig = OfflineTtsModelConfig().apply {
            vits = OfflineTtsVitsModelConfig(
                "$root/$model",
                "",
                "$root/$tokens",
                pack.dataDir ?: "",
                "",
                0.667f,
                0.8f,
                1.0f,
            )
        }
        val config = OfflineTtsConfig(modelConfig, "", "", 1, 1.0f)
        val tts = OfflineTts(context.assets, config)
        activeTts = tts
        try {
            val audio = tts.generate(text, 0, 1.0f)
            val format = AudioFormat.Builder()
                .setSampleRate(audio.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(audio.samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            activeTrack = track
            val samples = ShortArray(audio.samples.size) { index ->
                (audio.samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(samples, 0, samples.size)
            track.play()
            while (track.playbackHeadPosition < samples.size && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                kotlinx.coroutines.delay(20)
            }
            track.stop()
            track.release()
            activeTrack = null
        } finally {
            tts.release()
            activeTts = null
        }
    }

    override fun stop() {
        activeTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
        activeTts?.release()
        activeTts = null
    }

    override fun shutdown() = stop()
}
