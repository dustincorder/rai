package com.dustincorder.rai.domain.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
@JvmInline
value class ToolId(val value: String) {
    override fun toString(): String = value
}

/**
 * Canonical tool definition preserving full JSON Schema input and output definitions.
 *
 * Schemas must not be flattened into primitive parameter lists so that modern MCP
 * and JSON Schema 2020-12 constructs (nested objects, enums, combinators, $defs)
 * remain intact.
 */
@Serializable
data class ToolDefinition(
    val id: ToolId,
    val name: String,
    val description: String,
    val effect: ToolEffect,
    val executionKind: ExecutionKind,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val enabled: Boolean = true,
)
