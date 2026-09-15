package com.dustincorder.rai.domain

import java.util.Locale

enum class TtsEngine {
    LocalNeural,
    System,
}

data class TtsModelPack(
    val id: String,
    val version: String,
    val languageTags: Set<String>,
    val sha256: String,
    val byteSize: Long,
)

interface TtsModelPackStore {
    suspend fun installed(languageTag: String): TtsModelPack?
    suspend fun install(pack: TtsModelPack, source: java.io.InputStream)
    suspend fun delete(packId: String)
}

interface LocalNeuralTtsEngine {
    suspend fun speak(text: String, locale: Locale, pack: TtsModelPack)
    fun stop()
    fun shutdown()
}

class LocalTtsUnavailableException(message: String) : Exception(message)

/** Conservative TTS-only normalization; visible conversation text remains unchanged. */
fun normalizeForTts(text: String, locale: Locale): String {
    var normalized = text
        .replace(Regex("https?://\\S+"), "ссылка")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (locale.language == "ru") {
        normalized = normalized
            .replace(Regex("\\b(\\d{1,2}):(\\d{2})\\b"), "$1 часов $2 минут")
            .replace("₽", " рублей")
    } else if (locale.language == "uk") {
        normalized = normalized.replace("₴", " гривень")
    } else if (locale.language == "en") {
        normalized = normalized.replace("$", " dollars")
    }
    return normalized
}
