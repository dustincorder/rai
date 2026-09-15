package com.dustincorder.rai.speech

import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.domain.LocalTtsUnavailableException
import com.dustincorder.rai.domain.SpeechSynthesisProvider
import com.dustincorder.rai.domain.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.Locale

/** Runtime TTS selector. Local failures are explicit in debug diagnostics and use System fallback. */
class RuntimeSpeechSynthesisProvider(
    private val settings: SettingsRepository,
    private val local: SpeechSynthesisProvider,
    private val system: SpeechSynthesisProvider,
    private val diagnostics: (String) -> Unit = {},
) : SpeechSynthesisProvider {
    override suspend fun speak(text: String, locale: Locale) {
        val selected = settings.settings.first().ttsEngine
        if (selected == TtsEngine.System) {
            diagnostics("tts.engine=system language=${locale.toLanguageTag()}")
            system.speak(text, locale)
            return
        }
        try {
            diagnostics("tts.engine=local-neural language=${locale.toLanguageTag()}")
            local.speak(text, locale)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            diagnostics("tts.engine=system language=${locale.toLanguageTag()} fallbackReason=${failure.message ?: "local-unavailable"}")
            system.speak(text, locale)
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
