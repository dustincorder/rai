package com.dustincorder.rai.domain

enum class RayaEmotion {
    Calm,
    Happy,
    Curious,
    Concerned,
    Surprised,
    Angry,
}

/**
 * Structured domain response produced by an LLM transport.
 *
 * [text] is the only part persisted into the conversation history. [emotion] drives
 * the face expression of the app, [languageTag] (validated BCP-47) drives the voice
 * TTS locale and is kept for future UI/debug purposes.
 */
data class RayaResponse(
    val text: String,
    val emotion: RayaEmotion = RayaEmotion.Calm,
    val languageTag: String? = null,
)