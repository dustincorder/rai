package com.dustincorder.rai.data.llm

import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.normalizeBaseUrl
import com.dustincorder.rai.data.settings.resolveEndpointUrl
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
private data class OpenAiModelsResponse(val data: List<OpenAiModelEntry> = emptyList())

@Serializable
private data class OpenAiModelEntry(val id: String = "")

@Serializable
private data class AnthropicModelsResponse(val data: List<AnthropicModelEntry> = emptyList())

@Serializable
private data class AnthropicModelEntry(val id: String = "", val displayName: String? = null)

/** Lists provider models for dropdowns. Never throws API keys into messages. */
interface LlmModelDiscovery {
    suspend fun listModels(
        provider: LlmProviderPreset,
        protocol: LlmProtocol,
        baseUrl: String,
        apiKey: String?,
        allowInsecureHttp: Boolean,
    ): List<DiscoveredModel>
}

class DefaultLlmModelDiscovery(
    private val client: OkHttpClient,
    private val json: Json,
    private val gemini: GeminiReplyProvider,
) : LlmModelDiscovery {
    override suspend fun listModels(
        provider: LlmProviderPreset,
        protocol: LlmProtocol,
        baseUrl: String,
        apiKey: String?,
        allowInsecureHttp: Boolean,
    ): List<DiscoveredModel> {
        requireTransportAllowed(provider, baseUrl, allowInsecureHttp)
        return when (protocol) {
            LlmProtocol.OpenAiCompatible -> listOpenAiModels(provider, baseUrl, apiKey)
            LlmProtocol.AnthropicCompatible -> listAnthropicModels(baseUrl, apiKey)
            LlmProtocol.Gemini -> gemini.listModels(baseUrl, apiKey)
        }
    }

    private suspend fun listOpenAiModels(
        provider: LlmProviderPreset,
        baseUrl: String,
        apiKey: String?,
    ): List<DiscoveredModel> {
        val url = resolveEndpointUrl(baseUrl, listOf("models")).toString()
        val body = client.getJson(url, bearer = apiKey, extraHeaders = emptyMap())
        return json.decodeFromString<OpenAiModelsResponse>(body).data.mapNotNull { entry ->
            val id = entry.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DiscoveredModel(id = id, capabilities = classifyOpenAiStyleModel(id))
        }.ifEmpty { throw LlmSafeException("Провайдер вернул пустой список моделей.") }
    }

    private suspend fun listAnthropicModels(baseUrl: String, apiKey: String?): List<DiscoveredModel> {
        val normalized = normalizeBaseUrl(baseUrl).trimEnd('/')
        val url = if (normalized.endsWith("/messages")) {
            normalized.substringBeforeLast("/messages") + "/models?limit=100"
        } else {
            "$normalized/models?limit=100"
        }
        val body = client.getJson(
            url,
            bearer = null,
            extraHeaders = buildMap {
                if (!apiKey.isNullOrBlank()) put("x-api-key", apiKey)
                put("anthropic-version", "2023-06-01")
            },
        )
        return json.decodeFromString<AnthropicModelsResponse>(body).data.mapNotNull { entry ->
            val id = entry.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DiscoveredModel(id = id, label = entry.displayName, capabilities = classifyOpenAiStyleModel(id))
        }.ifEmpty { throw LlmSafeException("Провайдер вернул пустой список моделей.") }
    }

    private suspend fun OkHttpClient.getJson(
        url: String,
        bearer: String?,
        extraHeaders: Map<String, String>,
    ): String = suspendCancellableCoroutine { continuation ->
        val call = newCall(
            Request.Builder()
                .url(url)
                .get()
                .apply {
                    if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
                    extraHeaders.forEach { (name, value) -> header(name, value) }
                }
                .build(),
        )
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(LlmHttpException(it.code, null))
                        }
                    } else if (continuation.isActive) {
                        continuation.resume(body)
                    }
                }
            }
        })
    }
}
