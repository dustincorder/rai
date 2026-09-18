package com.dustincorder.rai.data.llm

import com.dustincorder.rai.data.secrets.ApiKeyStorageException
import com.dustincorder.rai.data.secrets.ApiKeyStore
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.domain.ConversationLanguage
import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import com.dustincorder.rai.domain.RayaEmotion
import com.dustincorder.rai.domain.RayaResponse
import com.dustincorder.rai.domain.ReplyEvent
import com.dustincorder.rai.domain.tools.ExecutionKind
import com.dustincorder.rai.domain.tools.ModelRoundStreamEvent
import com.dustincorder.rai.domain.tools.ToolDefinition
import com.dustincorder.rai.domain.tools.ToolEffect
import com.dustincorder.rai.domain.tools.ToolId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            GeminiReplyProvider(client, json),
            { "System" },
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `missing provider configuration avoids network request`() = runTest {
        val failure = runCatching { provider.reply(userMsg("hello"), "en-US") }.exceptionOrNull()
        assertTrue(failure is LlmSafeException)
        assertEquals("API key не сохранён.", failure?.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `provider switching applies to next request`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"one"}}]}"""))
        assertEquals("one", provider.reply(userMsg("hello"), "en-US").text)
        assertEquals("/v1/chat/completions", server.takeRequest().path)

        repository.save(custom(LlmProtocol.AnthropicCompatible))
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"two"}]}"""))
        assertEquals("two", provider.reply(userMsg("hello"), "en-US").text)
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
        val failure = runCatching { provider.reply(userMsg("hello"), "en-US") }.exceptionOrNull()

        assertTrue(failure is LlmSafeException)
        assertEquals("Провайдер вернул ошибку сервера (HTTP 500): boom", failure?.message)
    }

    @Test
    fun `missing configuration fails before network for production reply`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible).copy(modelId = ""))
        keyStore.storedKey = null
        val failure = runCatching { provider.reply(userMsg("hello"), "en-US") }.exceptionOrNull()

        assertTrue(failure is LlmSafeException)
        assertEquals(0, server.requestCount)
        assertEquals("Настрой LLM-провайдера.", failure?.message)
    }

    @Test
    fun `production reply reports missing api key`() = runTest {
        repository.save(AppSettings(provider = LlmProviderPreset.Groq, modelId = "model"))
        keyStore.storedKey = null
        val failure = runCatching { provider.reply(userMsg("hello"), "en-US") }.exceptionOrNull()

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

    @Test
    fun `connection test sends only probe and ignores conversation`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        val result = provider.testConnection(custom(LlmProtocol.OpenAiCompatible).connectionConfig(), "key")

        assertEquals(LlmConnectionResult.Success, result)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"role\":\"user\",\"content\":\"ping\""))
        val messages = body.substringAfter("\"messages\":[")
        assertEquals(1, Regex("""\{"role":"user"""").findAll(messages).count())
        assertFalse(messages.contains("\"role\":\"assistant\""))
    }

    @Test
    fun `production reply sends system once followed by full history`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible).copy(modelId = "model"))
        keyStore.storedKey = "key"
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"D"}}]}"""))
        val reply = provider.reply(
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
                ConversationMessage(ConversationRole.User, "C"),
            ),
            "en-US",
        )

        assertEquals("D", reply.text)
        val body = server.takeRequest().body.readUtf8()
        val messages = body.substringAfter("\"messages\":[")
        assertEquals(1, Regex("""\{"role":"system"""").findAll(messages).count())
        val userA = messages.indexOf("\"role\":\"user\",\"content\":\"A\"")
        val assistantB = messages.indexOf("\"role\":\"assistant\",\"content\":\"B\"")
        val userC = messages.indexOf("\"role\":\"user\",\"content\":\"C\"")
        assertTrue(userA in 0 until assistantB)
        assertTrue(assistantB in 0 until userC)
    }

    @Test
    fun `valid OpenAI structured response parses to the domain model`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible))
        keyStore.storedKey = "key"
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"{\"text\":\"Привет из OpenAI!\",\"emotion\":\"curious\",\"language\":\"en-US\"}"}}]}""",
            ),
        )
        val response = provider.reply(userMsg("hello"), "en-US")

        assertEquals(RayaResponse("Привет из OpenAI!", RayaEmotion.Curious, "en-US"), response)
    }

    @Test
    fun `valid Anthropic structured response parses to the domain model`() = runTest {
        repository.save(custom(LlmProtocol.AnthropicCompatible))
        keyStore.storedKey = "key"
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"{\"text\":\"Привет из Anthropic!\",\"emotion\":\"surprised\",\"language\":\"ru-RU\"}"}]}""",
            ),
        )
        val response = provider.reply(userMsg("hello"), "en-US")

        assertEquals(RayaResponse("Привет из Anthropic!", RayaEmotion.Surprised, "ru-RU"), response)
    }

    @Test
    fun `openai sse stream emits visible deltas then validated completion`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible))
        keyStore.storedKey = "key"
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"{\"text\":\"Привет\",\"emotion\":\"happy\",\"language\":\"ru-RU\"}"}}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"text\\\":\\\"При\"}}}]}\n" +
                    "\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"вет\\\",\\\"emotion\\\":\\\"happy\\\",\\\"language\\\":\\\"ru-RU\\\"}}}]}}\n" +
                    "\n" +
                "data: [DONE]\n",
            ),
        )
        val events = provider.streamReply(userMsg("hello"), "en-US").toList()

        val deltas = events.filterIsInstance<ReplyEvent.TextDelta>()
        val completed = events.filterIsInstance<ReplyEvent.Completed>()
        assertEquals("Привет", deltas.joinToString("") { it.text })
        assertEquals(1, completed.size)
        assertEquals(RayaResponse("Привет", RayaEmotion.Happy, "ru-RU"), completed.single().response)
        assertTrue(deltas.none { it.text.contains("emotion") })
    }

    @Test
    fun `anthropic adapts non streaming into single completion`() = runTest {
        repository.save(custom(LlmProtocol.AnthropicCompatible))
        keyStore.storedKey = "key"
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"{\"text\":\"Hi\",\"emotion\":\"calm\"}"}]}""",
            ),
        )
        val events = provider.streamReply(userMsg("hello"), "en-US").toList()

        assertEquals(2, events.size)
        val completed = events.last() as ReplyEvent.Completed
        assertEquals("Hi", completed.response.text)
    }

    @Test
    fun `streamRound emits multiple TextDeltas before Completed for streamed reply`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible))
        keyStore.storedKey = "key"
        val sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"world!\"}}]}\n\n" +
            "data: [DONE]\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val events = provider.streamRound(
            messages = userMsg("hi"),
            candidateTools = emptyList(),
            steps = emptyList(),
            languageTag = "en-US",
        ).toList()

        // First event is ExposedTools
        assertTrue(events[0] is ModelRoundStreamEvent.ExposedTools)
        val textDeltas = events.filterIsInstance<ModelRoundStreamEvent.TextDelta>()
        assertTrue("Expected more than one TextDelta, got ${textDeltas.size}", textDeltas.size > 1)
        assertEquals("Hello ", textDeltas[0].text)
        assertEquals("world!", textDeltas[1].text)

        val completed = events.last() as ModelRoundStreamEvent.Completed
        assertEquals("Hello world!", completed.response.text)
    }

    @Test
    fun `settings lookup failure exposes zero tools and fails closed`() = runTest {
        val failingRepo = object : SettingsRepository {
            override val settings: Flow<AppSettings> = flow { error("Disk read failed") }
            override val modelCache: Flow<Map<String, List<String>>> = emptyFlow()
            override suspend fun save(settings: AppSettings) {}
            override suspend fun saveModelCache(providerName: String, modelIds: List<String>) {}
            override suspend fun currentLanguage() = ConversationLanguage.Auto
        }
        val p = ConfigurableReplyProvider(
            failingRepo,
            keyStore,
            OpenAiCompatibleReplyProvider(OkHttpClient(), Json { ignoreUnknownKeys = true }),
            AnthropicCompatibleReplyProvider(OkHttpClient(), Json { ignoreUnknownKeys = true }),
            GeminiReplyProvider(OkHttpClient(), Json { ignoreUnknownKeys = true }),
            { "System" },
        )
        val tool = ToolDefinition(
            id = ToolId("tool_1"),
            name = "search",
            description = "search",
            effect = ToolEffect.ReadOnly,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = buildJsonObject { put("type", "object") },
        )
        val emittedEvents = mutableListOf<ModelRoundStreamEvent>()
        val failure = runCatching {
            p.streamRound(
                messages = userMsg("hi"),
                candidateTools = listOf(tool),
                steps = emptyList(),
                languageTag = "en-US",
            ).collect { emittedEvents.add(it) }
        }.exceptionOrNull()

        assertTrue("Expected failure on settings read", failure is LlmConfigurationException)
        val exposed = emittedEvents.filterIsInstance<ModelRoundStreamEvent.ExposedTools>().firstOrNull()
        assertNotNull("Must emit ExposedTools even on failure", exposed)
        assertTrue("Settings failure must expose 0 tools (fail closed)", exposed!!.tools.isEmpty())
    }

    @Test
    fun `unsupported schema tool is omitted from exposed tools`() = runTest {
        repository.save(custom(LlmProtocol.OpenAiCompatible))
        keyStore.storedKey = "key"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n"),
        )
        val unsupportedTool = ToolDefinition(
            id = ToolId("bad_tool"),
            name = "bad",
            description = "bad schema",
            effect = ToolEffect.ReadOnly,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = buildJsonObject { put("type", "string") }, // invalid root schema for OpenAI
        )
        val events = provider.streamRound(
            messages = userMsg("hi"),
            candidateTools = listOf(unsupportedTool),
            steps = emptyList(),
            languageTag = "en-US",
        ).toList()

        val exposed = events.filterIsInstance<ModelRoundStreamEvent.ExposedTools>().first()
        assertTrue("Unsupported schema must be omitted from exposed tools", exposed.tools.isEmpty())
    }

    @Test
    fun `provider change between calls updates exact round tool exposure`() = runTest {
        val defsTool = ToolDefinition(
            id = ToolId("defs_tool"),
            name = "defs_tool",
            description = "Tool with defs",
            effect = ToolEffect.ReadOnly,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = buildJsonObject {
                put("type", "object")
                put("\$defs", buildJsonObject {
                    put("Item", buildJsonObject { put("type", "string") })
                })
                put("properties", buildJsonObject {
                    put("val", buildJsonObject { put("\$ref", JsonPrimitive("#/\$defs/Item")) })
                })
            },
        )

        // Round 1: OpenAI
        repository.save(custom(LlmProtocol.OpenAiCompatible))
        keyStore.storedKey = "key"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n"),
        )
        val events1 = provider.streamRound(
            messages = userMsg("hi"),
            candidateTools = listOf(defsTool),
            steps = emptyList(),
            languageTag = "en-US",
        ).toList()
        val exposed1 = events1.filterIsInstance<ModelRoundStreamEvent.ExposedTools>().first()
        assertEquals(1, exposed1.tools.size)
        assertEquals("defs_tool", exposed1.tools[0].name)

        // Switch to Gemini protocol
        repository.save(custom(LlmProtocol.Gemini))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"OK\"}]}}]}\n\n"),
        )
        val events2 = provider.streamRound(
            messages = userMsg("hi"),
            candidateTools = listOf(defsTool),
            steps = emptyList(),
            languageTag = "en-US",
        ).toList()
        val exposed2 = events2.filterIsInstance<ModelRoundStreamEvent.ExposedTools>().first()
        assertTrue("Gemini does not support \$defs, so it must be omitted", exposed2.tools.isEmpty())
    }

    private fun custom(protocol: LlmProtocol) = AppSettings(
        provider = LlmProviderPreset.Custom,
        customProtocol = protocol,
        customBaseUrl = server.url("/v1").toString().trimEnd('/'),
        modelId = "model",
    )
private fun userMsg(text: String) = listOf(ConversationMessage(ConversationRole.User, text))
}

private class FakeSettingsRepository(initial: AppSettings) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    var saveCount = 0
    private val cache = MutableStateFlow(emptyMap<String, List<String>>())
    override val modelCache: Flow<Map<String, List<String>>> = cache

    override suspend fun save(settings: AppSettings) {
        saveCount++
        state.value = settings
    }

    override suspend fun saveModelCache(providerName: String, modelIds: List<String>) {
        cache.value = cache.value + (providerName to modelIds)
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
