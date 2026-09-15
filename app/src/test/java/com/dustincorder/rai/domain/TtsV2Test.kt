package com.dustincorder.rai.domain

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsV2Test {
    @Test
    fun `visible text is unchanged while russian tts normalizes time and currency`() {
        val visible = "Встреча в 12:30 стоит 100₽."
        assertEquals("Встреча в 12:30 стоит 100₽.", visible)
        assertEquals("Встреча в 12 часов 30 минут стоит 100 рублей.", normalizeForTts(visible, Locale("ru", "RU")))
    }

    @Test
    fun `english and ukrainian currency normalization is conservative`() {
        assertEquals("It costs 5 dollars", normalizeForTts("It costs 5$", Locale.US))
        assertEquals("Це коштує 5 гривень", normalizeForTts("Це коштує 5₴", Locale("uk", "UA")))
    }

    @Test
    fun `urls become safe spoken placeholder`() {
        assertEquals("Ось ссылка", normalizeForTts("Ось https://example.com", Locale("ru", "RU")))
    }
}
