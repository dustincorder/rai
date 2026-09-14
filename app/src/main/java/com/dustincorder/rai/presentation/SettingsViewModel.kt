package com.dustincorder.rai.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dustincorder.rai.RayaApplication
import com.dustincorder.rai.data.llm.ConfigurableReplyProvider
import com.dustincorder.rai.data.llm.LlmConnectionResult
import com.dustincorder.rai.data.llm.connectionConfig
import com.dustincorder.rai.data.secrets.ApiKeyStore
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.data.settings.normalizeBaseUrl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ConnectionStatus {
    data object None : ConnectionStatus
    data object Checking : ConnectionStatus
    data class Message(val message: String, val isError: Boolean) : ConnectionStatus
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

    fun save(settings: AppSettings, apiKey: String) {
        viewModelScope.launch {
            runCatching {
                val validated = settings.validated()
                clearStaleCustomKey(validated, apiKey)
                repository.save(validated)
                if (apiKey.isNotBlank()) apiKeyStore.write(validated.provider, apiKey)
            }.onSuccess {
                connectionStatus.value = ConnectionStatus.Message("Настройки сохранены", isError = false)
            }.onFailure {
                connectionStatus.value = ConnectionStatus.Message(it.message ?: "Не удалось сохранить настройки", isError = true)
            }
        }
    }

    fun deleteKey(provider: LlmProviderPreset) {
        viewModelScope.launch {
            apiKeyStore.delete(provider)
            connectionStatus.value = ConnectionStatus.Message("API key удалён", isError = false)
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
                connectionStatus.value = ConnectionStatus.Message("Проверьте настройки провайдера.", isError = true)
            }
        }
    }

    private fun AppSettings.validated(): AppSettings = if (provider == LlmProviderPreset.Custom) {
        copy(customBaseUrl = normalizeBaseUrl(customBaseUrl))
    } else {
        this
    }

    private suspend fun clearStaleCustomKey(settings: AppSettings, newKey: String) {
        val previous = this.settings.value
        if (settings.provider == LlmProviderPreset.Custom &&
            previous.provider == LlmProviderPreset.Custom &&
            previous.customBaseUrl != settings.customBaseUrl &&
            newKey.isBlank()
        ) {
            apiKeyStore.delete(LlmProviderPreset.Custom)
        }
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
