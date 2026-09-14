package com.dustincorder.rai.data.llm

import android.util.Log
import com.dustincorder.rai.BuildConfig
import com.dustincorder.rai.data.secrets.ApiKeyStore
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.data.settings.normalizeBaseUrl
import com.dustincorder.rai.domain.ReplyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl

class ConfigurableReplyProvider(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val openAi: OpenAiCompatibleReplyProvider,
    private val anthropic: AnthropicCompatibleReplyProvider,
    private val systemPrompt: () -> String,
) : ReplyProvider {
    override suspend fun reply(input: String, languageTag: String?): String {
        val settings = settingsRepository.settings.first()
        val config = settings.connectionConfig()
        return try {
            requireTransportAllowed(settings.provider, settings.baseUrl, settings.customAllowInsecureHttp)
            val apiKey = apiKeyStore.read(settings.provider)
            if (settings.modelId.isBlank() || settings.baseUrl.isBlank()) {
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
                LlmProtocol.OpenAiCompatible -> openAi.reply(settings.baseUrl, settings.modelId, apiKey, prompt, input)
                LlmProtocol.AnthropicCompatible -> anthropic.reply(settings.baseUrl, settings.modelId, apiKey, prompt, input)
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
            when (config.protocol) {
                LlmProtocol.OpenAiCompatible -> openAi.reply(config.baseUrl, config.modelId, effectiveKey, probePrompt, "ping")
                LlmProtocol.AnthropicCompatible -> anthropic.reply(config.baseUrl, config.modelId, effectiveKey, probePrompt, "ping")
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
    Тебя зовут Райя. Ты голосовой ассистент.
    Отвечай естественно и достаточно кратко для голосового общения.
    Отвечай на языке пользователя, если он явно не попросил другой язык.
    Не утверждай, что выполнила действие на устройстве, если действие реально не выполнялось.
""".trimIndent()
