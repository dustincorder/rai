package com.dustincorder.rai.data.llm.tools

import com.dustincorder.rai.domain.tools.ModelRoundStep
import com.dustincorder.rai.domain.tools.ProviderSchemaProjection
import com.dustincorder.rai.domain.tools.ProviderSchemaProjector
import com.dustincorder.rai.domain.tools.ToolCall
import com.dustincorder.rai.domain.tools.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OpenAiToolAdapter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderSchemaProjector {

    override fun project(schema: JsonObject): ProviderSchemaProjection {
        // OpenAI natively accepts JSON Schema for function parameters
        return ProviderSchemaProjection.Supported(schema)
    }

    /**
     * Formats registered tools into the OpenAI `tools` array wire format:
     * [{"type": "function", "function": {"name": "...", "description": "...", "parameters": {...}}}]
     */
    fun formatToolsPayload(tools: List<ToolDefinition>): JsonArray = buildJsonArray {
        tools.filter { it.enabled }.forEach { tool ->
            when (val projection = project(tool.inputSchema)) {
                is ProviderSchemaProjection.Supported -> {
                    add(
                        buildJsonObject {
                            put("type", "function")
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", projection.projectedSchema)
                                },
                            )
                        }
                    )
                }
                is ProviderSchemaProjection.LosslesslyProjected -> {
                    add(
                        buildJsonObject {
                            put("type", "function")
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", "${tool.description} (${projection.description})")
                                    put("parameters", projection.projectedSchema)
                                },
                            )
                        }
                    )
                }
                is ProviderSchemaProjection.Unsupported -> {
                    // Tool cannot be represented safely; omitted from payload to avoid invalid requests
                }
            }
        }
    }

    /**
     * Extracts normalized ToolCalls from an OpenAI response message object.
     * Looks for choices[0].message.tool_calls.
     */
    fun parseToolCalls(messageObject: JsonObject): List<ToolCall> {
        val toolCallsElement = messageObject["tool_calls"] ?: return emptyList()
        val toolCallsArray = runCatching { toolCallsElement.jsonArray }.getOrNull() ?: return emptyList()

        return toolCallsArray.mapNotNull { item ->
            runCatching {
                val callObj = item.jsonObject
                val id = callObj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val functionObj = callObj["function"]?.jsonObject ?: return@mapNotNull null
                val name = functionObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val rawArgs = functionObj["arguments"]?.jsonPrimitive?.content ?: "{}"
                val parsedArgs = runCatching { json.parseToJsonElement(rawArgs).jsonObject }
                    .getOrDefault(buildJsonObject {})

                ToolCall(
                    callId = id,
                    toolName = name,
                    arguments = parsedArgs,
                    providerCorrelation = id,
                )
            }.getOrNull()
        }
    }

    /**
     * Formats intermediate model round steps (assistant tool calls + tool result feedbacks)
     * into OpenAI wire messages:
     * 1. Assistant message with tool_calls
     * 2. Messages with role="tool", tool_call_id, content
     */
    fun formatStepMessages(steps: List<ModelRoundStep>): List<JsonObject> {
        val messages = mutableListOf<JsonObject>()
        var pendingAssistantCalls: List<ToolCall>? = null

        steps.forEach { step ->
            when (step) {
                is ModelRoundStep.AssistantToolCalls -> {
                    pendingAssistantCalls = step.calls
                    messages.add(
                        buildJsonObject {
                            put("role", "assistant")
                            put(
                                "tool_calls",
                                buildJsonArray {
                                    step.calls.forEach { call ->
                                        add(
                                            buildJsonObject {
                                                put("id", call.callId)
                                                put("type", "function")
                                                put(
                                                    "function",
                                                    buildJsonObject {
                                                        put("name", call.toolName)
                                                        put("arguments", call.arguments.toString())
                                                    },
                                                )
                                            }
                                        )
                                    }
                                },
                            )
                        }
                    )
                }

                is ModelRoundStep.ToolExecutionFeedback -> {
                    messages.add(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", step.callId)
                            put("content", step.result.toString())
                        }
                    )
                }
            }
        }
        return messages
    }
}
