package com.dustincorder.rai.speech

import android.content.Context
import com.dustincorder.rai.domain.TtsModelPack
import com.dustincorder.rai.domain.TtsModelPackStore
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** App-private, explicit-install model store. No automatic multi-hundred-MB downloads. */
class AndroidTtsModelPackStore(context: Context) : TtsModelPackStore {
    private val root = File(context.applicationContext.filesDir, "tts-model-packs")

    override suspend fun installed(languageTag: String): TtsModelPack? {
        val exact = findInstalled().firstOrNull { it.languageTags.any { tag -> tag.equals(languageTag, true) } }
        return exact ?: findInstalled().firstOrNull { pack ->
            pack.languageTags.any { tag -> tag.substringBefore('-').equals(languageTag.substringBefore('-'), true) }
        }
    }

    override suspend fun install(pack: TtsModelPack, source: InputStream) {
        val target = File(root, pack.id)
        val tempArchive = File(root, ".${pack.id}.zip.tmp")
        val tempDir = File(root, ".${pack.id}.dir.tmp")
        val backup = File(root, ".${pack.id}.backup")
        root.mkdirs()
        tempArchive.deleteRecursively()
        tempDir.deleteRecursively()
        backup.deleteRecursively()
        try {
            tempArchive.outputStream().use { output -> source.copyTo(output) }
            require(tempArchive.length() == pack.byteSize) { "TTS model archive size mismatch." }
            require(sha256(tempArchive) == pack.sha256.lowercase()) { "TTS model archive checksum mismatch." }
            extractSafely(tempArchive, tempDir)
            require(pack.modelPath != null && File(tempDir, pack.modelPath).isFile) { "TTS model file is missing." }
            require(pack.tokensPath != null && File(tempDir, pack.tokensPath).isFile) { "TTS tokens file is missing." }
            if (!pack.dataDir.isNullOrBlank()) require(File(tempDir, pack.dataDir).isDirectory) { "TTS data directory is missing." }
            File(tempDir, "metadata.properties").writeText(
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
            if (target.exists()) require(target.renameTo(backup)) { "TTS pack backup failed." }
            require(tempDir.renameTo(target)) { "TTS pack install failed." }
            backup.deleteRecursively()
        } catch (failure: Throwable) {
            tempArchive.deleteRecursively()
            tempDir.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw failure
        } finally {
            tempArchive.deleteRecursively()
            tempDir.deleteRecursively()
            backup.deleteRecursively()
        }
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

    private fun findInstalled(): List<TtsModelPack> = root.listFiles()
        .orEmpty()
        .filter { it.isDirectory && !it.name.startsWith('.') }
        .mapNotNull { dir ->
            val values = File(dir, "metadata.properties").takeIf { it.isFile }
                ?.readLines()
                ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                ?.toMap() ?: return@mapNotNull null
            TtsModelPack(
                id = values["id"] ?: return@mapNotNull null,
                version = values["version"] ?: return@mapNotNull null,
                languageTags = values["languages"].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                sha256 = values["sha256"] ?: return@mapNotNull null,
                byteSize = values["bytes"]?.toLongOrNull() ?: return@mapNotNull null,
                rootPath = dir.absolutePath,
                modelPath = values["modelPath"],
                tokensPath = values["tokensPath"],
                dataDir = values["dataDir"],
            )
        }

    private fun extractSafely(archive: File, destination: File) {
        destination.mkdirs()
        val rootPath = destination.canonicalFile.toPath()
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(destination, entry.name).canonicalFile
                require(target.toPath().startsWith(rootPath)) { "TTS archive contains unsafe path." }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }
}
