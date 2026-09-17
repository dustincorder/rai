package com.dustincorder.rai.domain

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow

@Serializable
data class ChatSession(
    val id: String,
    val title: String? = null,
    val titleSource: ChatTitleSource = ChatTitleSource.Default,
    val createdAt: Long,
    val updatedAt: Long,
    val titleGenerationAttempted: Boolean = false,
)

@Serializable
enum class ChatTitleSource {
    Default,
    Generated,
    Derived,
    Manual,
}

@Serializable
data class ChatTranscript(
    val sessionId: String,
    val messages: List<ConversationMessage>,
)

interface ChatSessionRepository {
    val sessions: Flow<List<ChatSession>>
    suspend fun listSessions(): List<ChatSession>
    suspend fun createSession(): ChatSession
    suspend fun loadSession(id: String): ChatTranscript?
    suspend fun saveMessages(id: String, messages: List<ConversationMessage>)
    suspend fun updateTitle(id: String, title: String, source: ChatTitleSource)
    suspend fun markTitleGenerationAttempted(id: String)
    suspend fun deleteSession(id: String)
}

interface ChatTitleGenerator {
    suspend fun generate(messages: List<ConversationMessage>): String?
}

interface ConversationOwner {
    val conversation: kotlinx.coroutines.flow.StateFlow<List<ConversationMessage>>
    fun canReplaceConversation(): Boolean
    fun replaceConversation(messages: List<ConversationMessage>): Boolean
}

sealed interface ActiveConversation {
    data object NewDraft : ActiveConversation
    data class Persistent(val sessionId: String) : ActiveConversation
}

fun shouldGenerateChatTitle(session: ChatSession, messages: List<ConversationMessage>): Boolean {
    if (session.titleSource != ChatTitleSource.Default || session.titleGenerationAttempted) return false
    val hasUser = messages.any { it.role == ConversationRole.User && it.contextText.isNotBlank() }
    val hasAssistant = messages.any { it.role == ConversationRole.Assistant }
    return hasUser && hasAssistant
}

fun deriveChatTitle(messages: List<ConversationMessage>): String? {
    val source = messages.firstOrNull {
        it.role == ConversationRole.User && it.contextText.isNotBlank()
    }?.contextText ?: return null
    val clean = source.replace(Regex("\\s+"), " ").trim().trim('"', '\'')
    if (clean.isBlank()) return null
    if (clean.length <= 50) return clean
    return clean.take(50).substringBeforeLast(' ').trimEnd().ifBlank { clean.take(50) } + "…"
}

fun sanitizeChatTitle(raw: String?): String? {
    val clean = raw.orEmpty()
        .replace(Regex("\\s+"), " ")
        .trim()
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
        .replace(Regex("^(#+|[-*])\\s+"), "")
        .trim('"', '\'')
        .take(60)
        .trim()
    return clean.takeIf { it.isNotBlank() && it.any(Char::isLetterOrDigit) }
}
