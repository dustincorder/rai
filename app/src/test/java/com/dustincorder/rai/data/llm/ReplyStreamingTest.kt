package com.dustincorder.rai.data.llm

import com.dustincorder.rai.domain.RayaEmotion
import com.dustincorder.rai.domain.ReplyEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplyStreamingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `progressive prefixes never leak json envelope`() {
        val full = """{"text":"Привет, Райя!","emotion":"happy","language":"ru-RU"}"""
        var previous = ""
        for (end in 1..full.length) {
            val prefix = extractVisibleTextPrefix(full.substring(0, end))
            assertTrue("prefix must grow monotonically", prefix.startsWith(previous))
            assertFalse("no metadata leakage", prefix.contains("emotion"))
            assertFalse("no metadata leakage", prefix.contains("language"))
            assertFalse("no key leakage", prefix.contains("\"text\""))
            previous = prefix
        }
        assertEquals("Привет, Райя!", previous)
    }

    @Test
    fun `escapes and unicode decode only when complete`() {
        assertEquals("a\\", extractVisibleTextPrefix("""{"text":"a\\"""))
        assertEquals("a\"", extractVisibleTextPrefix("""{"text":"a\""}"""))
        assertEquals("", extractVisibleTextPrefix("""{"text":"\u041"""))
        assertEquals("П", extractVisibleTextPrefix("""{"text":"\u041f"}"""))
        assertEquals("a\nb", extractVisibleTextPrefix("""{"text":"a\nb"}"""))
    }

    @Test
    fun `fenced envelope and surrounding whitespace handled`() {
        assertEquals("Hi", extractVisibleTextPrefix("```json\n{\"text\":\"Hi\"}\n```"))
        assertEquals("Hi", extractVisibleTextPrefix("  {\"text\":\"Hi\"}  "))
        assertEquals("", extractVisibleTextPrefix("Просто текст без ключа"))
        assertEquals("", extractVisibleTextPrefix(""))
    }

    @Test
    fun `deltas accumulate to final visible text exactly`() = runTest {
        val chunks = listOf("{\"text\":\"При", "вет, ", "Райя!\"", ",\"emotion\":\"happy\",\"language\":\"ru-RU\"}")
        val events = chunks.asFlowChunks().toReplyEvents(json).toList()

        val deltas = events.filterIsInstance<ReplyEvent.TextDelta>()
        val completed = events.filterIsInstance<ReplyEvent.Completed>()
        assertEquals("Привет, Райя!", deltas.joinToString("") { it.text })
        assertEquals(1, completed.size)
        assertEquals("Привет, Райя!", completed.single().response.text)
        assertEquals(RayaEmotion.Happy, completed.single().response.emotion)
        assertEquals("ru-RU", completed.single().response.languageTag)
    }

    @Test
    fun `malformed final metadata fails safely instead of leaking`() = runTest {
        org.junit.Assert.assertThrows(LlmSafeException::class.java) {
            kotlinx.coroutines.runBlocking {
                listOf("{\"text\":\"сломано\"").asFlowChunks().toReplyEvents(json).toList()
            }
        }
    }

    @Test
    fun `plain text chunks fall back to calm text`() = runTest {
        val events = listOf("Просто ", "ответ").asFlowChunks().toReplyEvents(json).toList()
        val completed = events.filterIsInstance<ReplyEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals("Просто ответ", completed.single().response.text)
        assertEquals(RayaEmotion.Calm, completed.single().response.emotion)
    }

    private fun List<String>.asFlowChunks() = kotlinx.coroutines.flow.flow {
        forEach { emit(it) }
    }
}

class OpenAiStreamingTest {
    private lateinit var server: MockWebServer
    private lateinit var openAi: OpenAiCompatibleReplyProvider

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        openAi = OpenAiCompatibleReplyProvider(client, Json { ignoreUnknownKeys = true })
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `sse deltas stream structured envelope chunks`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""))
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"text\\\":\\\"При\"}}}]}\n" +
                    "\n" +
                    ": keep-alive comment\n" +
                    "\n" +
                    "data: [DONE]\n",
            ),
        )
        val chunks = openAi.streamRaw(
            server.url("/v1").toString(),
            "model",
            "key",
            "System",
            listOf(com.dustincorder.rai.domain.ConversationMessage(com.dustincorder.rai.domain.ConversationRole.User, "Hi")),
        ).toList()
        // The corrupt second chunk is dropped; only the valid envelope prefix streams.
        assertEquals(1, chunks.size)
        assertEquals("hi", chunks.single())
        val request = server.takeRequest()
        assertTrue(request.getHeader("Accept") == "text/event-stream")
        assertTrue(request.body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun `plain json body falls back to single chunk`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""),
        )
        val chunks = openAi.streamRaw(
            server.url("/v1").toString(),
            "model",
            null,
            "System",
            listOf(com.dustincorder.rai.domain.ConversationMessage(com.dustincorder.rai.domain.ConversationRole.User, "Hi")),
        ).toList()

        assertEquals(1, chunks.size)
        assertEquals("hi", chunks.single())
    }

    @Test
    fun `streaming error status surfaces sanitized failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))
        val failure = runCatching {
            openAi.streamRaw(
                server.url("/v1").toString(),
                "model",
                null,
                "System",
                listOf(com.dustincorder.rai.domain.ConversationMessage(com.dustincorder.rai.domain.ConversationRole.User, "Hi")),
            ).toList()
        }.exceptionOrNull()

        assertTrue(failure is LlmHttpException)
        assertEquals(429, (failure as LlmHttpException).statusCode)
    }
}
