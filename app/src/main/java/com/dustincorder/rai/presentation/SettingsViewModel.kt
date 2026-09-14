package com.dustincorder.rai.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dustincorder.rai.RayaApplication
import com.dustincorder.rai.data.llm.ConfigurableReplyProvider
import com.dustincorder.rai.data.llm.LlmConnectionResult
import com.dustincorder.rai.data.llm.connectionConfig
import com.dustincorder.rai.data.secrets.ApiKeyStorageException
import com.dustincorder.rai.data.secrets.ApiKeyStore
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.data.settings.normalizeBaseUrl
import com.dustincorder.rai.data.llm.requireTransportAllowed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ConnectionStatus {
    data object None : ConnectionStatus
    data object Checking : ConnectionStatus
    data class Message(val message: String, val isError: Boolean) : ConnectionStatus
}

sealed interface ApiKeyStatus {
    data object Unknown : ApiKeyStatus
    data object Configured : ApiKeyStatus
    data object Missing : ApiKeyStatus
    data object Unreadable : ApiKeyStatus
}

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val replyProvider: ConfigurableReplyProvider,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )
    val connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.None)
    val apiKeyStatus = MutableStateFlow<ApiKeyStatus>(ApiKeyStatus.Unknown)

    init {
        viewModelScope.launch {
            repository.settings.collect { refreshApiKeyStatus(it.provider) }
        }
    }

    fun refreshApiKeyStatus(provider: LlmProviderPreset) {
        viewModelScope.launch {
            apiKeyStatus.value = keyStatusOf(provider)
        }
    }

    fun save(settings: AppSettings, apiKey: String) {
        viewModelScope.launch {
            runCatching {
                val validated = settings.validated()
                if (validated.provider == LlmProviderPreset.Custom) {
                    val previous = repository.settings.first()
                    if (customContextChanged(previous, validated)) {
                        apiKeyStore.delete(LlmProviderPreset.Custom)
                    }
                }
                repository.save(validated)
                if (apiKey.isNotBlank()) apiKeyStore.write(validated.provider, apiKey)
            }.onSuccess {
                refreshApiKeyStatus(settings.provider)
                connectionStatus.value = ConnectionStatus.Message("Настройки сохранены", isError = false)
            }.onFailure {
                connectionStatus.value = ConnectionStatus.Message(it.message ?: "Не удалось сохранить настройки", isError = true)
            }
        }
    }

    fun deleteKey(provider: LlmProviderPreset) {
        viewModelScope.launch {
            runCatching { apiKeyStore.delete(provider) }
                .onSuccess {
                    refreshApiKeyStatus(provider)
                    connectionStatus.value = ConnectionStatus.Message("API key удалён", isError = false)
                }
                .onFailure {
                    connectionStatus.value = ConnectionStatus.Message(it.message ?: "Не удалось удалить API key.", isError = true)
                }
        }
    }

    fun testConnection(settings: AppSettings, apiKey: String) {
        viewModelScope.launch {
            connectionStatus.value = ConnectionStatus.Checking
            val config = runCatching { settings.validated().connectionConfig() }
            config.onSuccess { cfg ->
                when (val result = replyProvider.testConnection(cfg, apiKey)) {
                    LlmConnectionResult.Success -> connectionStatus.value = ConnectionStatus.Message("Подключение работает", isError = false)
                    is LlmConnectionResult.Failure -> connectionStatus.value = ConnectionStatus.Message(result.message, isError = true)
                }
            }.onFailure {
                connectionStatus.value = ConnectionStatus.Message(it.message ?: "Проверьте настройки провайдера.", isError = true)
            }
        }
    }

    private suspend fun keyStatusOf(provider: LlmProviderPreset): ApiKeyStatus = try {
        if (apiKeyStore.isConfigured(provider)) ApiKeyStatus.Configured else ApiKeyStatus.Missing
    } catch (_: ApiKeyStorageException) {
        ApiKeyStatus.Unreadable
    }

    private fun AppSettings.validated(): AppSettings {
        val normalized = if (provider == LlmProviderPreset.Custom) {
            copy(customBaseUrl = normalizeBaseUrl(customBaseUrl))
        } else {
            this
        }
        requireTransportAllowed(normalized.provider, normalized.baseUrl, normalized.customAllowInsecureHttp)
        return normalized
    }

    private suspend fun customContextChanged(previous: AppSettings, next: AppSettings): Boolean {
        val endpointChanged = runCatching {
            normalizeBaseUrl(previous.customBaseUrl) != normalizeBaseUrl(next.customBaseUrl)
        }.getOrDefault(previous.customBaseUrl != next.customBaseUrl)
        val protocolChanged = previous.customProtocol != next.customProtocol
        return endpointChanged || protocolChanged
    }
}

class SettingsViewModelFactory(private val application: RayaApplication) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
        return SettingsViewModel(
            application.settingsRepository,
            application.apiKeyStore,
            application.replyProvider,
        ) as T
    }
}
