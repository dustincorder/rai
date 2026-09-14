package com.dustincorder.rai.data.llm

import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset

sealed interface LlmConnectionResult {
    data object Success : LlmConnectionResult
    data class Failure(val message: String) : LlmConnectionResult
}

data class LlmConnectionConfig(
    val provider: LlmProviderPreset,
    val protocol: LlmProtocol,
    val baseUrl: String,
    val modelId: String,
)

fun AppSettings.connectionConfig(): LlmConnectionConfig = LlmConnectionConfig(
    provider = provider,
    protocol = protocol,
    baseUrl = baseUrl,
    modelId = modelId,
)