package com.dustincorder.rai.domain.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets

/**
 * Projects a ToolResult into a model-visible JSON payload adhering strictly to
 * a UTF-8 byte budget constraint.
 *
 * Slicing raw JSON text at arbitrary byte limits is strictly prohibited, as it breaks
 * syntax. Instead, oversized results are compacted structurally into an explicit
 * model projection envelope while preserving valid parseable JSON.
 */
interface ToolResultProjector {
    fun project(result: ToolResult, maxBytes: Int): JsonObject
}

class DefaultToolResultProjector : ToolResultProjector {
    override fun project(result: ToolResult, maxBytes: Int): JsonObject {
        if (maxBytes <= 0) return buildJsonObject {}
        if (maxBytes < 18) return buildJsonObject {}

        return when (result) {
            is ToolResult.Error -> projectError(result, maxBytes)
            is ToolResult.Success -> projectSuccess(result, maxBytes)
        }
    }

    private fun projectError(result: ToolResult.Error, maxBytes: Int): JsonObject {
        val errorKind = result.kind.name
        val baseError = buildJsonObject {
            put("status", "error")
            put("error_kind", errorKind)
            put("message", "")
        }
        if (utf8ByteCount(baseError.toString()) > maxBytes) {
            return minimalTruncatedFallback(maxBytes)
        }

        var message = result.technicalMessage
        var candidate = buildJsonObject {
            put("status", "error")
            put("error_kind", errorKind)
            put("message", message)
        }

        while (utf8ByteCount(candidate.toString()) > maxBytes && message.isNotEmpty()) {
            val overage = utf8ByteCount(candidate.toString()) - maxBytes
            val dropChars = (overage / 2).coerceAtLeast(1)
            val base = message.removeSuffix("…")
            message = if (base.length > dropChars) {
                base.substring(0, base.length - dropChars).trimEnd() + "…"
            } else {
                ""
            }
            candidate = buildJsonObject {
                put("status", "error")
                put("error_kind", errorKind)
                put("message", message)
            }
        }

        if (utf8ByteCount(candidate.toString()) > maxBytes) {
            return minimalTruncatedFallback(maxBytes)
        }
        return candidate
    }

    private fun projectSuccess(result: ToolResult.Success, maxBytes: Int): JsonObject {
        val raw = result.data
        if (utf8ByteCount(raw.toString()) <= maxBytes) {
            return raw
        }

        // Project into an explicit compaction envelope
        val envelopeBaseBytes = utf8ByteCount(
            buildJsonObject {
                put("status", "truncated")
                put("data", buildJsonObject {})
            }.toString()
        )
        if (envelopeBaseBytes > maxBytes) {
            return minimalTruncatedFallback(maxBytes)
        }

        val availableForData = (maxBytes - envelopeBaseBytes).coerceAtLeast(0)
        var compactedData = compactElement(raw, availableForData, depth = 0)

        var candidate = buildJsonObject {
            put("status", "truncated")
            put("data", compactedData)
        }

        if (utf8ByteCount(candidate.toString()) > maxBytes && compactedData is JsonObject) {
            var currentObj: JsonObject = compactedData
            val userKeys = currentObj.keys.filterNot { it.startsWith("_omitted") }.toList()
            for (dropIndex in (userKeys.size - 1) downTo 0) {
                if (utf8ByteCount(candidate.toString()) <= maxBytes) {
                    break
                }
                val keepKeys = userKeys.take(dropIndex).toSet()
                val nextObj = buildJsonObject {
                    currentObj.entries.filter { it.key in keepKeys }.forEach { (k, v) -> put(k, v) }
                    put("_omitted_entries", userKeys.size - keepKeys.size)
                }
                currentObj = nextObj
                candidate = buildJsonObject {
                    put("status", "truncated")
                    put("data", currentObj)
                }
            }
        }

        if (utf8ByteCount(candidate.toString()) > maxBytes) {
            return minimalTruncatedFallback(maxBytes)
        }

        return candidate
    }

    private fun compactElement(element: JsonElement, budgetBytes: Int, depth: Int = 0): JsonElement {
        if (depth >= 3) {
            return JsonPrimitive("[...]")
        }
        return when (element) {
            is JsonArray -> {
                if (element.isEmpty()) {
                    element
                } else {
                    val count = element.size.coerceAtMost(3)
                    val perItemBudget = (budgetBytes / (count + 1)).coerceAtLeast(8)
                    val items = element.take(count).map { compactElement(it, perItemBudget, depth + 1) }
                    buildJsonArray {
                        items.forEach { add(it) }
                        if (element.size > count) {
                            add(JsonPrimitive("[... ${element.size - count} items omitted ...]"))
                        }
                    }
                }
            }

            is JsonObject -> {
                if (element.isEmpty()) {
                    element
                } else {
                    val count = element.size.coerceAtMost(4)
                    val perFieldBudget = (budgetBytes / (count + 1)).coerceAtLeast(8)
                    buildJsonObject {
                        element.entries.take(count).forEach { (key, value) ->
                            put(key, compactElement(value, perFieldBudget, depth + 1))
                        }
                        if (element.size > count) {
                            put("_omitted_fields", element.size - count)
                        }
                    }
                }
            }

            is JsonPrimitive -> {
                if (element.isString) {
                    val str = element.content
                    if (utf8ByteCount(str) > budgetBytes) {
                        JsonPrimitive(truncateToUtf8Bytes(str, budgetBytes))
                    } else {
                        element
                    }
                } else {
                    element
                }
            }
        }
    }

    private fun minimalTruncatedFallback(maxBytes: Int): JsonObject {
        val fallbackMsg = buildJsonObject {
            put("status", "truncated")
            put("summary", "[Data omitted: exceeds budget]")
        }
        if (utf8ByteCount(fallbackMsg.toString()) <= maxBytes) {
            return fallbackMsg
        }
        val minimal = buildJsonObject { put("truncated", true) }
        if (utf8ByteCount(minimal.toString()) <= maxBytes) {
            return minimal
        }
        return buildJsonObject {}
    }

    private fun truncateToUtf8Bytes(str: String, maxBytes: Int): String {
        val suffix = "…[truncated]"
        val suffixBytes = utf8ByteCount(suffix)
        if (maxBytes <= suffixBytes) {
            return if (maxBytes > 0) "…" else ""
        }
        val target = maxBytes - suffixBytes
        val sb = StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < str.length) {
            val cp = str.codePointAt(i)
            val charCount = Character.charCount(cp)
            val charStr = str.substring(i, i + charCount)
            val charBytes = utf8ByteCount(charStr)
            if (currentBytes + charBytes > target) break
            sb.append(charStr)
            currentBytes += charBytes
            i += charCount
        }
        return sb.toString() + suffix
    }

    private fun utf8ByteCount(str: String): Int = str.toByteArray(StandardCharsets.UTF_8).size
}
