package com.dustincorder.rai.speech

import android.content.Context
import com.dustincorder.rai.domain.TtsModelPack
import com.dustincorder.rai.domain.TtsModelPackStore
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** App-private, explicit-install model store. No automatic multi-hundred-MB downloads. */
class AndroidTtsModelPackStore(context: Context) : TtsModelPackStore {
    private val root = File(context.applicationContext.filesDir, "tts-model-packs")

    override suspend fun installed(languageTag: String): TtsModelPack? {
        val metadata = File(root, "$languageTag/metadata.properties")
        if (!metadata.exists()) return null
        val values = metadata.readLines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        return TtsModelPack(
            id = values["id"] ?: return null,
            version = values["version"] ?: return null,
            languageTags = values["languages"].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
            sha256 = values["sha256"] ?: return null,
            byteSize = values["bytes"]?.toLongOrNull() ?: return null,
            rootPath = File(root, values["id"] ?: return null).absolutePath,
            modelPath = values["modelPath"],
            tokensPath = values["tokensPath"],
            dataDir = values["dataDir"],
        )
    }

    override suspend fun install(pack: TtsModelPack, source: InputStream) {
        val target = File(root, pack.id)
        val temp = File(root, ".${pack.id}.tmp")
        root.mkdirs()
        temp.outputStream().use { output -> source.copyTo(output) }
        require(temp.length() == pack.byteSize) { "TTS model size mismatch." }
        require(sha256(temp) == pack.sha256.lowercase()) { "TTS model checksum mismatch." }
        target.deleteRecursively()
        require(temp.renameTo(target)) { "TTS model install failed." }
        File(target, "metadata.properties").writeText(
            listOf(
                "id=${pack.id}",
                "version=${pack.version}",
                "languages=${pack.languageTags.joinToString(",")}",
                "sha256=${pack.sha256}",
                "bytes=${pack.byteSize}",
                "modelPath=${pack.modelPath.orEmpty()}",
                "tokensPath=${pack.tokensPath.orEmpty()}",
                "dataDir=${pack.dataDir.orEmpty()}",
            ).joinToString("\n"),
        )
    }

    override suspend fun delete(packId: String) {
        File(root, packId).deleteRecursively()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
