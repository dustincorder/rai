package com.dustincorder.rai.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationLanguageTest {
    @Test
    fun `explicit language wins`() {
        assertEquals("uk-UA", ConversationLanguage.Explicit("uk-UA").resolveLanguageTag("en-US", "ru-RU"))
    }

    @Test
    fun `system language uses system tag`() {
        assertEquals("de-DE", ConversationLanguage.System.resolveLanguageTag("en-US", "de-DE"))
    }

    @Test
    fun `auto uses detected language then system fallback`() {
        assertEquals("en-US", ConversationLanguage.Auto.resolveLanguageTag("en-US", "ru-RU"))
        assertEquals("ru-RU", ConversationLanguage.Auto.resolveLanguageTag(null, "ru-RU"))
    }

    @Test
    fun `auto recognition falls back to system when Android detection is unavailable`() {
        val fallback = RecognitionRequest(ConversationLanguage.Auto, "uk-UA").toLanguagePlan(false)
        assertEquals("uk-UA", fallback.languageTag)
        assertEquals(false, fallback.enableDetection)

        val detected = RecognitionRequest(ConversationLanguage.Auto, "uk-UA").toLanguagePlan(true)
        assertEquals(null, detected.languageTag)
        assertEquals(true, detected.enableDetection)
    }
}
