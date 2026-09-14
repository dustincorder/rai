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

    @Test fun `ellipsis after name is stripped`() = assertParsed("Рая…", true, "")
    @Test fun `ellipsis between names leaves empty query`() = assertParsed("Рая… Райя", true, "")
    @Test fun `guillemets around name are stripped`() = assertParsed("«Рая»", true, "")
    @Test fun `quotes around name are stripped`() = assertParsed("\"Рая\"", true, "")
    @Test fun `dash before name is stripped`() = assertParsed("-Рая", true, "")
    @Test fun `question mark after name leaves empty query`() = assertParsed("Рая?", true, "")
    @Test fun `exclamation mark after name leaves empty query`() = assertParsed("Рая!", true, "")
    @Test fun `narrow no-break space is stripped`() = assertParsed("Рая\u202Fрасскажи", true, "расскажи")
    @Test fun `no-break space around name is tolerated`() = assertParsed("\u00A0Рая, привет", true, "привет")
    @Test fun `zero width space at boundary is tolerated`() = assertParsed("Рая\u200B", true, "")
    @Test fun `zero width joiner split keeps the question`() = assertParsed("Рая\u200Dрасскажи", true, "расскажи")

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
    fun `rupture a in adjective is not addressing`() {
        val result = RayaAddressingParser.parse("райянский")
        assertFalse(result.addressed)
        assertEquals("райянский", result.query)
    }

    @Test
    fun `english name as part of longer word is not addressing`() {
        val result = RayaAddressingParser.parse("rayabanana")
        assertFalse(result.addressed)
        assertEquals("rayabanana", result.query)
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
