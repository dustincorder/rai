package com.dustincorder.rai.domain.tools

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.RayaResponse
import kotlinx.serialization.json.JsonObject

/**
 * Invoker abstraction enabling ToolTurnRunner to execute multi-round model turns
 * with 0..N tool calls and tool result feedback.
 */
interface ModelTurnInvoker {
    suspend fun invokeRound(
        messages: List<ConversationMessage>,
        activeTools: List<ToolDefinition>,
        steps: List<ModelRoundStep>,
        languageTag: String?,
    ): ModelRoundResponse
}

sealed interface ModelRoundResponse {
    data class FinalReply(val response: RayaResponse) : ModelRoundResponse
    data class ToolCalls(val calls: List<ToolCall>) : ModelRoundResponse
}

sealed interface ModelRoundStep {
    data class AssistantToolCalls(val calls: List<ToolCall>) : ModelRoundStep
    data class ToolExecutionFeedback(
        val callId: String,
        val toolName: String,
        val result: JsonObject,
    ) : ModelRoundStep
}
