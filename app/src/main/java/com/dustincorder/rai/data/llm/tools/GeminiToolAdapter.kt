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

class GeminiToolAdapter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderSchemaProjector {

    override fun project(schema: JsonObject): ProviderSchemaProjection {
        // Gemini supports OpenAPI 3.0 schemas for function parameters
        return ProviderSchemaProjection.Supported(schema)
    }

    /**
     * Formats tools into Gemini `tools` array wire format:
     * [{"functionDeclarations": [{"name": "...", "description": "...", "parameters": {...}}]}]
     */
    fun formatToolsPayload(tools: List<ToolDefinition>): JsonArray {
        val declarations = buildJsonArray {
            tools.filter { it.enabled }.forEach { tool ->
                when (val projection = project(tool.inputSchema)) {
                    is ProviderSchemaProjection.Supported -> {
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", projection.projectedSchema)
                            }
                        )
                    }
                    is ProviderSchemaProjection.LosslesslyProjected -> {
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", "${tool.description} (${projection.description})")
                                put("parameters", projection.projectedSchema)
                            }
                        )
                    }
                    is ProviderSchemaProjection.Unsupported -> {
                        // Omitted safely
                    }
                }
            }
        }

        if (declarations.isEmpty()) return buildJsonArray {}

        return buildJsonArray {
            add(
                buildJsonObject {
                    put("functionDeclarations", declarations)
                }
            )
        }
    }

    /**
     * Parses functionCall parts from Gemini response candidates:
     * candidates[0].content.parts: [{"functionCall": {"name": "...", "args": {...}, "id": "..."}}]
     */
    fun parseToolCalls(candidates: JsonArray): List<ToolCall> {
        val firstCandidate = candidates.firstOrNull()?.jsonObject ?: return emptyList()
        val content = firstCandidate["content"]?.jsonObject ?: return emptyList()
        val parts = content["parts"]?.jsonArray ?: return emptyList()

        return parts.mapNotNull { partElement ->
            runCatching {
                val part = partElement.jsonObject
                val functionCall = part["functionCall"]?.jsonObject ?: return@mapNotNull null
                val name = functionCall["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val args = functionCall["args"]?.jsonObject ?: buildJsonObject {}
                val callId = functionCall["id"]?.jsonPrimitive?.content ?: "gemini_call_${name}"

                ToolCall(
                    callId = callId,
                    toolName = name,
                    arguments = args,
                    providerCorrelation = callId,
                )
            }.getOrNull()
        }
    }

    /**
     * Formats intermediate model round steps into Gemini wire contents:
     * 1. Assistant model content with role="model", parts=[{"functionCall": {...}}]
     * 2. User feedback content with role="user", parts=[{"functionResponse": {"name": ..., "response": {"output": ...}, "id": ...}}]
     *
     * In Gemini 2.0 / generateContent function calling, functionResponse is submitted under role="user".
     */
    fun formatStepContents(steps: List<ModelRoundStep>): List<JsonObject> {
        val contents = mutableListOf<JsonObject>()
        var currentResponses = mutableListOf<JsonObject>()

        steps.forEach { step ->
            when (step) {
                is ModelRoundStep.AssistantToolCalls -> {
                    if (currentResponses.isNotEmpty()) {
                        contents.add(
                            buildJsonObject {
                                put("role", "user")
                                put("parts", JsonArray(currentResponses))
                            }
                        )
                        currentResponses = mutableListOf()
                    }

                    contents.add(
                        buildJsonObject {
                            put("role", "model")
                            put(
                                "parts",
                                buildJsonArray {
                                    step.calls.forEach { call ->
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "functionCall",
                                                    buildJsonObject {
                                                        put("name", call.toolName)
                                                        put("args", call.arguments)
                                                        if (!call.callId.startsWith("gemini_call_")) {
                                                            put("id", call.callId)
                                                        }
                                                    }
                                                )
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                }

                is ModelRoundStep.ToolExecutionFeedback -> {
                    currentResponses.add(
                        buildJsonObject {
                            put(
                                "functionResponse",
                                buildJsonObject {
                                    put("name", step.toolName)
                                    put(
                                        "response",
                                        buildJsonObject {
                                            put("output", step.result)
                                        }
                                    )
                                    if (!step.callId.startsWith("gemini_call_")) {
                                        put("id", step.callId)
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }

        if (currentResponses.isNotEmpty()) {
            contents.add(
                buildJsonObject {
                    put("role", "user")
                    put("parts", JsonArray(currentResponses))
                }
            )
        }

        return contents
    }
}
