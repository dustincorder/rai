package com.dustincorder.rai.data.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class RayaSystemPromptTest {
    @Test
    fun `built in system prompt demands explicit female grammatical identity`() {
        val prompt = rayaSystemPrompt()

        assertTrue("prompt must name the assistant", prompt.contains("Райя"))
        assertTrue("prompt must require female grammatical forms", prompt.contains("женском роде"))
        assertTrue("prompt must carry a concrete Russian female example", prompt.contains("готова"))
        assertTrue("prompt must carry a past-tense female example", prompt.contains("сделала"))
    }

    @Test
    fun `built in system prompt does not overreach device actions`() {
        assertTrue(rayaSystemPrompt().contains("Не утверждай"))
    }

    @Test
    fun `built in system prompt demands structured json output`() {
        val prompt = rayaSystemPrompt()

        assertTrue("prompt must ask for a json object", prompt.contains("\"text\""))
        assertTrue("prompt must list the emotion field", prompt.contains("\"emotion\""))
        assertTrue("prompt must list the language field", prompt.contains("\"language\""))
        assertTrue("prompt must enumerate the supported emotion values", prompt.contains("calm"))
        assertTrue("prompt must enumerate the supported emotion values", prompt.contains("surprised"))
    }

    @Test
    fun `core prompt mentions the real face capability`() {
        val prompt = rayaSystemPrompt()

        assertTrue("prompt must say the app has a visual face", prompt.contains("визуальное лицо"))
        assertTrue("prompt must say the face supports emotions", prompt.contains("эмоци"))
    }

    @Test
    fun `core prompt forbids claiming unimplemented capabilities`() {
        val prompt = rayaSystemPrompt()

        assertTrue("web search is not implemented and must be called out", prompt.contains("поиск в интернете"))
        assertTrue("weather is not implemented and must be called out", prompt.contains("погод"))
        assertTrue("reminders are not implemented and must be called out", prompt.contains("напоминани"))
        assertTrue("device actions are not implemented and must be called out", prompt.contains("управление приложениями"))
        assertTrue("prompt must state unimplemented capabilities explicitly", prompt.contains("НЕ реализованы"))
        assertTrue("future-only claims allowed explicitly", prompt.contains("будущих"))
    }

    @Test
    fun `core prompt must not tell the user the face cannot change`() {
        val prompt = rayaSystemPrompt()

        assertTrue(
            "prompt must forbid the helpless-digital-assistant wording about the face",
            prompt.contains("не можешь менять лицо"),
        )
    }
}