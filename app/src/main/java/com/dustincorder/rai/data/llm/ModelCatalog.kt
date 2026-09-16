package com.dustincorder.rai.data.llm

/** Capability buckets used to keep model selectors honest per provider. */
enum class LlmModelCapability {
    Chat,
    Stt,
    TtsAudio,
    Embedding,
    Other,
}

data class DiscoveredModel(
    val id: String,
    val label: String? = null,
    val capabilities: Set<LlmModelCapability> = setOf(LlmModelCapability.Chat),
) {
    val displayName: String get() = label?.takeIf { it.isNotBlank() } ?: id
    fun supports(capability: LlmModelCapability): Boolean = capability in capabilities
}

/** Lifecycle of a provider model list: live data, cached data, or a failure keeping cache. */
sealed interface ModelListState {
    data object Loading : ModelListState
    data class Loaded(val models: List<DiscoveredModel>) : ModelListState
    data class Cached(val models: List<DiscoveredModel>) : ModelListState
    data class Failed(val message: String, val cached: List<DiscoveredModel>) : ModelListState
}

/** Chat-capable subset for the LLM model dropdown. */
fun List<DiscoveredModel>.chatModels(): List<DiscoveredModel> =
    filter { it.supports(LlmModelCapability.Chat) }

/** STT-capable subset for the voice transcription dropdown. */
fun List<DiscoveredModel>.sttModels(): List<DiscoveredModel> =
    filter { it.supports(LlmModelCapability.Stt) }

/**
 * Keeps the currently selected model usable even when discovery no longer returns it.
 * Returns the original list when the selection is present, otherwise appends a marker entry.
 */
fun List<DiscoveredModel>.withSelectedPresent(selectedId: String): List<DiscoveredModel> {
    if (selectedId.isBlank() || any { it.id == selectedId }) return this
    return this + DiscoveredModel(
        id = selectedId,
        label = "$selectedId (saved)",
        capabilities = setOf(LlmModelCapability.Chat),
    )
}

/** Shared id-based classifier for OpenAI-style /models payloads. */
fun classifyOpenAiStyleModel(id: String): Set<LlmModelCapability> {
    val lower = id.lowercase()
    return when {
        "whisper" in lower -> setOf(LlmModelCapability.Stt)
        lower.startsWith("tts") || "tts" in lower ||
            "text-to-speech" in lower || "orpheus" in lower -> setOf(LlmModelCapability.TtsAudio)
        "embed" in lower -> setOf(LlmModelCapability.Embedding)
        "moderation" in lower || "omni-moderation" in lower -> setOf(LlmModelCapability.Other)
        "dall-e" in lower || "image" in lower -> setOf(LlmModelCapability.Other)
        "realtime" in lower || "audio" in lower -> setOf(LlmModelCapability.TtsAudio)
        else -> setOf(LlmModelCapability.Chat)
    }
}

/** Groq chat selector must never offer transcription models. */
fun List<DiscoveredModel>.groqChatModels(): List<DiscoveredModel> = chatModels()

/** Gemini classifier driven by supportedGenerationMethods metadata. */
fun classifyGeminiModel(id: String, supportedMethods: List<String>): Set<LlmModelCapability> {
    val lower = id.lowercase()
    if ("embedding" in lower || "embed" in lower.substringAfterLast('/')) {
        return setOf(LlmModelCapability.Embedding)
    }
    val methods = supportedMethods.map { it.lowercase() }.toSet()
    return when {
        "generatecontent" in methods -> setOf(LlmModelCapability.Chat)
        "embedcontent" in methods -> setOf(LlmModelCapability.Embedding)
        else -> setOf(LlmModelCapability.Other)
    }
}
