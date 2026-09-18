package com.dustincorder.rai.domain.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultProjectorTest {
    private val projector = DefaultToolResultProjector()

    @Test
    fun `error result is projected with kind and message`() {
        val errorResult = ToolResult.Error(
            kind = ToolErrorKind.ValidationFailed,
            technicalMessage = "Invalid parameters provided",
        )

        val projected = projector.project(errorResult, maxBytes = 1024)

        assertEquals("error", projected["status"]?.jsonPrimitive?.content)
        assertEquals("ValidationFailed", projected["error_kind"]?.jsonPrimitive?.content)
        assertEquals("Invalid parameters provided", projected["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `success result within budget is preserved unchanged`() {
        val data = buildJsonObject {
            put("status", "ok")
            put("result", 42)
        }
        val success = ToolResult.Success(data)

        val projected = projector.project(success, maxBytes = 1024)
        assertEquals(data, projected)
    }

    @Test
    fun `oversized success result is compacted into valid json`() {
        val largeList = buildJsonArray {
            repeat(100) { idx ->
                add(
                    buildJsonObject {
                        put("id", idx)
                        put("description", "A very long detailed string item that consumes lots of space in the output payload #$idx")
                    }
                )
            }
        }
        val largeData = buildJsonObject {
            put("items", largeList)
            put("summary", "Complete items list with hundred entries")
        }
        val success = ToolResult.Success(largeData)

        val maxBytes = 500
        val projected = projector.project(success, maxBytes = maxBytes)

        val rawJsonString = projected.toString()
        val reparsed = Json.parseToJsonElement(rawJsonString).jsonObject
        assertNotNull(reparsed)

        val actualBytes = rawJsonString.toByteArray(Charsets.UTF_8).size
        assertTrue("Projected bytes ($actualBytes) must not exceed maxBytes ($maxBytes)", actualBytes <= maxBytes)
    }

    @Test
    fun `unicode multibyte strings strictly respect UTF-8 byte budget`() {
        // Cyrillic and emoji string where each character takes 2-4 bytes in UTF-8
        val multibyteText = "Привет, мир! 🚀 ".repeat(50)
        val data = buildJsonObject {
            put("content", multibyteText)
        }
        val success = ToolResult.Success(data)

        val budgets = listOf(64, 100, 150, 256)
        for (budget in budgets) {
            val projected = projector.project(success, maxBytes = budget)
            val jsonStr = projected.toString()
            val parsed = Json.parseToJsonElement(jsonStr).jsonObject
            assertNotNull(parsed)
            val utf8Size = jsonStr.toByteArray(Charsets.UTF_8).size
            assertTrue("Expected UTF-8 size $utf8Size <= $budget", utf8Size <= budget)
        }
    }

    @Test
    fun `two-item array containing huge strings is compacted within budget`() {
        val hugeString1 = "A".repeat(5000)
        val hugeString2 = "B".repeat(5000)
        val data = buildJsonObject {
            put("items", buildJsonArray {
                add(JsonPrimitive(hugeString1))
                add(JsonPrimitive(hugeString2))
            })
        }
        val success = ToolResult.Success(data)

        val maxBytes = 256
        val projected = projector.project(success, maxBytes = maxBytes)
        val jsonStr = projected.toString()
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject
        assertNotNull(parsed)
        val utf8Size = jsonStr.toByteArray(Charsets.UTF_8).size
        assertTrue("Expected UTF-8 size $utf8Size <= $maxBytes for 2 huge items array", utf8Size <= maxBytes)
    }

    @Test
    fun `deeply nested data is compacted within budget`() {
        var current: kotlinx.serialization.json.JsonObject = buildJsonObject {
            put("leaf", "deep value ".repeat(50))
        }
        repeat(15) { depth ->
            current = buildJsonObject {
                put("level_$depth", current)
            }
        }
        val success = ToolResult.Success(current)

        val maxBytes = 200
        val projected = projector.project(success, maxBytes = maxBytes)
        val jsonStr = projected.toString()
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject
        assertNotNull(parsed)
        val utf8Size = jsonStr.toByteArray(Charsets.UTF_8).size
        assertTrue("Expected UTF-8 size $utf8Size <= $maxBytes for deeply nested data", utf8Size <= maxBytes)
    }

    @Test
    fun `error message with unicode strictly respects byte budget`() {
        val unicodeError = "Ошибка выполнения: не удалось подключиться к серверу ❌ ".repeat(20)
        val error = ToolResult.Error(ToolErrorKind.ExecutionFailed, unicodeError)

        val maxBytes = 120
        val projected = projector.project(error, maxBytes = maxBytes)
        val jsonStr = projected.toString()
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject
        assertNotNull(parsed)
        val utf8Size = jsonStr.toByteArray(Charsets.UTF_8).size
        assertTrue("Expected UTF-8 size $utf8Size <= $maxBytes for error message", utf8Size <= maxBytes)
    }
}
