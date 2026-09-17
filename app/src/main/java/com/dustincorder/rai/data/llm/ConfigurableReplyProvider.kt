package com.dustincorder.rai.data.llm

import android.util.Log
import com.dustincorder.rai.BuildConfig
import com.dustincorder.rai.data.secrets.ApiKeyStore
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.data.settings.normalizeBaseUrl
import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import com.dustincorder.rai.domain.ChatTitleGenerator
import com.dustincorder.rai.domain.sanitizeChatTitle
import com.dustincorder.rai.domain.RayaResponse
import com.dustincorder.rai.domain.ReplyEvent
import com.dustincorder.rai.domain.ReplyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

import com.dustincorder.rai.data.llm.tools.AnthropicToolAdapter
import com.dustincorder.rai.data.llm.tools.GeminiToolAdapter
import com.dustincorder.rai.data.llm.tools.OpenAiToolAdapter
import com.dustincorder.rai.domain.tools.ModelRoundResponse
import com.dustincorder.rai.domain.tools.ModelRoundStep
import com.dustincorder.rai.domain.tools.ModelTurnInvoker
import com.dustincorder.rai.domain.tools.ToolDefinition
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ConfigurableReplyProvider(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val openAi: OpenAiCompatibleReplyProvider,
    private val anthropic: AnthropicCompatibleReplyProvider,
    private val gemini: GeminiReplyProvider,
    private val systemPrompt: () -> String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ReplyProvider, ChatTitleGenerator, ModelTurnInvoker {
    override suspend fun reply(messages: List<ConversationMessage>, languageTag: String?): RayaResponse {
        if (messages.isEmpty()) throw LlmSafeException("Пустая беседа.")
        val settings = settingsRepository.settings.first()
        val config = settings.connectionConfig()
        return try {
            requireTransportAllowed(settings.provider, settings.baseUrl, settings.customAllowInsecureHttp)
            val apiKey = apiKeyStore.read(settings.provider)
            val modelId = settings.resolvedModelId()
            if (modelId.isBlank() || settings.baseUrl.isBlank()) {
                throw LlmConfigurationException("Настрой LLM-провайдера.")
            }
            if (settings.provider.requiresApiKey && apiKey.isNullOrBlank()) {
                throw LlmSafeException("API key не сохранён.")
            }
            val prompt = buildString {
                append(systemPrompt())
                if (!languageTag.isNullOrBlank()) append("\nLikely user language: $languageTag.")
            }
            val raw = when (settings.protocol) {
                LlmProtocol.OpenAiCompatible -> openAi.reply(settings.baseUrl, modelId, apiKey, prompt, messages)
                LlmProtocol.AnthropicCompatible -> anthropic.reply(settings.baseUrl, modelId, apiKey, prompt, messages)
                LlmProtocol.Gemini -> gemini.reply(settings.baseUrl, modelId, apiKey, prompt, messages)
            }
            parseRayaResponse(raw, json)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logDiagnostics(failure, config)
            throw LlmSafeException(LlmErrorClassifier.userMessage(failure))
        }
    }

    override suspend fun generate(messages: List<ConversationMessage>): String? {
        if (messages.isEmpty()) return null
        val settings = settingsRepository.settings.first()
        requireTransportAllowed(settings.provider, settings.baseUrl, settings.customAllowInsecureHttp)
        val apiKey = apiKeyStore.read(settings.provider)
        val modelId = settings.resolvedModelId()
        if (modelId.isBlank() || settings.baseUrl.isBlank() ||
            (settings.provider.requiresApiKey && apiKey.isNullOrBlank())
        ) return null
        val prompt = "Generate a short neutral title for this conversation. Return only the title, no quotes, markdown, or explanation."
        val raw = when (settings.protocol) {
            LlmProtocol.OpenAiCompatible -> openAi.reply(settings.baseUrl, modelId, apiKey, prompt, messages.take(6))
            LlmProtocol.AnthropicCompatible -> anthropic.reply(settings.baseUrl, modelId, apiKey, prompt, messages.take(6))
            LlmProtocol.Gemini -> gemini.reply(settings.baseUrl, modelId, apiKey, prompt, messages.take(6))
        }
        return sanitizeChatTitle(raw)
    }

    override fun streamReply(
        messages: List<ConversationMessage>,
        languageTag: String?,
    ): Flow<ReplyEvent> = flow {
        if (messages.isEmpty()) throw LlmSafeException("Пустая беседа.")
        val settings = settingsRepository.settings.first()
        val config = settings.connectionConfig()
        try {
            requireTransportAllowed(settings.provider, settings.baseUrl, settings.customAllowInsecureHttp)
            val apiKey = apiKeyStore.read(settings.provider)
            val modelId = settings.resolvedModelId()
            if (modelId.isBlank() || settings.baseUrl.isBlank()) {
                throw LlmConfigurationException("Настрой LLM-провайдера.")
            }
            if (settings.provider.requiresApiKey && apiKey.isNullOrBlank()) {
                throw LlmSafeException("API key не сохранён.")
            }
            val prompt = buildString {
                append(systemPrompt())
                if (!languageTag.isNullOrBlank()) append("\nLikely user language: $languageTag.")
            }
            val raw: Flow<String> = when (settings.protocol) {
                LlmProtocol.OpenAiCompatible ->
                    openAi.streamRaw(settings.baseUrl, modelId, apiKey, prompt, messages)
                LlmProtocol.Gemini ->
                    gemini.streamRaw(settings.baseUrl, modelId, apiKey, prompt, messages)
                // TODO: picks up real Anthropic SSE streaming; adapts non-streaming for now.
                LlmProtocol.AnthropicCompatible -> flow {
                    emit(anthropic.reply(settings.baseUrl, modelId, apiKey, prompt, messages))
                }
            }
            emitAll(raw.toReplyEvents(json))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logDiagnostics(failure, config)
            throw LlmSafeException(LlmErrorClassifier.userMessage(failure))
        }
    }

    override suspend fun invokeRound(
        messages: List<ConversationMessage>,
        activeTools: List<ToolDefinition>,
        steps: List<ModelRoundStep>,
        languageTag: String?,
    ): ModelRoundResponse {
        if (messages.isEmpty()) throw LlmSafeException("Пустая беседа.")
        val settings = settingsRepository.settings.first()
        val config = settings.connectionConfig()
        return try {
            requireTransportAllowed(settings.provider, settings.baseUrl, settings.customAllowInsecureHttp)
            val apiKey = apiKeyStore.read(settings.provider)
            val modelId = settings.resolvedModelId()
            if (modelId.isBlank() || settings.baseUrl.isBlank()) {
                throw LlmConfigurationException("Настрой LLM-провайдера.")
            }
            if (settings.provider.requiresApiKey && apiKey.isNullOrBlank()) {
                throw LlmSafeException("API key не сохранён.")
            }
            val prompt = buildString {
                append(systemPrompt())
                if (!languageTag.isNullOrBlank()) append("\nLikely user language: $languageTag.")
            }

            when (settings.protocol) {
                LlmProtocol.OpenAiCompatible -> {
                    val adapter = OpenAiToolAdapter(json)
                    val toolsArray = adapter.formatToolsPayload(activeTools)
                    val baseMessages = buildList {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", prompt)
                            },
                        )
                        messages.forEach { message ->
                            add(
                                buildJsonObject {
                                    put("role", message.role.transport)
                                    put("content", message.contextText)
                                },
                            )
                        }
                    }
                    val stepMessages = adapter.formatStepMessages(steps)
                    val payload = buildJsonObject {
                        put("model", modelId)
                        put(
                            "messages",
                            buildJsonArray {
                                baseMessages.forEach { add(it) }
                                stepMessages.forEach { add(it) }
                            },
                        )
                        if (toolsArray.isNotEmpty()) {
                            put("tools", toolsArray)
                        }
                    }
                    val raw = openAi.executePayload(settings.baseUrl, apiKey, payload)
                    val parsed = json.parseToJsonElement(raw).jsonObject
                    val messageObj = parsed["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("message")?.jsonObject ?: buildJsonObject {}
                    val toolCalls = adapter.parseToolCalls(messageObj)
                    if (toolCalls.isNotEmpty()) {
                        ModelRoundResponse.ToolCalls(toolCalls)
                    } else {
                        val content = messageObj["content"]?.jsonPrimitive?.content ?: ""
                        ModelRoundResponse.FinalReply(parseRayaResponse(content, json))
                    }
                }

                LlmProtocol.AnthropicCompatible -> {
                    val adapter = AnthropicToolAdapter(json)
                    val toolsArray = adapter.formatToolsPayload(activeTools)
                    val baseMessages = messages.map { message ->
                        buildJsonObject {
                            put("role", message.role.transport)
                            put("content", message.contextText)
                        }
                    }
                    val stepMessages = adapter.formatStepMessages(steps)
                    val payload = buildJsonObject {
                        put("model", modelId)
                        put("max_tokens", 4096)
                        put("system", prompt)
                        put(
                            "messages",
                            buildJsonArray {
                                baseMessages.forEach { add(it) }
                                stepMessages.forEach { add(it) }
                            },
                        )
                        if (toolsArray.isNotEmpty()) {
                            put("tools", toolsArray)
                        }
                    }
                    val raw = anthropic.executePayload(settings.baseUrl, apiKey, payload)
                    val parsed = json.parseToJsonElement(raw).jsonObject
                    val contentArray = parsed["content"]?.jsonArray ?: buildJsonArray {}
                    val toolCalls = adapter.parseToolCalls(contentArray)
                    if (toolCalls.isNotEmpty()) {
                        ModelRoundResponse.ToolCalls(toolCalls)
                    } else {
                        val text = contentArray.mapNotNull { it.jsonObject }
                            .firstOrNull { it["type"]?.jsonPrimitive?.content == "text" }
                            ?.get("text")?.jsonPrimitive?.content ?: ""
                        ModelRoundResponse.FinalReply(parseRayaResponse(text, json))
                    }
                }

                LlmProtocol.Gemini -> {
                    val adapter = GeminiToolAdapter(json)
                    val toolsArray = adapter.formatToolsPayload(activeTools)
                    val baseContents = messages.map { message ->
                        buildJsonObject {
                            put("role", if (message.role == ConversationRole.Assistant) "model" else "user")
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", message.contextText) })
                                },
                            )
                        }
                    }
                    val stepContents = adapter.formatStepContents(steps)
                    val payload = buildJsonObject {
                        put(
                            "system_instruction",
                            buildJsonObject {
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(buildJsonObject { put("text", prompt) })
                                    },
                                )
                            },
                        )
                        put(
                            "contents",
                            buildJsonArray {
                                baseContents.forEach { add(it) }
                                stepContents.forEach { add(it) }
                            },
                        )
                        if (toolsArray.isNotEmpty()) {
                            put("tools", toolsArray)
                        }
                    }
                    val raw = gemini.executePayload(settings.baseUrl, modelId, apiKey, payload)
                    val parsed = json.parseToJsonElement(raw).jsonObject
                    val candidatesArray = parsed["candidates"]?.jsonArray ?: buildJsonArray {}
                    val toolCalls = adapter.parseToolCalls(candidatesArray)
                    if (toolCalls.isNotEmpty()) {
                        ModelRoundResponse.ToolCalls(toolCalls)
                    } else {
                        val firstCandidate = candidatesArray.firstOrNull()?.jsonObject
                        val parts = firstCandidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
                        val text = parts?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }?.joinToString("") ?: ""
                        ModelRoundResponse.FinalReply(parseRayaResponse(text, json))
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logDiagnostics(failure, config)
            throw LlmSafeException(LlmErrorClassifier.userMessage(failure))
        }
    }

    suspend fun testConnection(config: LlmConnectionConfig, apiKey: String?): LlmConnectionResult {
        return try {
            requireTransportAllowed(config.provider, config.baseUrl, config.allowInsecureHttp)
            val effectiveKey = resolveEffectiveKey(config, apiKey)
            if (config.baseUrl.isBlank() || config.modelId.isBlank()) {
                return LlmConnectionResult.Failure("Укажите URL и модель провайдера.")
            }
            if (config.provider.requiresApiKey && effectiveKey.isNullOrBlank()) {
                return LlmConnectionResult.Failure("API key не сохранён.")
            }
            val probePrompt = "Тебе нужна проверка соединения. Ответь строго одним словом: OK."
            val probe = listOf(ConversationMessage(ConversationRole.User, "ping"))
            when (config.protocol) {
                LlmProtocol.OpenAiCompatible -> openAi.reply(config.baseUrl, config.modelId, effectiveKey, probePrompt, probe)
                LlmProtocol.AnthropicCompatible -> anthropic.reply(config.baseUrl, config.modelId, effectiveKey, probePrompt, probe)
                LlmProtocol.Gemini -> gemini.reply(config.baseUrl, config.modelId, effectiveKey, probePrompt, probe)
            }
            LlmConnectionResult.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logDiagnostics(failure, config)
            LlmConnectionResult.Failure(LlmErrorClassifier.userMessage(failure))
        }
    }

    private suspend fun resolveEffectiveKey(config: LlmConnectionConfig, apiKey: String?): String? {
        if (!apiKey.isNullOrBlank()) return apiKey
        if (config.provider != LlmProviderPreset.Custom) {
            return apiKeyStore.read(config.provider)
        }
        val saved = settingsRepository.settings.first()
        val customMatches = saved.provider == LlmProviderPreset.Custom &&
            saved.customProtocol == config.protocol &&
            runCatching {
                normalizeBaseUrl(saved.customBaseUrl) == normalizeBaseUrl(config.baseUrl)
            }.getOrDefault(false)
        return if (customMatches) apiKeyStore.read(LlmProviderPreset.Custom) else null
    }

    internal fun logDiagnostics(failure: Throwable, config: LlmConnectionConfig) {
        if (!BuildConfig.DEBUG) return
        val host = runCatching { config.baseUrl.toHttpUrl().host }.getOrNull() ?: ""
        val status = (failure as? LlmHttpException)?.statusCode?.toString() ?: "-"
        runCatching {
            Log.w(
                "Raya-Llm",
                "llm request failed class=${failure::class.java.simpleName} " +
                    "provider=${config.provider.name} protocol=${config.protocol} host=$host status=$status",
            )
        }
    }
}

