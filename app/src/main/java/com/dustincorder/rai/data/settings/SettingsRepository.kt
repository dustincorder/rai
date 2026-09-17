package com.dustincorder.rai.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.dustincorder.rai.domain.ConversationLanguage
import com.dustincorder.rai.domain.ConversationLanguageProvider
import com.dustincorder.rai.domain.TtsEngine
import com.dustincorder.rai.domain.isValidLanguageTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore("raya_settings")

interface SettingsRepository : ConversationLanguageProvider {
    val settings: Flow<AppSettings>
    suspend fun save(settings: AppSettings)
    suspend fun hasPersistedSettings(): Boolean = false

    /** Last successfully discovered model ids per provider name. */
    val modelCache: Flow<Map<String, List<String>>>
    suspend fun saveModelCache(providerName: String, modelIds: List<String>)
}

private val modelCacheJson = Json { ignoreUnknownKeys = true }

class DataStoreSettingsRepository private constructor(private val dataStore: DataStore<Preferences>) : SettingsRepository {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)
    internal constructor(dataStore: DataStore<Preferences>, forTests: Boolean = true) : this(dataStore)

    override val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        val provider = enumValueOrDefault(preferences[PROVIDER], LlmProviderPreset.OpenAI)
        AppSettings(
            provider = provider,
            customProtocol = enumValueOrDefault(preferences[PROTOCOL], LlmProtocol.OpenAiCompatible),
            customBaseUrl = preferences[BASE_URL].orEmpty(),
            modelId = preferences[MODEL] ?: provider.defaultModel,
            useCustomModel = preferences[USE_CUSTOM_MODEL] ?: false,
            customModelId = preferences[CUSTOM_MODEL_ID].orEmpty(),
            sttModelId = preferences[STT_MODEL] ?: "whisper-large-v3-turbo",
            sttEngine = enumValueOrDefault(preferences[STT_ENGINE], SttEngine.GroqWhisper),
            ttsEngine = enumValueOrDefault(preferences[TTS_ENGINE], TtsEngine.System),
            appearanceMode = enumValueOrDefault(preferences[APPEARANCE_MODE], AppearanceMode.Raya),
            conversationLanguage = decodeLanguage(preferences[LANGUAGE] ?: "auto"),
            customAllowInsecureHttp = preferences[ALLOW_INSECURE_HTTP] ?: false,
        )
    }

    override val modelCache: Flow<Map<String, List<String>>> = dataStore.data.map { preferences ->
        val raw = preferences[MODEL_CACHE].orEmpty()
        if (raw.isBlank()) {
            emptyMap()
        } else {
            runCatching {
                modelCacheJson.decodeFromString(
                    MapSerializer(String.serializer(), ListSerializer(String.serializer())),
                    raw,
                )
            }.getOrDefault(emptyMap())
        }
    }

    override suspend fun saveModelCache(providerName: String, modelIds: List<String>) {
        dataStore.edit { preferences ->
            val current = runCatching {
                modelCacheJson.decodeFromString(
                    MapSerializer(String.serializer(), ListSerializer(String.serializer())),
                    preferences[MODEL_CACHE].orEmpty().ifBlank { "{}" },
                )
            }.getOrDefault(emptyMap())
            preferences[MODEL_CACHE] = modelCacheJson.encodeToString(
                MapSerializer(String.serializer(), ListSerializer(String.serializer())),
                current + (providerName to modelIds.distinct().take(500)),
            )
        }
    }

    override suspend fun save(settings: AppSettings) {
        dataStore.edit {
            it[PROVIDER] = settings.provider.name
            it[PROTOCOL] = settings.customProtocol.name
            it[BASE_URL] = settings.customBaseUrl
            it[MODEL] = settings.modelId
            it[USE_CUSTOM_MODEL] = settings.useCustomModel
            it[CUSTOM_MODEL_ID] = settings.customModelId
            it[STT_MODEL] = settings.sttModelId
            it[STT_ENGINE] = settings.sttEngine.name
            it[TTS_ENGINE] = settings.ttsEngine.name
            it[APPEARANCE_MODE] = settings.appearanceMode.name
            it[LANGUAGE] = encodeLanguage(settings.conversationLanguage)
            it[ALLOW_INSECURE_HTTP] = settings.customAllowInsecureHttp
        }
    }

    override suspend fun hasPersistedSettings(): Boolean = dataStore.data.first().asMap().isNotEmpty()

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
        val USE_CUSTOM_MODEL = booleanPreferencesKey("use_custom_model")
        val CUSTOM_MODEL_ID = stringPreferencesKey("custom_model_id")
        val STT_MODEL = stringPreferencesKey("stt_model_id")
        val STT_ENGINE = stringPreferencesKey("stt_engine")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        val MODEL_CACHE = stringPreferencesKey("model_cache_json")
        val LANGUAGE = stringPreferencesKey("conversation_language")
        val ALLOW_INSECURE_HTTP = booleanPreferencesKey("custom_allow_insecure_http")
    }
}
