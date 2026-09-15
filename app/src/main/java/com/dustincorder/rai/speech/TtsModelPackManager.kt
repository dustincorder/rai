package com.dustincorder.rai.speech

import com.dustincorder.rai.domain.TtsModelPack
import com.dustincorder.rai.domain.TtsModelPackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class TtsModelPackSource(
    val pack: TtsModelPack,
    val downloadUrl: String,
)

/** Explicit user-triggered downloader. No pack is downloaded during app startup. */
class TtsModelPackManager(
    private val client: OkHttpClient,
    private val store: TtsModelPackStore,
) {
    suspend fun install(source: TtsModelPackSource) = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(source.downloadUrl).get().build()).execute()
        response.use {
            check(it.isSuccessful) { "TTS model download failed: HTTP ${it.code}" }
            store.install(source.pack, it.body?.byteStream() ?: error("TTS model body is empty."))
        }
    }

    suspend fun delete(packId: String) = store.delete(packId)
    suspend fun installed(languageTag: String): TtsModelPack? = store.installed(languageTag)
}
