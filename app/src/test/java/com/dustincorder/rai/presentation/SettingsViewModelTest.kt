package com.dustincorder.rai.presentation

import com.dustincorder.rai.data.llm.ConfigurableReplyProvider
import com.dustincorder.rai.data.llm.OpenAiCompatibleReplyProvider
import com.dustincorder.rai.data.llm.AnthropicCompatibleReplyProvider
import com.dustincorder.rai.data.secrets.ApiKeyStorageException
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
    fun `restart-like recreation shows saved key indicator`() = runBlocking {
        keyStore.storedKey = "saved"
        val recreated = SettingsViewModel(repository, keyStore, viewModelReplyProvider())

        assertEquals(ApiKeyStatus.Configured, awaitKeyStatus(recreated, ApiKeyStatus.Configured))
    }

    @Test
    fun `missing key shows not saved indicator`() = runBlocking {
        keyStore.storedKey = null
        val recreated = SettingsViewModel(repository, keyStore, viewModelReplyProvider())

        assertEquals(ApiKeyStatus.Missing, awaitKeyStatus(recreated, ApiKeyStatus.Missing))
    }

    @Test
    fun `blank text field does not delete stored key`() = runBlocking {
        keyStore.storedKey = "saved"
        viewModel.save(draftSettings(), "")

        awaitKeyStatus(viewModel, ApiKeyStatus.Configured)
        assertEquals("saved", keyStore.storedKey)
    }

    @Test
    fun `save with new key updates indicator`() = runBlocking {
        viewModel.save(draftSettings(), "new-key")

        awaitKeyStatus(viewModel, ApiKeyStatus.Configured)
        assertEquals("new-key", keyStore.storedKey)
    }

    @Test
    fun `explicit delete clears key and indicator`() = runBlocking {
        keyStore.storedKey = "saved"
        viewModel.deleteKey(LlmProviderPreset.Custom)

        withTimeout(11_000) { while (keyStore.storedKey != null) delay(20) }
        assertEquals(null, keyStore.storedKey)
        awaitKeyStatus(viewModel, ApiKeyStatus.Missing)
        assertEquals(ApiKeyStatus.Missing, viewModel.apiKeyStatus.value)
    }

    @Test
    fun `unreadable stored key surfaces unreadable indicator`() = runBlocking {
        keyStore.storedKey = "saved"
        keyStore.failRead = true
        val recreated = SettingsViewModel(repository, keyStore, viewModelReplyProvider())

        assertEquals(ApiKeyStatus.Unreadable, awaitKeyStatus(recreated, ApiKeyStatus.Unreadable))
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

    @Test
    fun `stale custom key deletion failure aborts save and keeps old config`() = runBlocking {
        val oldSettings = customSettings(LlmProtocol.OpenAiCompatible, "https://host/v1")
        repository.save(oldSettings)
        keyStore.storedKey = "SECRET_A"
        keyStore.failDelete = true

        viewModel.save(customSettings(LlmProtocol.OpenAiCompatible, "https://host/v2"), "")
        val status = awaitTerminalStatus()

        assertTrue(status.isError)
        assertEquals("Не удалось удалить API key.", status.message)
        assertEquals(1, repository.saveCount)
        assertEquals(oldSettings, repository.state.value)
    }

    @Test
    fun `custom protocol change deletes stale key`() = runBlocking {
        repository.save(customSettings(LlmProtocol.OpenAiCompatible, "https://host/v1"))
        keyStore.storedKey = "SECRET_A"

        viewModel.save(customSettings(LlmProtocol.AnthropicCompatible, "https://host/v1"), "")
        awaitTerminalStatus()

        assertEquals(null, keyStore.storedKey)
        assertEquals(2, repository.saveCount)
    }

    @Test
    fun `trailing slash change does not count as different endpoint`() = runBlocking {
        repository.save(customSettings(LlmProtocol.OpenAiCompatible, "https://host/v1/"))
        keyStore.storedKey = "SECRET_A"

        viewModel.save(customSettings(LlmProtocol.OpenAiCompatible, "https://host/v1"), "")
        awaitTerminalStatus()

        assertEquals("SECRET_A", keyStore.storedKey)
        assertEquals(2, repository.saveCount)
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

    private suspend fun awaitKeyStatus(vm: SettingsViewModel = viewModel, expected: ApiKeyStatus): ApiKeyStatus {
        withTimeout(11_000) {
            while (vm.apiKeyStatus.value != expected) {
                delay(20)
            }
        }
        return vm.apiKeyStatus.value
    }

    private fun viewModelReplyProvider(): ConfigurableReplyProvider {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        val json = Json { ignoreUnknownKeys = true }
        return ConfigurableReplyProvider(
            repository,
            keyStore,
            OpenAiCompatibleReplyProvider(client, json),
            AnthropicCompatibleReplyProvider(client, json),
            { "System" },
        )
    }

    private fun draftSettings() = AppSettings(
        provider = LlmProviderPreset.Custom,
        customProtocol = LlmProtocol.OpenAiCompatible,
        customBaseUrl = server.url("/v1").toString().trimEnd('/'),
        modelId = "draft-model",
    )

    private fun customSettings(protocol: LlmProtocol, url: String) = AppSettings(
        provider = LlmProviderPreset.Custom,
        customProtocol = protocol,
        customBaseUrl = url,
        modelId = "model",
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
    var storedKey: String? = null
    var failRead = false
    var failDelete = false

    override suspend fun read(provider: LlmProviderPreset): String? {
        if (failRead) throw ApiKeyStorageException("Не удалось прочитать сохранённый API key. Замените или удалите его.")
        return storedKey
    }

    override suspend fun write(provider: LlmProviderPreset, value: String) {
        writeCount++
        writtenKey = value
        storedKey = value
    }

    override suspend fun delete(provider: LlmProviderPreset) {
        if (failDelete) throw ApiKeyStorageException("Не удалось удалить API key.")
        storedKey = null
    }
}