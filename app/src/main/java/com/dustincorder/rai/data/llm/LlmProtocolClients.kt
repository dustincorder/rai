package com.dustincorder.rai.data.llm

import com.dustincorder.rai.data.settings.resolveEndpointUrl
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAiCompatibleReplyProvider(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun reply(baseUrl: String, model: String, apiKey: String?, systemPrompt: String, input: String): String {
        val body = json.encodeToString(
            OpenAiRequest(model, listOf(OpenAiMessage("system", systemPrompt), OpenAiMessage("user", input))),
        )
        val request = Request.Builder()
            .url(resolveEndpointUrl(baseUrl, listOf("chat", "completions")))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        val response = client.await(request, json)
        return json.decodeFromString<OpenAiResponse>(response).choices.firstOrNull()?.message?.content
            ?.takeIf { it.isNotBlank() }
            ?: error("Провайдер вернул пустой ответ.")
    }
}

class AnthropicCompatibleReplyProvider(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun reply(baseUrl: String, model: String, apiKey: String?, systemPrompt: String, input: String): String {
        val body = json.encodeToString(
            AnthropicRequest(model = model, maxTokens = 512, system = systemPrompt, messages = listOf(AnthropicMessage("user", input))),
        )
        val request = Request.Builder()
            .url(resolveEndpointUrl(baseUrl, listOf("messages")))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("anthropic-version", "2023-06-01")
            .apply { if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey) }
            .build()
        val response = client.await(request, json)
        return json.decodeFromString<AnthropicResponse>(response).content
            .firstOrNull { it.type == "text" }?.text?.takeIf { it.isNotBlank() }
            ?: error("Провайдер вернул пустой ответ.")
    }
}

private suspend fun OkHttpClient.await(request: Request, json: Json): String = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val detail = extractProviderErrorMessage(body, json)
                    if (continuation.isActive) continuation.resumeWithException(LlmHttpException(it.code, detail))
                } else if (continuation.isActive) {
                    continuation.resume(body)
                }
            }
        }
    })
}

private fun extractProviderErrorMessage(body: String, json: Json): String? {
    if (body.isBlank()) return null
    val message = runCatching {
        json.decodeFromString<ProviderErrorEnvelope>(body).error?.message
    }.getOrNull()
    return LlmErrorClassifier.sanitizeProviderMessage(message ?: return null)
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
