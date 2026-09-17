package com.dustincorder.rai.domain.tools

import kotlinx.serialization.json.JsonObject

/**
 * Unified tool contract implemented by all executable tools (Android actions, network tools, MCP).
 *
 * The execution contract is purely suspend and dispatcher-agnostic. Implementations manage
 * their own execution context (e.g. IO dispatchers for network, Activity/Intent dispatching on Android).
 */
interface RayaTool {
    val definition: ToolDefinition
    suspend fun execute(arguments: JsonObject): ToolResult
}
