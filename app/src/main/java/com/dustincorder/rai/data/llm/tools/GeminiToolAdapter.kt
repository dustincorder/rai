package com.dustincorder.rai.data.llm.tools

import com.dustincorder.rai.domain.tools.ModelRoundStep
import com.dustincorder.rai.domain.tools.ProviderSchemaProjection
import com.dustincorder.rai.domain.tools.ProviderSchemaProjector
import com.dustincorder.rai.domain.tools.ToolCall
import com.dustincorder.rai.domain.tools.ToolDefinition
import com.dustincorder.rai.domain.tools.findForbiddenKeyword
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
        val rootType = schema["type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        if (rootType != "object") {
            return ProviderSchemaProjection.Unsupported("Gemini functionDeclarations require root schema type to be 'object'")
        }

        val forbidden = setOf(
            "oneOf",
            "anyOf",
            "allOf",
            "not",
            "\$ref",
            "\$defs",
            "definitions",
            "additionalProperties",
            "patternProperties",
            "if",
            "then",
            "else",
        )
        val foundForbidden = schema.findForbiddenKeyword(forbidden)
        if (foundForbidden != null) {
            return ProviderSchemaProjection.Unsupported("Gemini functionDeclarations do not support: '$foundForbidden'")
        }

        return if (schema.containsKey("\$schema")) {
            val stripped = buildJsonObject {
                schema.entries.filterNot { it.key == "\$schema" }.forEach { (k, v) -> put(k, v) }
            }
            ProviderSchemaProjection.LosslesslyProjected(stripped, "Stripped root \$schema")
        } else {
            ProviderSchemaProjection.Supported(schema)
        }
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

        val calls = mutableListOf<ToolCall>()
        val seenIds = mutableSetOf<String>()

        parts.forEachIndexed { index, partElement ->
            val part = runCatching { partElement.jsonObject }.getOrNull() ?: return@forEachIndexed
            val functionCall = part["functionCall"]?.jsonObject ?: return@forEachIndexed
            val name = functionCall["name"]?.jsonPrimitive?.content ?: return@forEachIndexed
            val args = functionCall["args"]?.jsonObject ?: buildJsonObject {}
            val providerId = functionCall["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val callId = providerId ?: "gemini_call_${name}_$index"

            if (!seenIds.add(callId)) {
                throw IllegalArgumentException("Обнаружен дубликат callId '$callId' в ответе Gemini.")
            }

            calls.add(
                ToolCall(
                    callId = callId,
                    toolName = name,
                    arguments = args,
                    providerCorrelation = providerId,
                )
            )
        }
        return calls
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
                                                        if (!call.callId.matches(SYNTHETIC_CALL_ID_REGEX)) {
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
                                    if (!step.callId.matches(SYNTHETIC_CALL_ID_REGEX)) {
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

    companion object {
        private val SYNTHETIC_CALL_ID_REGEX = Regex("""^gemini_call_.*_\d+$""")
    }
}
