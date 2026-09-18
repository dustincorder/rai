package com.dustincorder.rai.domain.tools

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.RayaResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Invoker abstraction enabling ToolTurnRunner to execute multi-round model turns
 * with streaming deltas, 0..N tool calls, and tool result feedback.
 */
interface ModelTurnInvoker {
    fun streamRound(
        messages: List<ConversationMessage>,
        candidateTools: List<ToolDefinition>,
        steps: List<ModelRoundStep>,
        languageTag: String?,
    ): Flow<ModelRoundStreamEvent>
}

sealed interface ModelRoundStreamEvent {
    data class ExposedTools(val tools: List<ToolDefinition>) : ModelRoundStreamEvent
    data class TextDelta(val text: String) : ModelRoundStreamEvent
    data class ToolCalls(val calls: List<ToolCall>) : ModelRoundStreamEvent
    data class Completed(val response: RayaResponse) : ModelRoundStreamEvent
}

sealed interface ModelRoundStep {
    data class AssistantToolCalls(val calls: List<ToolCall>) : ModelRoundStep
    data class ToolExecutionFeedback(
        val callId: String,
        val toolName: String,
        val result: JsonObject,
        val providerCorrelation: String? = null,
    ) : ModelRoundStep
}
