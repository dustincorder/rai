package com.dustincorder.rai.speech

import android.content.res.AssetManager
import com.dustincorder.rai.domain.SpeechFrameClassifier
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Offline Silero VAD model bundled from MIT-licensed onnx-community/silero-vad. */
class SherpaSileroSpeechFrameClassifier(assetManager: AssetManager) : SpeechFrameClassifier {
    private val available = AtomicBoolean(true)
    private val vad: Vad? = runCatching {
        val silero = SileroVadModelConfig("silero_vad.onnx", 0.5f, 1.4f, 0.2f, 512, 30f)
        Vad(
            assetManager,
            VadModelConfig(silero, TenVadModelConfig(), 16_000, 1, "cpu", false),
        )
    }.getOrNull()

    override fun isSpeech(frame: ShortArray): Boolean {
        val model = vad ?: return false
        if (!available.get()) return false
        val audio = FloatArray(frame.size) { frame[it] / 32_768f }
        return runCatching {
            model.acceptWaveform(audio)
            model.isSpeechDetected()
        }.getOrElse {
            available.set(false)
            false
        }
    }
}
