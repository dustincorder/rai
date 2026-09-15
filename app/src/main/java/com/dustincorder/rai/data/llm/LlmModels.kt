package com.dustincorder.rai.data.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException

@Serializable data class OpenAiMessage(val role: String, val content: String)
@Serializable data class OpenAiRequest(val model: String, val messages: List<OpenAiMessage>, val stream: Boolean = false)
@Serializable data class OpenAiResponse(val choices: List<OpenAiChoice> = emptyList())
@Serializable data class OpenAiChoice(val message: OpenAiMessage)
@Serializable data class OpenAiStreamResponse(val choices: List<OpenAiStreamChoice> = emptyList())
@Serializable data class OpenAiStreamChoice(val delta: OpenAiDelta = OpenAiDelta())
@Serializable data class OpenAiDelta(val content: String? = null)

@Serializable data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<AnthropicMessage>,
)
@Serializable data class AnthropicMessage(val role: String, val content: String)
@Serializable data class AnthropicResponse(val content: List<AnthropicContent> = emptyList())
@Serializable data class AnthropicContent(val type: String, val text: String? = null)

@Serializable data class ProviderErrorEnvelope(val error: ProviderErrorDetail? = null)
@Serializable data class ProviderErrorDetail(val message: String? = null, val type: String? = null)

class LlmConfigurationException(message: String) : IllegalStateException(message)
class LlmHttpException(val statusCode: Int, val providerMessage: String? = null) : IOException("HTTP $statusCode")
class LlmSafeException(message: String) : RuntimeException(message)
class LlmTransportException(message: String) : IOException(message)
