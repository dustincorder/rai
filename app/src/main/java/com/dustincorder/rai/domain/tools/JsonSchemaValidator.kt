package com.dustincorder.rai.domain.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Domain validator abstraction for JSON Schema validation.
 */
interface JsonSchemaValidator {
    fun validate(schema: JsonObject, instance: JsonElement): SchemaValidationResult
}

sealed interface SchemaValidationResult {
    data object Valid : SchemaValidationResult
    data class Invalid(val errors: List<SchemaValidationError>) : SchemaValidationResult
}

data class SchemaValidationError(
    val path: String,
    val error: String,
    val keyword: String? = null,
)
