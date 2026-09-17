package com.dustincorder.rai.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {
    @Test
    fun `title waits for assistant response and enough user context`() {
        val session = ChatSession("chat", createdAt = 1L, updatedAt = 1L)
        assertFalse(shouldGenerateChatTitle(session, listOf(user("This is a sufficiently long request about my schedule"))))
        assertTrue(
            shouldGenerateChatTitle(
                session,
                listOf(user("This is a sufficiently long request about my schedule"), assistant("Answer")),
            ),
        )
    }

    @Test
    fun `title generates once only for default sessions`() {
        val messages = listOf(user("one"), assistant("two"), user("three"), assistant("four"))
        assertTrue(shouldGenerateChatTitle(ChatSession("chat", createdAt = 1L, updatedAt = 1L), messages))
        assertFalse(
            shouldGenerateChatTitle(
                ChatSession("chat", createdAt = 1L, updatedAt = 1L, titleGenerationAttempted = true),
                messages,
            ),
        )
        assertFalse(
            shouldGenerateChatTitle(
                ChatSession("chat", title = "Saved", titleSource = ChatTitleSource.Generated, createdAt = 1L, updatedAt = 1L),
                messages,
            ),
        )
    }

    private fun user(text: String) = ConversationMessage(ConversationRole.User, text)
    private fun assistant(text: String) = ConversationMessage(ConversationRole.Assistant, text)
}
