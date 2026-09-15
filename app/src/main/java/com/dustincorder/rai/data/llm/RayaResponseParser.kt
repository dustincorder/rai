package com.dustincorder.rai.data.llm

import com.dustincorder.rai.domain.RayaEmotion
import com.dustincorder.rai.domain.RayaResponse
import com.dustincorder.rai.domain.isValidLanguageTag
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
private data class RayaStructured(
    val text: String? = null,
    val emotion: String? = null,
    val language: String? = null,
)

private val RAYA_STRUCTURED_JSON = Json { ignoreUnknownKeys = true }

/**
 * Parses the raw transport content into the single domain response model. Unsupported
 * emotions and invalid/missing language tags degrade safely; plain text responses are
 * kept as-is instead of being shown as JSON. A structured object without [RayaStructured.text]
 * is treated as a provider error so raw JSON never reaches the user as a normal reply.
 */
fun parseRayaResponse(raw: String, json: Json = RAYA_STRUCTURED_JSON): RayaResponse {
    val source = raw.trim()
    val structured = runCatching {
        json.decodeFromString<RayaStructured>(stripMarkdownFence(source))
    }.getOrNull()
    val text = structured?.text?.trim().orEmpty()
    if (structured != null && text.isBlank()) {
        throw LlmSafeException("Ответ модели не содержит текста.")
    }
    if (structured == null || text.isBlank()) {
        return RayaResponse(text = source, emotion = RayaEmotion.Calm, languageTag = null)
    }
    return RayaResponse(
        text = text,
        emotion = structured.emotion.toRayaEmotion(),
        languageTag = structured.language?.takeIf { it.isValidLanguageTag() },
    )
}

/** Removes a leading ``` or ```json fence and the trailing ``` guard if present. */
private fun stripMarkdownFence(input: String): String {
    val trimmed = input.trim()
    val firstNewline = trimmed.indexOf('\n')
    val firstLine = if (firstNewline >= 0) trimmed.substring(0, firstNewline).trim() else trimmed
    if (!firstLine.startsWith("```")) return input
    val body = trimmed.substringAfter('\n').trim()
    return if (body.endsWith("```")) {
        body.substring(0, body.length - 3).trim()
    } else {
        body
    }
}

fun String?.toRayaEmotion(): RayaEmotion = when (this?.trim()?.lowercase()) {
    "calm" -> RayaEmotion.Calm
    "happy" -> RayaEmotion.Happy
    "excited" -> RayaEmotion.Excited
    "playful" -> RayaEmotion.Playful
    "curious" -> RayaEmotion.Curious
    "thinking" -> RayaEmotion.Thinking
    "skeptical" -> RayaEmotion.Skeptical
    "confused" -> RayaEmotion.Confused
    "concerned" -> RayaEmotion.Concerned
    "sad" -> RayaEmotion.Sad
    "embarrassed" -> RayaEmotion.Embarrassed
    "surprised" -> RayaEmotion.Surprised
    "angry" -> RayaEmotion.Angry
    "annoyed" -> RayaEmotion.Annoyed
    "tired" -> RayaEmotion.Tired
    else -> RayaEmotion.Calm
}
