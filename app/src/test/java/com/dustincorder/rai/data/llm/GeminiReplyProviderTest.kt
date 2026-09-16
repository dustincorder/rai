package com.dustincorder.rai.data.llm

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiReplyProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var gemini: GeminiReplyProvider

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
        gemini = GeminiReplyProvider(client, Json { ignoreUnknownKeys = true })
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `generateContent response parses joined parts`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"Привет, "},{"text":"Райя!"}]}}]}""",
            ),
        )
        val reply = gemini.reply(server.url("/v1beta").toString(), "gemini-2.0-flash", "key", "System", userMsg())

        assertEquals("Привет, Райя!", reply)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.0-flash:generateContent", request.path)
        assertEquals("key", request.getHeader("x-goog-api-key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("Hi"))
    }

    @Test
    fun `generateContent assistant role maps to model role`() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))
        gemini.reply(
            server.url("/v1beta").toString(),
            "gemini-2.0-flash",
            "key",
            "System",
            listOf(
                ConversationMessage(ConversationRole.User, "a"),
                ConversationMessage(ConversationRole.Assistant, "b"),
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"role\":\"model\""))
    }

    @Test
    fun `empty candidates raise provider error`() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[]}"""))
        val failure = runCatching {
            gemini.reply(server.url("/v1beta").toString(), "gemini-2.0-flash", "key", "System", userMsg())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `models list parses ids and classifies capabilities`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"models":[
                    {"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash","supportedGenerationMethods":["generateContent"]},
                    {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}
                ]}""",
            ),
        )
        val models = gemini.listModels(server.url("/v1beta").toString(), "key")

        assertEquals("/v1beta/models?pageSize=100", server.takeRequest().path)
        assertEquals(2, models.size)
        assertEquals("gemini-2.0-flash", models[0].id)
        assertEquals("Gemini 2.0 Flash", models[0].label)
        assertTrue(models[0].supports(LlmModelCapability.Chat))
        assertEquals(setOf(LlmModelCapability.Embedding), models[1].capabilities)
    }

    @Test
    fun `gemini http error surfaces status without key leakage`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad key"}}"""))
        val failure = runCatching {
            gemini.reply(server.url("/v1beta").toString(), "m", "SECRET-KEY", "System", userMsg())
        }.exceptionOrNull()

        assertTrue(failure is LlmHttpException)
        assertEquals(400, (failure as LlmHttpException).statusCode)
        assertEquals("bad key", failure.providerMessage)
    }

    private fun userMsg() = listOf(ConversationMessage(ConversationRole.User, "Hi"))
}
