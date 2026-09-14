package com.dustincorder.rai.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test fun `OpenAI preset maps protocol and URL`() = assertPreset(LlmProviderPreset.OpenAI, LlmProtocol.OpenAiCompatible, "https://api.openai.com/v1")
    @Test fun `Groq preset maps protocol and URL`() = assertPreset(LlmProviderPreset.Groq, LlmProtocol.OpenAiCompatible, "https://api.groq.com/openai/v1")
    @Test fun `Anthropic preset maps protocol and URL`() = assertPreset(LlmProviderPreset.Anthropic, LlmProtocol.AnthropicCompatible, "https://api.anthropic.com/v1")

    @Test
    fun `Groq preset default model uses creator slash id`() {
        assertEquals("openai/gpt-oss-20b", LlmProviderPreset.Groq.defaultModel)
    }

    @Test
    fun `Anthropic preset default model is current Sonnet`() {
        assertEquals("claude-sonnet-5", LlmProviderPreset.Anthropic.defaultModel)
    }

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
    fun `URL normalization removes trailing slash and allows http`() {
        assertEquals("https://example.com/v1", normalizeBaseUrl(" https://example.com/v1/ "))
        assertEquals("http://example.com/v1", normalizeBaseUrl("http://example.com/v1"))
        assertTrue(runCatching { normalizeBaseUrl("https://example.com/v1?token=value") }.isFailure)
    }

    @Test
    fun `OpenAI endpoint appends chat completions to base URL`() {
        assertEquals(
            "/v1/chat/completions",
            resolveEndpointUrl("https://example.com/v1", listOf("chat", "completions")).encodedPath,
        )
    }

    @Test
    fun `OpenAI full endpoint URL is used unchanged`() {
        assertEquals(
            "/v1/chat/completions",
            resolveEndpointUrl("https://example.com/v1/chat/completions", listOf("chat", "completions")).encodedPath,
        )
    }

    @Test
    fun `Anthropic endpoint appends messages to base URL`() {
        assertEquals(
            "/v1/messages",
            resolveEndpointUrl("https://example.com/v1", listOf("messages")).encodedPath,
        )
    }

    @Test
    fun `Anthropic full endpoint URL is used unchanged`() {
        assertEquals(
            "/v1/messages",
            resolveEndpointUrl("https://example.com/v1/messages", listOf("messages")).encodedPath,
        )
    }

    @Test
    fun `endpoint URL normalizes trailing slash without duplication`() {
        assertEquals(
            "/v1/chat/completions",
            resolveEndpointUrl("https://example.com/v1/chat/completions/", listOf("chat", "completions")).encodedPath,
        )
        assertEquals(
            "/v1/chat/completions",
            resolveEndpointUrl("https://example.com/v1/", listOf("chat", "completions")).encodedPath,
        )
    }

    @Test
    fun `endpoint preserves custom prefix without forcing v1`() {
        assertEquals(
            "/api/openai/chat/completions",
            resolveEndpointUrl("https://host/api/openai", listOf("chat", "completions")).encodedPath,
        )
    }

    private fun assertPreset(preset: LlmProviderPreset, protocol: LlmProtocol, url: String) {
        val settings = AppSettings(provider = preset)
        assertEquals(protocol, settings.protocol)
        assertEquals(url, settings.baseUrl)
    }
}
