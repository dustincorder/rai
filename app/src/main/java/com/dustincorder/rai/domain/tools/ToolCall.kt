package com.dustincorder.rai.domain.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Normalized representation of a tool call request emitted by an LLM.
 *
 * Designed to cleanly handle 0..N tool calls per model round without leaking
 * provider-specific wire DTOs (OpenAI/Anthropic/Gemini) into the domain.
 */
@Serializable
data class ToolCall(
    val callId: String,
    val toolName: String,
    val arguments: JsonObject,
    val providerCorrelation: String? = null,
)
