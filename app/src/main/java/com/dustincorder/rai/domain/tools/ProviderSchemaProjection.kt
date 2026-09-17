package com.dustincorder.rai.domain.tools

import kotlinx.serialization.json.JsonObject

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
