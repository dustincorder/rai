package com.dustincorder.rai.data.tools

import com.dustincorder.rai.domain.tools.JsonSchemaValidator
import com.dustincorder.rai.domain.tools.SchemaValidationError
import com.dustincorder.rai.domain.tools.SchemaValidationResult
import dev.harrel.jsonschema.ValidatorFactory
import dev.harrel.jsonschema.providers.KotlinxJsonNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Concrete implementation of JsonSchemaValidator using dev.harrel:json-schema (Draft 2020-12 compliant).
 *
 * Uses KotlinxJsonNode.Factory() to operate natively on kotlinx.serialization.json.JsonElement
 * without any external Jackson, Gson, or reflection overhead.
 */
class HarrelJsonSchemaValidator : JsonSchemaValidator {
    private val validatorFactory = ValidatorFactory()
        .withJsonNodeFactory(KotlinxJsonNode.Factory())

    override fun validate(schema: JsonObject, instance: JsonElement): SchemaValidationResult {
        return try {
            val result = validatorFactory.validate(schema, instance)
            if (result.isValid) {
                SchemaValidationResult.Valid
            } else {
                SchemaValidationResult.Invalid(
                    result.errors.map { error ->
                        SchemaValidationError(
                            path = error.instanceLocation ?: "",
                            error = error.error ?: "Validation failed",
                            keyword = error.keyword,
                        )
                    }
                )
            }
        } catch (failure: Throwable) {
            SchemaValidationResult.Invalid(
                listOf(
                    SchemaValidationError(
                        path = "",
                        error = failure.message ?: "Schema evaluation failed",
                    )
                )
            )
        }
    }
}
