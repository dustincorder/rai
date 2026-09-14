package com.dustincorder.rai.data.llm

import com.dustincorder.rai.data.secrets.ApiKeyStorageException
import com.dustincorder.rai.data.secrets.ApiKeyStore
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.domain.ConversationLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

class ConfigurableReplyProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: FakeSettingsRepository
    private lateinit var keyStore: FakeApiKeyStore
    private lateinit var provider: ConfigurableReplyProvider

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
        repository = FakeSettingsRepository(AppSettings())
        keyStore = FakeApiKeyStore()
        provider = ConfigurableReplyProvider(
            repository,
            keyStore,
            OpenAiCompatibleReplyProvider(client, json),
            AnthropicCompatibleReplyProvider(client, json),
            { "System" },
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `missing provider configuration avoids network request`() = runTest {
        val failure = runCatching { provider.reply("hello", "en-US") }.exceptionOrNull()
        assertTrue(failure is LlmSafeException)
        assertEquals("API key не сохранён.", failure?.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `provider switching applies to next request`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"one"}}]}"""))
        assertEquals("one", provider.reply("hello", "en-US"))
        assertEquals("/v1/chat/completions", server.takeRequest().path)

        repository.save(custom(LlmProtocol.AnthropicCompatible))
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"two"}]}"""))
        assertEquals("two", provider.reply("hello", "en-US"))
        assertEquals("/v1/messages", server.takeRequest().path)
    }

    @Test
    fun `successful connection test does not persist draft settings`() = runTest {
        val initial = repository.settings.first()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        val result = provider.testConnection(custom(LlmProtocol.OpenAiCompatible).connectionConfig(), "key")

        assertEquals(LlmConnectionResult.Success, result)
        assertEquals(initial, repository.settings.first())
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun `failed connection test does not persist draft settings`() = runTest {
        val initial = repository.settings.first()
        server.enqueue(MockResponse().setResponseCode(500))
        val result = provider.testConnection(custom(LlmProtocol.OpenAiCompatible).connectionConfig(), "key")

        assertTrue(result is LlmConnectionResult.Failure)
        assertEquals(initial, repository.settings.first())
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun `connection test never writes api keys`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        provider.testConnection(custom(LlmProtocol.OpenAiCompatible).connectionConfig(), "draft-key")
        assertEquals(0, keyStore.writeCount)
    }

    @Test
    fun `connection test reuses stored key for builtin provider when draft key is blank`() = runTest {
        keyStore.storedKey = "stored-key"
        val config = LlmConnectionConfig(
            provider = LlmProviderPreset.Groq,
            protocol = LlmProtocol.OpenAiCompatible,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            modelId = "model",
        )
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        val result = provider.testConnection(config, "")

        assertEquals(LlmConnectionResult.Success, result)
        assertEquals("Bearer stored-key", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `custom connection test never reuses stored key for a different endpoint`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible).copy(customBaseUrl = "https://old.example/v1"))
        keyStore.storedKey = "SECRET_A"
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        val result = provider.testConnection(custom(LlmProtocol.OpenAiCompatible).connectionConfig(), "")

        assertEquals(LlmConnectionResult.Success, result)
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `custom connection test reuses stored key only for the saved endpoint`() = runTest {
        val saved = custom(LlmProtocol.OpenAiCompatible)
        repository.save(saved)
        keyStore.storedKey = "SECRET_A"
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        val result = provider.testConnection(saved.connectionConfig(), "")

        assertEquals(LlmConnectionResult.Success, result)
        assertEquals("Bearer SECRET_A", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `connection test requires api key for keyed providers`() = runTest {
        val keyedConfig = AppSettings(
            provider = LlmProviderPreset.Groq,
            modelId = "openai/gpt-oss-20b",
        )
        val result = provider.testConnection(keyedConfig.connectionConfig(), "")

        assertEquals(LlmConnectionResult.Failure("API key не сохранён."), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `connection refused maps to safe user message`() = runTest {
        val config = custom(LlmProtocol.OpenAiCompatible).copy(
            customBaseUrl = "https://127.0.0.1:1",
        )
        val result = provider.testConnection(config.connectionConfig(), "key")

        assertTrue(result is LlmConnectionResult.Failure)
        assertEquals("Не удалось подключиться к провайдеру.", (result as LlmConnectionResult.Failure).message)
    }

    @Test
    fun `production reply maps network failure to safe user message`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"boom"}}"""))
        val failure = runCatching { provider.reply("hello", "en-US") }.exceptionOrNull()

        assertTrue(failure is LlmSafeException)
        assertEquals("Провайдер вернул ошибку сервера (HTTP 500): boom", failure?.message)
    }

    @Test
    fun `missing configuration fails before network for production reply`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible).copy(modelId = ""))
        keyStore.storedKey = null
        val failure = runCatching { provider.reply("hello", "en-US") }.exceptionOrNull()

        assertTrue(failure is LlmSafeException)
        assertEquals(0, server.requestCount)
        assertEquals("Настрой LLM-провайдера.", failure?.message)
    }

    @Test
    fun `production reply reports missing api key`() = runTest {
        repository.save(AppSettings(provider = LlmProviderPreset.Groq, modelId = "model"))
        keyStore.storedKey = null
        val failure = runCatching { provider.reply("hello", "en-US") }.exceptionOrNull()

        assertTrue(failure is LlmSafeException)
        assertEquals("API key не сохранён.", failure?.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `connection test maps stored key read failure to safe message`() = runTest {
        keyStore.failRead = true
        val config = LlmConnectionConfig(
            provider = LlmProviderPreset.Groq,
            protocol = LlmProtocol.OpenAiCompatible,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            modelId = "model",
        )
        val result = provider.testConnection(config, "")

        assertTrue(result is LlmConnectionResult.Failure)
        assertEquals(
            "Не удалось прочитать сохранённый API key. Замените или удалите его.",
            (result as LlmConnectionResult.Failure).message,
        )
    }

    @Test
    fun `custom connection test rejects http when insecure disabled`() = runTest {
        val config = LlmConnectionConfig(
            provider = LlmProviderPreset.Custom,
            protocol = LlmProtocol.OpenAiCompatible,
            baseUrl = "http://192.168.1.2:8080/v1",
            modelId = "model",
        )
        val result = provider.testConnection(config, null)

        assertTrue(result is LlmConnectionResult.Failure)
        assertEquals("HTTP для Custom provider отключён. Разрешите его в настройках.", (result as LlmConnectionResult.Failure).message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `custom connection test allows http when explicitly enabled`() = runTest {
        val httpServer = MockWebServer()
        httpServer.start()
        try {
            httpServer.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
            val config = LlmConnectionConfig(
                provider = LlmProviderPreset.Custom,
                protocol = LlmProtocol.OpenAiCompatible,
                baseUrl = httpServer.url("/v1").toString().trimEnd('/'),
                modelId = "model",
                allowInsecureHttp = true,
            )
            val result = provider.testConnection(config, null)

            assertEquals(LlmConnectionResult.Success, result)
            assertEquals("/v1/chat/completions", httpServer.takeRequest().path)
        } finally {
            httpServer.shutdown()
        }
    }

    @Test
    fun `builtin providers never allow http transport`() = runTest {
        val config = LlmConnectionConfig(
            provider = LlmProviderPreset.OpenAI,
            protocol = LlmProtocol.OpenAiCompatible,
            baseUrl = "http://api.openai.com/v1",
            modelId = "gpt-4o-mini",
            allowInsecureHttp = true,
        )
        val result = provider.testConnection(config, "key")

        assertTrue(result is LlmConnectionResult.Failure)
        assertEquals("HTTP не разрешён для этого провайдера.", (result as LlmConnectionResult.Failure).message)
        assertEquals(0, server.requestCount)
    }

    private fun custom(protocol: LlmProtocol) = AppSettings(
        provider = LlmProviderPreset.Custom,
        customProtocol = protocol,
        customBaseUrl = server.url("/v1").toString().trimEnd('/'),
        modelId = "model",
    )
}

private class FakeSettingsRepository(initial: AppSettings) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    var saveCount = 0

    override suspend fun save(settings: AppSettings) {
        saveCount++
        state.value = settings
    }

    override suspend fun currentLanguage(): ConversationLanguage = state.value.conversationLanguage
}

private class FakeApiKeyStore : ApiKeyStore {
    var storedKey: String? = null
    var writeCount = 0
    var failRead = false

    override suspend fun read(provider: LlmProviderPreset): String? {
        if (failRead) throw ApiKeyStorageException("Не удалось прочитать сохранённый API key. Замените или удалите его.")
        return storedKey
    }

    override suspend fun write(provider: LlmProviderPreset, value: String) {
        writeCount++
        storedKey = value
    }

    override suspend fun delete(provider: LlmProviderPreset) {
        storedKey = null
    }
}