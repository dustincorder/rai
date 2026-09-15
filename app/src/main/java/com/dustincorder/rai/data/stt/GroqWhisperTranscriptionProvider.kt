package com.dustincorder.rai.data.stt

import com.dustincorder.rai.domain.AudioUtterance
import com.dustincorder.rai.domain.SpeechTranscriptionProvider
import com.dustincorder.rai.domain.TranscriptionResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
private data class WhisperResponse(
    val text: String = "",
    val language: String? = null,
)

/** Groq Whisper transcription transport. Audio exists only in memory for this request. */
class GroqWhisperTranscriptionProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val endpoint: String = "https://api.groq.com/openai/v1/audio/transcriptions",
    private val apiKey: suspend () -> String? = { null },
) : SpeechTranscriptionProvider {
    override suspend fun transcribe(
        audio: AudioUtterance,
        model: String,
        languageHint: String?,
    ): TranscriptionResult {
        require(model.isNotBlank()) { "STT model is required." }
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw IOException("Groq STT API key is not configured.")
        val wav = pcm16ToWav(audio.pcm16, audio.sampleRateHz, audio.channels)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .apply { if (!languageHint.isNullOrBlank()) addFormDataPart("language", languageHint) }
            .addFormDataPart(
                "file",
                "raya-utterance.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("Authorization", "Bearer $key")
            .build()
        val raw = client.await(request)
        val parsed = json.decodeFromString<WhisperResponse>(raw)
        return TranscriptionResult(parsed.text.trim(), parsed.language?.let(::normalizeLanguage))
    }

    private fun normalizeLanguage(value: String): String =
        value.trim().lowercase().takeIf { it.isNotBlank() }.orEmpty()

    private fun pcm16ToWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val size = pcm.size
        val out = ByteArray(44 + size)
        fun putAscii(offset: Int, value: String) = value.forEachIndexed { index, c -> out[offset + index] = c.code.toByte() }
        fun putInt(offset: Int, value: Int) = repeat(4) { index -> out[offset + index] = (value ushr (index * 8)).toByte() }
        fun putShort(offset: Int, value: Int) = repeat(2) { index -> out[offset + index] = (value ushr (index * 8)).toByte() }
        putAscii(0, "RIFF")
        putInt(4, 36 + size)
        putAscii(8, "WAVEfmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, blockAlign)
        putShort(34, 16)
        putAscii(36, "data")
        putInt(40, size)
        pcm.copyInto(out, 44)
        return out
    }

    private suspend fun OkHttpClient.await(request: Request): String = suspendCancellableCoroutine { continuation ->
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
                        if (continuation.isActive) continuation.resumeWithException(IOException("STT HTTP ${it.code}"))
                    } else if (continuation.isActive) {
                        continuation.resume(body)
                    }
                }
            }
        })
    }
}
