package com.dustincorder.rai.data.llm

import com.dustincorder.rai.data.settings.normalizeBaseUrl
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
            .url(endpoint(baseUrl, "chat", "completions"))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        val response = client.await(request)
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
            .url(endpoint(baseUrl, "messages"))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("anthropic-version", "2023-06-01")
            .apply { if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey) }
            .build()
        val response = client.await(request)
        return json.decodeFromString<AnthropicResponse>(response).content
            .firstOrNull { it.type == "text" }?.text?.takeIf { it.isNotBlank() }
            ?: error("Провайдер вернул пустой ответ.")
    }
}

private suspend fun OkHttpClient.await(request: Request): String = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(IOException("Не удалось связаться с LLM-провайдером.", error))
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    if (continuation.isActive) continuation.resumeWithException(
                        LlmHttpException(it.code, "LLM-провайдер вернул ошибку HTTP ${it.code}."),
                    )
                } else if (continuation.isActive) {
                    continuation.resume(body)
                }
            }
        }
    })
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private fun endpoint(baseUrl: String, vararg segments: String): HttpUrl {
    val builder = normalizeBaseUrl(baseUrl).toHttpUrl().newBuilder()
    segments.forEach(builder::addPathSegment)
    return builder.build()
}
