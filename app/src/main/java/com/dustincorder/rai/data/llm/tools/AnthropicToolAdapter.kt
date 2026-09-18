package com.dustincorder.rai.data.llm.tools

import com.dustincorder.rai.domain.tools.ModelRoundStep
import com.dustincorder.rai.domain.tools.ProviderSchemaProjection
import com.dustincorder.rai.domain.tools.ProviderSchemaProjector
import com.dustincorder.rai.domain.tools.ToolCall
import com.dustincorder.rai.domain.tools.ToolDefinition
import com.dustincorder.rai.domain.tools.findAllRefValues
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

class AnthropicToolAdapter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderSchemaProjector {

    override fun project(schema: JsonObject): ProviderSchemaProjection {
        val rootType = schema["type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        if (rootType != "object") {
            return ProviderSchemaProjection.Unsupported("Anthropic tools require root schema type to be 'object'")
        }

        val forbidden = setOf("patternProperties", "unevaluatedProperties", "if", "then", "else")
        val foundForbidden = schema.findForbiddenKeyword(forbidden)
        if (foundForbidden != null) {
            return ProviderSchemaProjection.Unsupported("Anthropic tools do not support '$foundForbidden'")
        }

        val refs = schema.findAllRefValues()
        for (ref in refs) {
            if (ref.startsWith("http://") || ref.startsWith("https://")) {
                return ProviderSchemaProjection.Unsupported("Anthropic tools do not support external \$ref '$ref'")
            }
            if (!ref.startsWith("#/\$defs/") && !ref.startsWith("#/definitions/")) {
                return ProviderSchemaProjection.Unsupported("Anthropic tools do not support unresolvable \$ref '$ref'")
            }
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
        val calls = contentArray.mapNotNull { item ->
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

        val seenIds = mutableSetOf<String>()
        for (call in calls) {
            if (!seenIds.add(call.callId)) {
                throw IllegalArgumentException("Обнаружен дубликат callId '${call.callId}' в ответе Anthropic.")
            }
        }
        return calls
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
