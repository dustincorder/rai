package com.dustincorder.rai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RayaAddressingParserTest {
    @Test fun `only Russian name is addressed with empty query`() = assertParsed("Райя", true, "")
    @Test fun `real stt spelling ray a is addressed`() = assertParsed("Рая", true, "")
    @Test fun `lowercase real stt spelling is addressed`() = assertParsed("рая", true, "")
    @Test fun `russian punctuation is stripped`() = assertParsed("Райя, привет", true, "привет")
    @Test fun `lowercase Russian name is stripped`() = assertParsed("райя расскажи про космос", true, "расскажи про космос")
    @Test fun `rupture a without punctuation is stripped`() = assertParsed("рая привет", true, "привет")
    @Test fun `rupture a with comma is stripped`() = assertParsed("Рая, привет", true, "привет")
    @Test fun `english name is stripped`() = assertParsed("Raya, hello", true, "hello")
    @Test fun `dash after name is accepted`() = assertParsed("Райя—привет", true, "привет")

    @Test fun `repeated real stt names leave empty query`() = assertParsed("Рая, рая", true, "")
    @Test fun `repeated mixed names with exclamation leave empty query`() = assertParsed("Райя, Рая!", true, "")
    @Test fun `repeated english names leave empty query`() = assertParsed("Raya, Raya", true, "")
    @Test fun `repeated names before request keep the question`() = assertParsed("Рая, Райя, расскажи", true, "расскажи")
    @Test fun `repeated names without punctuation keep the question`() = assertParsed("рая рая расскажи про Марс", true, "расскажи про Марс")

    @Test
    fun `name inside another word is not addressing`() {
        val result = RayaAddressingParser.parse("крайяновский текст")
        assertFalse(result.addressed)
        assertEquals("крайяновский текст", result.query)
    }

    @Test
    fun `rupture a as part of longer russian word is not addressing`() {
        val result = RayaAddressingParser.parse("раяльность")
        assertFalse(result.addressed)
        assertEquals("раяльность", result.query)
    }

    @Test
    fun `name as substring not at start is not addressing`() {
        val result = RayaAddressingParser.parse("прорая")
        assertFalse(result.addressed)
        assertEquals("прорая", result.query)
    }

    private fun assertParsed(input: String, addressed: Boolean, query: String) {
        val result = RayaAddressingParser.parse(input)
        assertEquals(addressed, result.addressed)
        assertEquals(query, result.query)
    }
}
