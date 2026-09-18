package com.dustincorder.rai.domain.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Result of projecting a canonical JSON Schema against a provider's capabilities.
 *
 * Schemas must never be silently weakened. If a provider API cannot represent
 * a constraint, the projection explicitly flags it as Unsupported so the tool
 * is disabled or rejected for that provider turn.
 */
sealed interface ProviderSchemaProjection {
    /** Target provider natively supports this schema without modification. */
    data class Supported(val projectedSchema: JsonObject) : ProviderSchemaProjection

    /** Target provider supports an equivalent lossless representation (e.g. inlined $defs). */
    data class LosslesslyProjected(val projectedSchema: JsonObject, val description: String) : ProviderSchemaProjection

    /** Target provider API cannot represent this schema safely; tool must be rejected for this turn. */
    data class Unsupported(val reason: String) : ProviderSchemaProjection
}

interface ProviderSchemaProjector {
    fun project(schema: JsonObject): ProviderSchemaProjection
}

fun JsonElement.findForbiddenKeyword(forbidden: Set<String>): String? {
    return when (this) {
        is JsonObject -> {
            for (key in keys) {
                if (key in forbidden) return key
                val found = this[key]?.findForbiddenKeyword(forbidden)
                if (found != null) return found
            }
            null
        }
        is JsonArray -> {
            for (item in this) {
                val found = item.findForbiddenKeyword(forbidden)
                if (found != null) return found
            }
            null
        }
        else -> null
    }
}

fun JsonElement.findAllRefValues(): Set<String> {
    val result = mutableSetOf<String>()
    fun traverse(el: JsonElement) {
        when (el) {
            is JsonObject -> {
                el["\$ref"]?.let { ref ->
                    runCatching { ref.jsonPrimitive.content }.getOrNull()?.let { result.add(it) }
                }
                el.values.forEach { traverse(it) }
            }
            is JsonArray -> el.forEach { traverse(it) }
            else -> {}
        }
    }
    traverse(this)
    return result
}
