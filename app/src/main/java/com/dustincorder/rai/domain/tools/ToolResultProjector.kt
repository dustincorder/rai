package com.dustincorder.rai.domain.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Projects a ToolResult into a model-visible JSON payload adhering to budget constraints.
 *
 * Slicing raw JSON text at arbitrary byte limits is strictly prohibited, as it breaks
 * syntax. Instead, oversized results are compacted structurally while preserving valid JSON.
 */
interface ToolResultProjector {
    fun project(result: ToolResult, maxBytes: Int): JsonObject
}

class DefaultToolResultProjector : ToolResultProjector {
    override fun project(result: ToolResult, maxBytes: Int): JsonObject = when (result) {
        is ToolResult.Error -> buildJsonObject {
            put("status", "error")
            put("error_kind", result.kind.name)
            put("message", result.technicalMessage.take(maxBytes.coerceAtLeast(256)))
        }

        is ToolResult.Success -> {
            val raw = result.data
            val rawString = raw.toString()
            if (rawString.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                raw
            } else {
                compactStructuredJson(raw, maxBytes)
            }
        }
    }

    private fun compactStructuredJson(obj: JsonObject, maxBytes: Int): JsonObject {
        val estimatedPerFieldBudget = maxBytes / (obj.size.coerceAtLeast(1) + 1)
        return buildJsonObject {
            put("truncated", true)
            var currentBytes = 20
            obj.entries.forEach { (key, value) ->
                if (currentBytes < maxBytes) {
                    val compactedValue = compactElement(value, estimatedPerFieldBudget)
                    put(key, compactedValue)
                    currentBytes += key.length + compactedValue.toString().length + 4
                }
            }
        }
    }

    private fun compactElement(element: JsonElement, budgetBytes: Int): JsonElement = when (element) {
        is JsonArray -> {
            if (element.size <= 2) element
            else {
                val kept = element.take(2).map { compactElement(it, budgetBytes / 2) }
                JsonArray(kept + listOf(JsonPrimitive("[... ${element.size - 2} items omitted ...]")))
            }
        }
        is JsonObject -> {
            val rawStr = element.toString()
            if (rawStr.toByteArray(Charsets.UTF_8).size <= budgetBytes) element
            else buildJsonObject {
                put("truncated", true)
                element.entries.take(3).forEach { (k, v) ->
                    put(k, compactElement(v, budgetBytes / 3))
                }
            }
        }
        is JsonPrimitive -> {
            if (element.isString && element.content.length > budgetBytes) {
                JsonPrimitive(element.content.take(budgetBytes) + "… (truncated)")
            } else {
                element
            }
        }
    }
}
