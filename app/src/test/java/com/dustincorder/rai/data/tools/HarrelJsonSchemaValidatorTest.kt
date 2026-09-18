package com.dustincorder.rai.data.tools

import com.dustincorder.rai.domain.tools.SchemaValidationResult
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarrelJsonSchemaValidatorTest {
    private val validator = HarrelJsonSchemaValidator()

    @Test
    fun `valid object passes schema validation`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject { put("type", "string") })
                put("age", buildJsonObject { put("type", "integer") })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("name")) })
        }

        val instance = buildJsonObject {
            put("name", "Raya")
            put("age", 25)
        }

        val result = validator.validate(schema, instance)
        assertTrue(result is SchemaValidationResult.Valid)
    }

    @Test
    fun `missing required property fails validation`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("target")) })
        }

        val instance = buildJsonObject {
            put("other", "value")
        }

        val result = validator.validate(schema, instance)
        assertTrue(result is SchemaValidationResult.Invalid)
        val invalid = result as SchemaValidationResult.Invalid
        assertEquals(1, invalid.errors.size)
    }

    @Test
    fun `type mismatch fails validation`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("count", buildJsonObject { put("type", "integer") })
            })
        }

        val instance = buildJsonObject {
            put("count", "not_a_number")
        }

        val result = validator.validate(schema, instance)
        assertTrue(result is SchemaValidationResult.Invalid)
    }

    @Test
    fun `enum constraint validation`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("start"))
                        add(kotlinx.serialization.json.JsonPrimitive("stop"))
                    })
                })
            })
        }

        val validInstance = buildJsonObject { put("action", "start") }
        assertTrue(validator.validate(schema, validInstance) is SchemaValidationResult.Valid)

        val invalidInstance = buildJsonObject { put("action", "pause") }
        assertTrue(validator.validate(schema, invalidInstance) is SchemaValidationResult.Invalid)
    }
}
