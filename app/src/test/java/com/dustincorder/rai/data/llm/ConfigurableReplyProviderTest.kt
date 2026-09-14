package com.dustincorder.rai.data.llm

import com.dustincorder.rai.data.secrets.ApiKeyStore
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.domain.ConversationLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        provider = ConfigurableReplyProvider(
            repository,
            FakeApiKeyStore(),
            OpenAiCompatibleReplyProvider(client, json),
            AnthropicCompatibleReplyProvider(client, json),
            { "System" },
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `missing provider configuration avoids network request`() = runTest {
        val failure = runCatching { provider.reply("hello", "en-US") }.exceptionOrNull()
        assertTrue(failure is LlmConfigurationException)
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
    override suspend fun save(settings: AppSettings) { state.value = settings }
    override suspend fun currentLanguage(): ConversationLanguage = state.value.conversationLanguage
}

private class FakeApiKeyStore : ApiKeyStore {
    override suspend fun read(provider: LlmProviderPreset): String? = null
    override suspend fun write(provider: LlmProviderPreset, value: String) = Unit
    override suspend fun delete(provider: LlmProviderPreset) = Unit
}
