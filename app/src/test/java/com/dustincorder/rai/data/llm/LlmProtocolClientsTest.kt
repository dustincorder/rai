package com.dustincorder.rai.data.llm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmProtocolClientsTest {
    private lateinit var server: MockWebServer
    private lateinit var openAi: OpenAiCompatibleReplyProvider
    private lateinit var anthropic: AnthropicCompatibleReplyProvider

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
        val json = Json { ignoreUnknownKeys = true }
        openAi = OpenAiCompatibleReplyProvider(client, json)
        anthropic = AnthropicCompatibleReplyProvider(client, json)
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `OpenAI request serializes and response parses`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Hello"}}]}"""))
        val reply = openAi.reply(server.url("/v1").toString(), "test-model", "secret", "System", "Hi")

        assertEquals("Hello", reply)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"content\":\"Hi\""))
    }

    @Test
    fun `OpenAI custom provider omits empty authorization`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        openAi.reply(server.url("/v1").toString(), "model", null, "System", "Hi")
        assertFalse(server.takeRequest().headers.names().contains("Authorization"))
    }

    @Test
    fun `Anthropic request serializes and response parses`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Привет"}]}"""))
        val reply = anthropic.reply(server.url("/v1").toString(), "claude-test", "secret", "System", "Hi")

        assertEquals("Привет", reply)
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("secret", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"system\":\"System\""))
        assertTrue(body.contains("\"max_tokens\":512"))
    }

    @Test
    fun `non 2xx response throws sanitized HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret provider details"))
        val failure = runCatching {
            openAi.reply(server.url("/v1").toString(), "model", "secret", "System", "Hi")
        }.exceptionOrNull()

        assertTrue(failure is LlmHttpException)
        assertEquals("LLM-провайдер вернул ошибку HTTP 401.", failure?.message)
        assertFalse(failure?.message.orEmpty().contains("secret"))
    }
}
