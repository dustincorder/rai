package com.dustincorder.rai.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun `groq whisper models are stt only and excluded from chat selector`() {
        val models = listOf(
            DiscoveredModel("llama-3.3-70b-versatile", capabilities = classifyOpenAiStyleModel("llama-3.3-70b-versatile")),
            DiscoveredModel("whisper-large-v3-turbo", capabilities = classifyOpenAiStyleModel("whisper-large-v3-turbo")),
            DiscoveredModel("whisper-large-v3", capabilities = classifyOpenAiStyleModel("whisper-large-v3")),
        )

        val chat = models.groqChatModels()
        assertEquals(listOf("llama-3.3-70b-versatile"), chat.map { it.id })

        val stt = models.sttModels()
        assertEquals(listOf("whisper-large-v3-turbo", "whisper-large-v3"), stt.map { it.id })
    }

    @Test
    fun `openai style classifier separates tts embedding and other`() {
        assertEquals(setOf(LlmModelCapability.TtsAudio), classifyOpenAiStyleModel("tts-1"))
        assertEquals(setOf(LlmModelCapability.TtsAudio), classifyOpenAiStyleModel("gpt-4o-mini-tts"))
        assertEquals(setOf(LlmModelCapability.Embedding), classifyOpenAiStyleModel("text-embedding-3-small"))
        assertEquals(setOf(LlmModelCapability.Other), classifyOpenAiStyleModel("dall-e-3"))
        assertEquals(setOf(LlmModelCapability.Chat), classifyOpenAiStyleModel("gpt-4o-mini"))
        assertEquals(setOf(LlmModelCapability.Chat), classifyOpenAiStyleModel("openai/gpt-oss-20b"))
    }

    @Test
    fun `gemini classifier uses supported methods and hides embedding models`() {
        val chat = classifyGeminiModel("gemini-2.0-flash", listOf("generateContent", "countTokens"))
        assertEquals(setOf(LlmModelCapability.Chat), chat)

        val embedding = classifyGeminiModel("text-embedding-004", listOf("embedContent"))
        assertEquals(setOf(LlmModelCapability.Embedding), embedding)

        val unknown = classifyGeminiModel("models/some-future-thing", emptyList())
        assertEquals(setOf(LlmModelCapability.Other), unknown)
    }

    @Test
    fun `gemini embedding named models never enter chat selector`() {
        val models = listOf(
            DiscoveredModel("gemini-2.0-flash", capabilities = setOf(LlmModelCapability.Chat)),
            DiscoveredModel("text-embedding-004", capabilities = setOf(LlmModelCapability.Embedding)),
        )
        assertEquals(listOf("gemini-2.0-flash"), models.chatModels().map { it.id })
    }

    @Test
    fun `missing selection is appended as saved marker`() {
        val models = listOf(DiscoveredModel("a"), DiscoveredModel("b"))
        val merged = models.withSelectedPresent("gone-model")

        assertEquals(3, merged.size)
        assertEquals("gone-model", merged.last().id)
        assertTrue(merged.last().supports(LlmModelCapability.Chat))
    }

    @Test
    fun `present selection leaves list untouched`() {
        val models = listOf(DiscoveredModel("a"), DiscoveredModel("b"))
        assertTrue(models.withSelectedPresent("a") === models)
        assertTrue(models.withSelectedPresent("") === models)
    }

    @Test
    fun `display name falls back to id`() {
        assertEquals("x", DiscoveredModel("x").displayName)
        assertEquals("Nice", DiscoveredModel("x", "Nice").displayName)
        assertFalse(DiscoveredModel("x").supports(LlmModelCapability.Stt))
    }
}
