package com.dustincorder.rai.data.settings

import com.dustincorder.rai.domain.ConversationLanguage
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class LlmProtocol { OpenAiCompatible, AnthropicCompatible }

enum class LlmProviderPreset(
    val protocol: LlmProtocol,
    val baseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean = true,
) {
    OpenAI(LlmProtocol.OpenAiCompatible, "https://api.openai.com/v1", "gpt-4o-mini"),
    Groq(LlmProtocol.OpenAiCompatible, "https://api.groq.com/openai/v1", "openai/gpt-oss-20b"),
    Anthropic(LlmProtocol.AnthropicCompatible, "https://api.anthropic.com/v1", "claude-sonnet-5"),
    Custom(LlmProtocol.OpenAiCompatible, "", "", false),
}

data class AppSettings(
    val provider: LlmProviderPreset = LlmProviderPreset.OpenAI,
    val customProtocol: LlmProtocol = LlmProtocol.OpenAiCompatible,
    val customBaseUrl: String = "",
    val modelId: String = LlmProviderPreset.OpenAI.defaultModel,
    val conversationLanguage: ConversationLanguage = ConversationLanguage.Auto,
) {
    val protocol: LlmProtocol get() = if (provider == LlmProviderPreset.Custom) customProtocol else provider.protocol
    val baseUrl: String get() = if (provider == LlmProviderPreset.Custom) customBaseUrl else provider.baseUrl
}

fun normalizeBaseUrl(value: String): String {
    val normalized = value.trim().trimEnd('/')
    val url = normalized.toHttpUrl()
    require(url.isHttps) { "Base URL должен использовать HTTPS." }
    require(url.username.isEmpty() && url.password.isEmpty()) { "Base URL не должен содержать credentials." }
    require(url.query == null && url.fragment == null) { "Base URL не должен содержать query или fragment." }
    return url.toString().trimEnd('/')
}

fun resolveEndpointUrl(baseUrl: String, suffixes: List<String>): HttpUrl {
    val url = normalizeBaseUrl(baseUrl).toHttpUrl()
    val suffixPath = suffixes.joinToString("/")
    val path = url.encodedPath.trimEnd('/')
    if (path == "/$suffixPath" || path.endsWith("/$suffixPath")) return url
    return url.newBuilder().apply { suffixes.forEach(::addPathSegment) }.build()
}
