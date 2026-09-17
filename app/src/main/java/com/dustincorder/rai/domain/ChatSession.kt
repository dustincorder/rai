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

fun shouldGenerateChatTitle(session: ChatSession, messages: List<ConversationMessage>): Boolean {
    if (session.titleSource != ChatTitleSource.Default || session.titleGenerationAttempted) return false
    val userCount = messages.count { it.role == ConversationRole.User }
    val hasAssistant = messages.any { it.role == ConversationRole.Assistant }
    val userTextLength = messages.filter { it.role == ConversationRole.User }.sumOf { it.contextText.length }
    return hasAssistant && (userTextLength >= 40 || userCount >= 2)
}
