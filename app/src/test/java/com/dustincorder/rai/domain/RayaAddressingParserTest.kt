package com.dustincorder.rai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RayaAddressingParserTest {
    @Test fun `only Russian name is addressed with empty query`() = assertParsed("Райя", true, "")
    @Test fun `Russian punctuation is stripped`() = assertParsed("Райя, привет", true, "привет")
    @Test fun `lowercase Russian name is stripped`() = assertParsed("райя расскажи про космос", true, "расскажи про космос")
    @Test fun `English name is stripped`() = assertParsed("Raya, hello", true, "hello")
    @Test fun `dash after name is accepted`() = assertParsed("Райя—привет", true, "привет")

    @Test
    fun `name inside another word is not addressing`() {
        val result = RayaAddressingParser.parse("крайяновский текст")
        assertFalse(result.addressed)
        assertEquals("крайяновский текст", result.query)
    }

    private fun assertParsed(input: String, addressed: Boolean, query: String) {
        val result = RayaAddressingParser.parse(input)
        assertEquals(addressed, result.addressed)
        assertEquals(query, result.query)
    }
}
