package com.dustincorder.rai.domain.tools

import kotlinx.serialization.json.Json
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

        // Must be parseable valid JSON
        val rawJsonString = projected.toString()
        val reparsed = Json.parseToJsonElement(rawJsonString).jsonObject
        assertNotNull(reparsed)

        // Truncated flag should be present
        assertEquals(true, reparsed["truncated"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue("Projected size should be within bounds or reasonably small", rawJsonString.length <= 1000)
    }
}
