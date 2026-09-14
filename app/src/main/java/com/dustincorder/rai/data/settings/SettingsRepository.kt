package com.dustincorder.rai.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dustincorder.rai.domain.ConversationLanguage
import com.dustincorder.rai.domain.ConversationLanguageProvider
import com.dustincorder.rai.domain.isValidLanguageTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("raya_settings")

interface SettingsRepository : ConversationLanguageProvider {
    val settings: Flow<AppSettings>
    suspend fun save(settings: AppSettings)
}

class DataStoreSettingsRepository(context: Context) : SettingsRepository {
    private val dataStore = context.applicationContext.settingsDataStore

    override val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        val provider = enumValueOrDefault(preferences[PROVIDER], LlmProviderPreset.OpenAI)
        AppSettings(
            provider = provider,
            customProtocol = enumValueOrDefault(preferences[PROTOCOL], LlmProtocol.OpenAiCompatible),
            customBaseUrl = preferences[BASE_URL].orEmpty(),
            modelId = preferences[MODEL] ?: provider.defaultModel,
            conversationLanguage = decodeLanguage(preferences[LANGUAGE] ?: "auto"),
            customAllowInsecureHttp = preferences[ALLOW_INSECURE_HTTP] ?: false,
        )
    }

    override suspend fun save(settings: AppSettings) {
        dataStore.edit {
            it[PROVIDER] = settings.provider.name
            it[PROTOCOL] = settings.customProtocol.name
            it[BASE_URL] = settings.customBaseUrl
            it[MODEL] = settings.modelId
            it[LANGUAGE] = encodeLanguage(settings.conversationLanguage)
            it[ALLOW_INSECURE_HTTP] = settings.customAllowInsecureHttp
        }
    }

    override suspend fun currentLanguage(): ConversationLanguage = settings.first().conversationLanguage

    private fun encodeLanguage(language: ConversationLanguage): String = when (language) {
        ConversationLanguage.Auto -> "auto"
        ConversationLanguage.System -> "system"
        is ConversationLanguage.Explicit -> "explicit:${language.languageTag}"
    }

    private fun decodeLanguage(value: String): ConversationLanguage = when {
        value == "system" -> ConversationLanguage.System
        value.startsWith("explicit:") -> value.substringAfter(':').let {
            if (it.isValidLanguageTag()) ConversationLanguage.Explicit(it) else ConversationLanguage.Auto
        }
        else -> ConversationLanguage.Auto
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val PROTOCOL = stringPreferencesKey("protocol")
        val BASE_URL = stringPreferencesKey("custom_base_url")
        val MODEL = stringPreferencesKey("model")
        val LANGUAGE = stringPreferencesKey("conversation_language")
        val ALLOW_INSECURE_HTTP = booleanPreferencesKey("custom_allow_insecure_http")
    }
}
