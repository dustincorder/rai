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
}