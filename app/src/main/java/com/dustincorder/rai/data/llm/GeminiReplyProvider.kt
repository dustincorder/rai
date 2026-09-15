package com.dustincorder.rai.data.llm

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

@Serializable
private data class GeminiPart(val text: String)

@Serializable
private data class GeminiContent(val role: String? = null, val parts: List<GeminiPart>)

@Serializable
private data class GeminiSystemInstruction(val parts: List<GeminiPart>)

@Serializable
private data class GeminiGenerationConfig(
    @SerialName("maxOutputTokens") val maxOutputTokens: Int = 512,
)

@Serializable
private data class GeminiGenerateRequest(
    @SerialName("system_instruction") val systemInstruction: GeminiSystemInstruction,
    val contents: List<GeminiContent>,
    @SerialName("generationConfig") val generationConfig: GeminiGenerationConfig = GeminiGenerationConfig(),
)

@Serializable
private data class GeminiGenerateResponse(val candidates: List<GeminiCandidate> = emptyList())

@Serializable
private data class GeminiCandidate(val content: GeminiContent? = null)

@Serializable
private data class GeminiModelsResponse(val models: List<GeminiModelEntry> = emptyList())

@Serializable
private data class GeminiModelEntry(
    val name: String = "",
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("supportedGenerationMethods") val supportedGenerationMethods: List<String> = emptyList(),
)

/**
 * First-class Gemini AI Studio transport (no vendor SDK).
 * Uses generateContent for replies and the models endpoint for discovery.
 */
class GeminiReplyProvider(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun reply(
        baseUrl: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        messages: List<ConversationMessage>,
    ): String {
        val body = json.encodeToString(
            GeminiGenerateRequest(
                systemInstruction = GeminiSystemInstruction(listOf(GeminiPart(systemPrompt))),
                contents = messages.map { message ->
                    GeminiContent(
                        role = if (message.role == ConversationRole.Assistant) "model" else "user",
                        parts = listOf(GeminiPart(message.contextText)),
                    )
                },
            ),
        )
        val url = "${baseUrl.trimEnd('/')}/models/$model:generateContent"
        val response = client.postJson(url, body, apiKey)
        val text = json.decodeFromString<GeminiGenerateResponse>(response)
            .candidates.firstOrNull()?.content?.parts
            ?.joinToString("") { it.text }
            ?.takeIf { it.isNotBlank() }
        return text ?: error("Провайдер вернул пустой ответ.")
    }

    suspend fun listModels(baseUrl: String, apiKey: String?): List<DiscoveredModel> {
        val url = "${baseUrl.trimEnd('/')}/models?pageSize=100"
        val response = client.getJson(url, apiKey)
        return json.decodeFromString<GeminiModelsResponse>(response).models.mapNotNull { entry ->
            val id = entry.name.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DiscoveredModel(
                id = id,
                label = entry.displayName?.takeIf { it.isNotBlank() },
                capabilities = classifyGeminiModel(id, entry.supportedGenerationMethods),
            )
        }
    }

    private suspend fun OkHttpClient.postJson(url: String, body: String, apiKey: String?): String =
        suspendCancellableCoroutine { continuation ->
            val call = newCall(
                Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .apply { if (!apiKey.isNullOrBlank()) header("x-goog-api-key", apiKey) }
                    .build(),
            )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(geminiCallback(this, continuation))
        }

    private suspend fun OkHttpClient.getJson(url: String, apiKey: String?): String =
        suspendCancellableCoroutine { continuation ->
            val call = newCall(
                Request.Builder()
                    .url(url)
                    .get()
                    .apply { if (!apiKey.isNullOrBlank()) header("x-goog-api-key", apiKey) }
                    .build(),
            )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(geminiCallback(this, continuation))
        }

    private fun geminiCallback(
        client: OkHttpClient,
        continuation: kotlinx.coroutines.CancellableContinuation<String>,
    ): Callback = object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val detail = runCatching {
                        client.jsonLenientDecode(body)
                    }.getOrNull()
                    if (continuation.isActive) continuation.resumeWithException(LlmHttpException(it.code, detail))
                } else if (continuation.isActive) {
                    continuation.resume(body)
                }
            }
        }
    }

    private fun OkHttpClient.jsonLenientDecode(body: String): String? {
        if (body.isBlank()) return null
        val message = runCatching {
            json.decodeFromString<ProviderErrorEnvelope>(body).error?.message
        }.getOrNull()
        return LlmErrorClassifier.sanitizeProviderMessage(message ?: return null)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
