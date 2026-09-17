package com.dustincorder.rai.data.chat

import com.dustincorder.rai.domain.ChatSession
import com.dustincorder.rai.domain.ChatSessionRepository
import com.dustincorder.rai.domain.ChatTitleSource
import com.dustincorder.rai.domain.ChatTranscript
import com.dustincorder.rai.domain.ConversationMessage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Serializable
private data class ChatIndex(val sessions: List<ChatSession> = emptyList())

class FileChatSessionRepository(
    private val root: File,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatSessionRepository {
    private val indexFile = File(root, "chat-index.json")
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    private var initialized = false
    private val initializationMutex = Mutex()
    private val mutationMutex = Mutex()
    override val sessions: Flow<List<ChatSession>> = flow {
        listSessions()
        emitAll(_sessions)
    }

    override suspend fun listSessions(): List<ChatSession> = onIo { _sessions.value }

    override suspend fun createSession(): ChatSession = onIo {
        mutationMutex.withLock {
            val timestamp = now()
            val session = ChatSession(idFactory(), createdAt = timestamp, updatedAt = timestamp)
            writeTranscript(ChatTranscript(session.id, emptyList()))
            publish(_sessions.value + session)
            session
        }
    }

    override suspend fun loadSession(id: String): ChatTranscript? = onIo {
        val file = File(root, "$id.json")
        if (!file.isFile) return@onIo null
        runCatching { json.decodeFromString<ChatTranscript>(file.readText()) }
            .getOrNull()?.takeIf { it.sessionId == id }
    }

    override suspend fun saveMessages(id: String, messages: List<ConversationMessage>) = onIo {
        mutationMutex.withLock {
            if (_sessions.value.none { it.id == id }) return@withLock
            writeTranscript(ChatTranscript(id, messages))
            val timestamp = now()
            publish(_sessions.value.map { if (it.id == id) it.copy(updatedAt = timestamp) else it })
        }
    }

    override suspend fun updateTitle(id: String, title: String, source: ChatTitleSource) = onIo {
        mutationMutex.withLock {
            val clean = title.replace(Regex("\\s+"), " ").trim().trim('"', '\'').take(60)
            if (clean.isBlank()) return@withLock
            publish(_sessions.value.map { session ->
                if (session.id != id ||
                    (source != ChatTitleSource.Manual && session.titleSource != ChatTitleSource.Default)
                ) session else session.copy(title = clean, titleSource = source)
            })
        }
    }

    override suspend fun markTitleGenerationAttempted(id: String) = onIo {
        mutationMutex.withLock {
            publish(_sessions.value.map { if (it.id == id) it.copy(titleGenerationAttempted = true) else it })
        }
    }

    override suspend fun deleteSession(id: String) {
        onIo {
            mutationMutex.withLock {
                publish(_sessions.value.filterNot { it.id == id })
                File(root, "$id.json").delete()
            }
        }
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(ioDispatcher) {
        initializationMutex.withLock {
            if (!initialized) {
                _sessions.value = readIndex()
                initialized = true
            }
        }
        block()
    }

    private fun publish(sessions: List<ChatSession>) {
        val sorted = sessions.sortedByDescending { it.updatedAt }
        root.mkdirs()
        val temp = File(root, ".chat-index.${UUID.randomUUID()}.tmp")
        temp.writeText(json.encodeToString(ChatIndex(sorted)))
        atomicReplace(temp, indexFile)
        _sessions.value = sorted
    }

    private fun writeTranscript(transcript: ChatTranscript) {
        root.mkdirs()
        val target = File(root, "${transcript.sessionId}.json")
        val temp = File(root, ".${transcript.sessionId}.${UUID.randomUUID()}.tmp")
        temp.writeText(json.encodeToString(transcript))
        atomicReplace(temp, target)
    }

    private fun atomicReplace(temp: File, target: File) {
        try {
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: java.io.IOException) {
            Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    private fun readIndex(): List<ChatSession> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<ChatIndex>(indexFile.readText()).sessions
                .filter { File(root, "${it.id}.json").isFile }
                .sortedByDescending { it.updatedAt }
        }
            .getOrDefault(emptyList())
    }
}
