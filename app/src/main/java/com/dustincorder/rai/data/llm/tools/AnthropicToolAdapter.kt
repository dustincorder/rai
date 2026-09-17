package com.dustincorder.rai.data.llm.tools

import com.dustincorder.rai.domain.tools.ModelRoundStep
import com.dustincorder.rai.domain.tools.ProviderSchemaProjection
import com.dustincorder.rai.domain.tools.ProviderSchemaProjector
import com.dustincorder.rai.domain.tools.ToolCall
import com.dustincorder.rai.domain.tools.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AnthropicToolAdapter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderSchemaProjector {

    override fun project(schema: JsonObject): ProviderSchemaProjection {
        // Anthropic natively supports JSON Schema in input_schema
        return ProviderSchemaProjection.Supported(schema)
    }

    /**
     * Formats tools into Anthropic `tools` array wire format:
     * [{"name": "...", "description": "...", "input_schema": {...}}]
     */
    fun formatToolsPayload(tools: List<ToolDefinition>): JsonArray = buildJsonArray {
        tools.filter { it.enabled }.forEach { tool ->
            when (val projection = project(tool.inputSchema)) {
                is ProviderSchemaProjection.Supported -> {
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", projection.projectedSchema)
                        }
                    )
                }
                is ProviderSchemaProjection.LosslesslyProjected -> {
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", "${tool.description} (${projection.description})")
                            put("input_schema", projection.projectedSchema)
                        }
                    )
                }
                is ProviderSchemaProjection.Unsupported -> {
                    // Tool omitted safely
                }
            }
        }
    }

    /**
     * Parses tool_use blocks from Anthropic response content array:
     * [{"type": "tool_use", "id": "...", "name": "...", "input": {...}}]
     */
    fun parseToolCalls(contentArray: JsonArray): List<ToolCall> {
        return contentArray.mapNotNull { item ->
            runCatching {
                val block = item.jsonObject
                if (block["type"]?.jsonPrimitive?.content != "tool_use") return@mapNotNull null
                val id = block["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = block["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val inputObj = block["input"]?.jsonObject ?: buildJsonObject {}

                ToolCall(
                    callId = id,
                    toolName = name,
                    arguments = inputObj,
                    providerCorrelation = id,
                )
            }.getOrNull()
        }
    }

    /**
     * Formats intermediate steps into Anthropic wire messages:
     * - Assistant message with content: [{"type": "tool_use", ...}]
     * - User message with content: [{"type": "tool_result", "tool_use_id": id, "content": ...}]
     */
    fun formatStepMessages(steps: List<ModelRoundStep>): List<JsonObject> {
        val messages = mutableListOf<JsonObject>()
        var currentToolResults = mutableListOf<JsonObject>()

        steps.forEach { step ->
            when (step) {
                is ModelRoundStep.AssistantToolCalls -> {
                    if (currentToolResults.isNotEmpty()) {
                        messages.add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", JsonArray(currentToolResults))
                            }
                        )
                        currentToolResults = mutableListOf()
                    }

                    messages.add(
                        buildJsonObject {
                            put("role", "assistant")
                            put(
                                "content",
                                buildJsonArray {
                                    step.calls.forEach { call ->
                                        add(
                                            buildJsonObject {
                                                put("type", "tool_use")
                                                put("id", call.callId)
                                                put("name", call.toolName)
                                                put("input", call.arguments)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                }

                is ModelRoundStep.ToolExecutionFeedback -> {
                    currentToolResults.add(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", step.callId)
                            put("content", step.result.toString())
                        }
                    )
                }
            }
        }

        if (currentToolResults.isNotEmpty()) {
            messages.add(
                buildJsonObject {
                    put("role", "user")
                    put("content", JsonArray(currentToolResults))
                }
            )
        }

        return messages
    }
}
