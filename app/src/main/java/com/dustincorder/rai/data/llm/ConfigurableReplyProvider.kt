package com.dustincorder.rai.data.llm

import com.dustincorder.rai.data.secrets.ApiKeyStore
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.domain.ReplyProvider
import kotlinx.coroutines.flow.first

class ConfigurableReplyProvider(
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val openAi: OpenAiCompatibleReplyProvider,
    private val anthropic: AnthropicCompatibleReplyProvider,
    private val systemPrompt: () -> String,
) : ReplyProvider {
    override suspend fun reply(input: String, languageTag: String?): String {
        val settings = settingsRepository.settings.first()
        val apiKey = apiKeyStore.read(settings.provider)
        if (settings.modelId.isBlank() || settings.baseUrl.isBlank() ||
            (settings.provider.requiresApiKey && apiKey.isNullOrBlank())
        ) {
            throw LlmConfigurationException("Настрой LLM-провайдера.")
        }
        val prompt = buildString {
            append(systemPrompt())
            if (!languageTag.isNullOrBlank()) append("\nLikely user language: $languageTag.")
        }
        return when (settings.protocol) {
            LlmProtocol.OpenAiCompatible -> openAi.reply(settings.baseUrl, settings.modelId, apiKey, prompt, input)
            LlmProtocol.AnthropicCompatible -> anthropic.reply(settings.baseUrl, settings.modelId, apiKey, prompt, input)
        }
    }

    suspend fun testConnection(): String = reply("Ответь одним словом: OK", null)
}

fun rayaSystemPrompt(): String = """
    Тебя зовут Райя. Ты голосовой ассистент.
    Отвечай естественно и достаточно кратко для голосового общения.
    Отвечай на языке пользователя, если он явно не попросил другой язык.
    Не утверждай, что выполнила действие на устройстве, если действие реально не выполнялось.
""".trimIndent()
