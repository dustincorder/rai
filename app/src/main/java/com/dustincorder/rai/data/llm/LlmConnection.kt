package com.dustincorder.rai.data.llm

import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import okhttp3.HttpUrl.Companion.toHttpUrl

sealed interface LlmConnectionResult {
    data object Success : LlmConnectionResult
    data class Failure(val message: String) : LlmConnectionResult
}

data class LlmConnectionConfig(
    val provider: LlmProviderPreset,
    val protocol: LlmProtocol,
    val baseUrl: String,
    val modelId: String,
    val allowInsecureHttp: Boolean = false,
)

fun AppSettings.connectionConfig(): LlmConnectionConfig = LlmConnectionConfig(
    provider = provider,
    protocol = protocol,
    baseUrl = baseUrl,
    modelId = resolvedModelId(),
    allowInsecureHttp = customAllowInsecureHttp,
)

fun requireTransportAllowed(provider: LlmProviderPreset, baseUrl: String, allowInsecureHttp: Boolean) {
    val scheme = runCatching { baseUrl.toHttpUrl().scheme }.getOrNull() ?: return
    if (scheme == "https") return
    if (provider != LlmProviderPreset.Custom) {
        throw LlmTransportException("HTTP не разрешён для этого провайдера.")
    }
    if (!allowInsecureHttp) {
        throw LlmTransportException("HTTP для Custom provider отключён. Разрешите его в настройках.")
    }
}