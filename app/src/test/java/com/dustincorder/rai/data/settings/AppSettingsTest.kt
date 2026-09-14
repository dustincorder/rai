package com.dustincorder.rai.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test fun `OpenAI preset maps protocol and URL`() = assertPreset(LlmProviderPreset.OpenAI, LlmProtocol.OpenAiCompatible, "https://api.openai.com/v1")
    @Test fun `Groq preset maps protocol and URL`() = assertPreset(LlmProviderPreset.Groq, LlmProtocol.OpenAiCompatible, "https://api.groq.com/openai/v1")
    @Test fun `Anthropic preset maps protocol and URL`() = assertPreset(LlmProviderPreset.Anthropic, LlmProtocol.AnthropicCompatible, "https://api.anthropic.com/v1")

    @Test
    fun `custom provider uses custom protocol and URL`() {
        val settings = AppSettings(
            provider = LlmProviderPreset.Custom,
            customProtocol = LlmProtocol.AnthropicCompatible,
            customBaseUrl = "https://example.com/v1",
            modelId = "custom-model",
        )
        assertEquals(LlmProtocol.AnthropicCompatible, settings.protocol)
        assertEquals("https://example.com/v1", settings.baseUrl)
    }

    @Test
    fun `URL normalization removes trailing slash and rejects HTTP`() {
        assertEquals("https://example.com/v1", normalizeBaseUrl(" https://example.com/v1/ "))
        assertTrue(runCatching { normalizeBaseUrl("http://example.com/v1") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { normalizeBaseUrl("https://example.com/v1?token=value") }.isFailure)
    }

    private fun assertPreset(preset: LlmProviderPreset, protocol: LlmProtocol, url: String) {
        val settings = AppSettings(provider = preset)
        assertEquals(protocol, settings.protocol)
        assertEquals(url, settings.baseUrl)
    }
}