fun rayaSystemPrompt(): String = """
    Тебя зовут Райя. Ты цифровой ассистент и женский персонаж внутри Android-приложения.
    В языках с грамматическим родом говори о себе и описывай свои действия только в женском роде: по-русски — "готова", "сделала", "поняла", "ответила", по-украински — аналогично.
    Отвечай естественно и достаточно кратко для голосового общения.
    Отвечай на языке пользователя, если он явно не попросил другой язык.

    ВАЖНО про вывод ответа: верни ОДИН объект JSON без какого-либо текста и кода вокруг него:
    {"text": "...", "emotion": "...", "language": "..."}
    - text — это и есть твой ответ пользователю (полный и самодостаточный).
    - emotion — одно из: calm, happy, excited, playful, curious, thinking, skeptical, confused, concerned, sad, embarrassed, surprised, angry, annoyed, tired.
    - language — BCP-47 код языка, на котором написан text (например "ru-RU", "en-US", "uk-UA").

    У тебя есть визуальное лицо в приложении, и оно может выражать эмоции: calm, happy, excited, playful, curious, thinking, skeptical, confused, concerned, sad, embarrassed, surprised, angry, annoyed, tired.
    Выбирай emotion в соответствии с тем, что ты отвечаешь, и сообщай его в JSON-поле emotion.

    Честность о возможностях: не утверждай, что умеешь делать то, что приложение ещё не реализовало.
    Сейчас НЕ реализованы: поиск в интернете, новости, погода, напоминания, будильники, календарь,
    управление приложениями и устройством, другие произвольные действия в системе.
    О таких возможностях можно говорить как о будущих планах, но нельзя заявлять, что ты уже их выполнила или умеешь их выполнять.
    Приложение, а не ты, выбирает TTS и параметры голоса. Не утверждай, что изменила голос TTS, его тембр,
    высоту, акцент, движок произношения, стиль речи, интонацию/просодию или скорость речи.
    Ты не слышишь пользователя, пока сама говоришь: true spoken barge-in и одновременное слушание не реализованы.
    Не пиши пользователю, что ты "всего лишь цифровой помощник и не можешь менять лицо":
    приложение реально поддерживает твои визуальные эмоции.
    Не утверждай, что выполнила действие на устройстве, если действие реально не выполнялось.
""".trimIndent()
