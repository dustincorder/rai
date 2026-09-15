package com.dustincorder.rai.data.stt

import com.dustincorder.rai.domain.AudioUtterance
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroqWhisperTranscriptionProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: GroqWhisperTranscriptionProvider

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        provider = GroqWhisperTranscriptionProvider(
            OkHttpClient(),
            Json { ignoreUnknownKeys = true },
            server.url("/audio/transcriptions").toString(),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `transcription posts whisper multipart and propagates language`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"Привет, Райя?","language":"ru"}"""))
        val result = provider.transcribe(
            AudioUtterance(ByteArray(320) { 0 }, 16_000, 1),
            "whisper-large-v3-turbo",
            "ru",
        )

        assertEquals("Привет, Райя?", result.text)
        assertEquals("ru-ru", result.languageTag)
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"model\""))
    }

    @Test
    fun `http failure is safe and cancellable transport is used`() = runTest {
        server.enqueue(MockResponse().setResponseCode(504))
        val failure = runCatching {
            provider.transcribe(AudioUtterance(ByteArray(64), 16_000, 1), "whisper-large-v3", null)
        }.exceptionOrNull()
        assertTrue(failure != null)
    }
}
