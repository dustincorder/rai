package com.dustincorder.rai.data.llm

import com.dustincorder.rai.domain.RayaEmotion
import com.dustincorder.rai.domain.RayaResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RayaResponseParserTest {
    @Test
    fun `each supported emotion parses`() {
        RayaEmotion.entries.forEachIndexed { index, emotion ->
            val raw = """{"text":"t","emotion":"${emotion.name.lowercase()}","language":"ru-RU"}"""
            assertEquals(
                "emotion ${emotion.name} lowercased must parse",
                RayaResponse("t", emotion, "ru-RU"),
                parseRayaResponse(raw),
            )
        }
    }

    @Test
    fun `mixed case emotion still parses`() {
        assertEquals(
            RayaResponse("t", RayaEmotion.Happy, null),
            parseRayaResponse("""{"text":"t","emotion":"HAPPY"}"""),
        )
    }

    @Test
    fun `unknown emotion falls back to calm`() {
        assertEquals(
            RayaResponse("t", RayaEmotion.Calm, null),
            parseRayaResponse("""{"text":"t","emotion":"ecstatic"}"""),
        )
        assertEquals(
            RayaResponse("t", RayaEmotion.Calm, null),
            parseRayaResponse("""{"text":"t","emotion":""}"""),
        )
    }

    @Test
    fun `valid language tag is preserved`() {
        assertEquals(
            RayaResponse("t", RayaEmotion.Calm, "en-US"),
            parseRayaResponse("""{"text":"t","language":"en-US"}"""),
        )
    }

    @Test
    fun `invalid language tag becomes null`() {
        assertEquals(
            RayaResponse("t", RayaEmotion.Calm, null),
            parseRayaResponse("""{"text":"t","language":"12345"}"""),
        )
    }

    @Test
    fun `plain text response falls back to calm without json`() {
        assertEquals(
            RayaResponse("Просто ответ", RayaEmotion.Calm, null),
            parseRayaResponse("Просто ответ"),
        )
        assertEquals(
            RayaResponse("Ответ\nна двух строках", RayaEmotion.Calm, null),
            parseRayaResponse("Ответ\nна двух строках"),
        )
    }

    @Test
    fun `malformed json object is a provider error`() {
        assertThrows(LlmSafeException::class.java) {
            parseRayaResponse("""{"text": "не закрыт""")
        }
        assertThrows(LlmSafeException::class.java) {
            parseRayaResponse("""{"emotion":"happy",""")
        }
    }

    @Test
    fun `malformed fenced json object is a provider error`() {
        val raw = """
            ```json
            {"text":"сломано"
            ```
        """.trimIndent()

        assertThrows(LlmSafeException::class.java) {
            parseRayaResponse(raw)
        }
    }

    @Test
    fun `markdown fenced json is parsed`() {
        val raw = """
                ```json
                {"text":"Ого, это сюрприз!","emotion":"surprised","language":"ru-RU"}
                ```
            """.trimIndent()
        assertEquals(
            RayaResponse("Ого, это сюрприз!", RayaEmotion.Surprised, "ru-RU"),
            parseRayaResponse(raw),
        )
    }

    @Test
    fun `json without text is a provider error not a normal response`() {
        assertThrows(LlmSafeException::class.java) {
            parseRayaResponse("""{"emotion":"happy"}""")
        }
        assertThrows(LlmSafeException::class.java) {
            parseRayaResponse("""{"text":"   ","emotion":"happy"}""")
        }
    }

    @Test
    fun `valid json with text trims surrounding whitespace`() {
        assertEquals(
            RayaResponse("Привет", RayaEmotion.Calm, null),
            parseRayaResponse("""  {"text":"Привет"}  """),
        )
    }
}
