package com.dustincorder.rai.speech

import com.dustincorder.rai.domain.LocalNeuralTtsEngine
import com.dustincorder.rai.domain.LocalTtsUnavailableException
import com.dustincorder.rai.domain.SpeechSynthesisProvider
import com.dustincorder.rai.domain.TtsModelPackStore
import com.dustincorder.rai.domain.normalizeForTts
import java.util.Locale

/**
 * Local neural seam. It deliberately fails when no real engine/model is installed;
 * it never pretends that Android TTS is local neural inference.
 */
class LocalNeuralSpeechSynthesisProvider(
    private val packs: TtsModelPackStore,
    private val engine: LocalNeuralTtsEngine?,
) : SpeechSynthesisProvider {
    override suspend fun speak(text: String, locale: Locale) {
        val actualEngine = engine ?: throw LocalTtsUnavailableException("Local neural TTS engine is unavailable.")
        val pack = packs.installed(locale.toLanguageTag())
            ?: throw LocalTtsUnavailableException("No local TTS model for ${locale.toLanguageTag()}.")
        actualEngine.speak(normalizeForTts(text, locale), locale, pack)
    }

    override fun stop() {
        engine?.stop()
    }

    override fun shutdown() {
        engine?.shutdown()
    }
}

/** LocalNeural-first adapter with explicit System fallback. */
class FallbackSpeechSynthesisProvider(
    private val local: SpeechSynthesisProvider,
    private val system: SpeechSynthesisProvider,
) : SpeechSynthesisProvider {
    override suspend fun speak(text: String, locale: Locale) {
        try {
            local.speak(text, locale)
        } catch (_: LocalTtsUnavailableException) {
            system.speak(normalizeForTts(text, locale), locale)
        }
    }

    override fun stop() {
        local.stop()
        system.stop()
    }

    override fun shutdown() {
        local.shutdown()
        system.shutdown()
    }
}
