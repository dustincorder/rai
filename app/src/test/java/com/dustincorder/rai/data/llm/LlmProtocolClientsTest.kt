package com.dustincorder.rai.data.llm

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
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
        val reply = openAi.reply(server.url("/v1").toString(), "test-model", "secret", "System", userMsg("Hi"))

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
        openAi.reply(server.url("/v1").toString(), "model", null, "System", userMsg("Hi"))
        assertFalse(server.takeRequest().headers.names().contains("Authorization"))
    }

    @Test
    fun `Anthropic request serializes and response parses`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Привет"}]}"""))
        val reply = anthropic.reply(server.url("/v1").toString(), "claude-test", "secret", "System", userMsg("Hi"))

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
    fun `non 2xx response is classified with safe sanitized message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret provider details"))
        val failure = runCatching {
            openAi.reply(server.url("/v1").toString(), "model", "secret", "System", userMsg("Hi"))
        }.exceptionOrNull()

        assertTrue(failure is LlmHttpException)
        assertEquals(401, (failure as LlmHttpException).statusCode)
        val message = LlmErrorClassifier.userMessage(failure)
        assertEquals("Неверный API key или провайдер отклонил авторизацию.", message)
        assertFalse(message.contains("secret"))
    }

    @Test
    fun `json provider error message is extracted safely`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Модель openai/gpt-oss-20b недоступна для этого проекта.","type":"invalid_request_error"}}""",
            ),
        )
        val failure = runCatching {
            openAi.reply(server.url("/v1").toString(), "model", "secret", "System", userMsg("Hi"))
        }.exceptionOrNull()
        val message = LlmErrorClassifier.userMessage(failure!!)
        assertEquals("Некорректный запрос к провайдеру: Модель openai/gpt-oss-20b недоступна для этого проекта.", message)
    }

    @Test
    fun `malformed error body falls back to safe message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html><body>upstream exploded</body></html>"))
        val failure = runCatching {
            openAi.reply(server.url("/v1").toString(), "model", "secret", "System", userMsg("Hi"))
        }.exceptionOrNull()
        val message = LlmErrorClassifier.userMessage(failure!!)
        assertEquals("Провайдер вернул ошибку сервера (HTTP 500).", message)
        assertFalse(message.contains("<html>"))
    }

    @Test
    fun `OpenAI uses full chat completions endpoint unchanged`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        openAi.reply(server.url("/v1/chat/completions").toString(), "model", null, "System", userMsg("Hi"))
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `Anthropic uses full messages endpoint unchanged`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"OK"}]}"""))
        anthropic.reply(server.url("/v1/messages").toString(), "model", null, "System", userMsg("Hi"))
        assertEquals("/v1/messages", server.takeRequest().path)
    }

    @Test
    fun `slash model id is serialized unchanged`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        openAi.reply(server.url("/v1").toString(), "openai/gpt-oss-20b", "secret", "System", userMsg("Hi"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"model\":\"openai/gpt-oss-20b\""))
    }

    @Test
    fun `slash model id qwen is serialized unchanged`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        openAi.reply(server.url("/v1").toString(), "qwen/example-model", "secret", "System", userMsg("Hi"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"model\":\"qwen/example-model\""))
    }

    @Test
    fun `OpenAI sends system once followed by conversation history`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        openAi.reply(
            server.url("/v1").toString(),
            "model",
            null,
            "System",
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
                ConversationMessage(ConversationRole.User, "C"),
            ),
        )
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        val systemIndex = body.indexOf("\"role\":\"system\",\"content\":\"System\"")
        val userIndex = body.indexOf("\"role\":\"user\",\"content\":\"A\"")
        val assistantIndex = body.indexOf("\"role\":\"assistant\",\"content\":\"B\"")
        val lastUserIndex = body.indexOf("\"role\":\"user\",\"content\":\"C\"")
        assertEquals(1, Regex("""\{"role":"system","content":"System"\}""").findAll(body).count())
        assertTrue(systemIndex in 0 until userIndex)
        assertTrue(userIndex in 0 until assistantIndex)
        assertTrue(assistantIndex in 0 until lastUserIndex)
    }

    @Test
    fun `Anthropic keeps system separate and conversation roles ordered`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"OK"}]}"""))
        anthropic.reply(
            server.url("/v1").toString(),
            "model",
            null,
            "System",
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
                ConversationMessage(ConversationRole.User, "C"),
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        val systemIndex = body.indexOf("\"system\":\"System\"")
        val userIndex = body.indexOf("\"messages\":[{\"role\":\"user\",\"content\":\"A\"}")
        val assistantIndex = body.indexOf("\"role\":\"assistant\",\"content\":\"B\"")
        val lastUserIndex = body.indexOf("\"role\":\"user\",\"content\":\"C\"")
        assertTrue(systemIndex in 0 until userIndex)
        assertTrue(userIndex in 0 until assistantIndex)
        assertTrue(assistantIndex in 0 until lastUserIndex)
        assertFalse(body.substringAfter("\"messages\":[").contains("\"role\":\"system\""))
    }

    private fun userMsg(text: String) = listOf(ConversationMessage(ConversationRole.User, text))
}