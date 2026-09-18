package com.dustincorder.rai.domain.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Normalized result produced by tool execution.
 *
 * Tools return structured machine data and typed status/errors. Tools do NOT own
 * localized user-facing summaries or UI strings; presentation mapping and final model
 * synthesis are responsible for localized text.
 */
@Serializable
sealed interface ToolResult {
    @Serializable
    data class Success(val data: JsonObject) : ToolResult

    @Serializable
    data class Error(
        val kind: ToolErrorKind,
        val technicalMessage: String,
    ) : ToolResult
}

@Serializable
enum class ToolErrorKind {
    ValidationFailed,
    PolicyDenied,
    PermissionMissing,
    ExecutionFailed,
    NetworkError,
    Timeout,
}
