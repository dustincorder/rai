package com.dustincorder.rai.data.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class OpenAiMessage(val role: String, val content: String)
@Serializable data class OpenAiRequest(val model: String, val messages: List<OpenAiMessage>)
@Serializable data class OpenAiResponse(val choices: List<OpenAiChoice> = emptyList())
@Serializable data class OpenAiChoice(val message: OpenAiMessage)

@Serializable data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<AnthropicMessage>,
)
@Serializable data class AnthropicMessage(val role: String, val content: String)
@Serializable data class AnthropicResponse(val content: List<AnthropicContent> = emptyList())
@Serializable data class AnthropicContent(val type: String, val text: String? = null)

class LlmConfigurationException(message: String) : IllegalStateException(message)
class LlmHttpException(val statusCode: Int, message: String) : IllegalStateException(message)
