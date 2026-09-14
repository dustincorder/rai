package com.dustincorder.rai.presentation

import com.dustincorder.rai.data.llm.ConfigurableReplyProvider
import com.dustincorder.rai.data.llm.OpenAiCompatibleReplyProvider
import com.dustincorder.rai.data.llm.AnthropicCompatibleReplyProvider
import com.dustincorder.rai.data.secrets.ApiKeyStore
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.domain.ConversationLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: TrackingSettingsRepository
    private lateinit var keyStore: TrackingApiKeyStore
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
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
        repository = TrackingSettingsRepository(AppSettings())
        keyStore = TrackingApiKeyStore()
        viewModel = SettingsViewModel(
            repository,
            keyStore,
            ConfigurableReplyProvider(
                repository,
                keyStore,
                OpenAiCompatibleReplyProvider(client, json),
                AnthropicCompatibleReplyProvider(client, json),
                { "System" },
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    @Test
    fun `successful connection test does not persist draft`() = runBlocking {
        val initial = repository.state
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))

        viewModel.testConnection(draftSettings(), "draft-key")
        val status = awaitTerminalStatus()

        assertSame(initial, repository.state)
        assertEquals(0, repository.saveCount)
        assertEquals(0, keyStore.writeCount)
        assertEquals("Подключение работает", status.message)
        assertTrue(!status.isError)
    }

    @Test
    fun `failed connection test does not persist draft`() = runBlocking {
        val initial = repository.state
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"boom"}}"""))

        viewModel.testConnection(draftSettings(), "draft-key")
        val status = awaitTerminalStatus()

        assertSame(initial, repository.state)
        assertEquals(0, repository.saveCount)
        assertEquals(0, keyStore.writeCount)
        assertTrue(status.isError)
        assertEquals("Провайдер вернул ошибку сервера (HTTP 500): boom", status.message)
    }

    @Test
    fun `save persists both settings and api key`() = runBlocking {
        viewModel.save(draftSettings(), "saved-key")
        val status = awaitTerminalStatus()

        assertEquals(1, repository.saveCount)
        assertEquals(1, keyStore.writeCount)
        assertEquals("saved-key", keyStore.writtenKey)
        assertEquals("Настройки сохранены", status.message)
    }

    private suspend fun awaitTerminalStatus(): ConnectionStatus.Message {
        var message: ConnectionStatus.Message? = null
        withTimeout(11_000) {
            while (message == null) {
                val status = viewModel.connectionStatus.value
                if (status is ConnectionStatus.Message) {
                    message = status
                } else {
                    delay(20)
                }
            }
        }
        return checkNotNull(message)
    }

    private fun draftSettings() = AppSettings(
        provider = LlmProviderPreset.Custom,
        customProtocol = LlmProtocol.OpenAiCompatible,
        customBaseUrl = server.url("/v1").toString().trimEnd('/'),
        modelId = "draft-model",
    )
}

private class TrackingSettingsRepository(initial: AppSettings) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    var saveCount = 0

    override suspend fun save(settings: AppSettings) {
        saveCount++
        state.value = settings
    }

    override suspend fun currentLanguage(): ConversationLanguage = state.value.conversationLanguage
}

private class TrackingApiKeyStore : ApiKeyStore {
    var writeCount = 0
    var writtenKey: String? = null

    override suspend fun read(provider: LlmProviderPreset): String? = null
    override suspend fun write(provider: LlmProviderPreset, value: String) {
        writeCount++
        writtenKey = value
    }

    override suspend fun delete(provider: LlmProviderPreset) = Unit
}