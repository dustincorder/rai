package com.dustincorder.rai.data.chat

import com.dustincorder.rai.domain.ChatTitleSource
import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileChatSessionRepositoryTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("rai-chat-test-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `sessions survive repository recreation and sort by update time`() = runTest {
        var clock = 10L
        var nextId = 0
        val first = FileChatSessionRepository(root, now = { clock++ }, idFactory = { if (nextId++ == 0) "first" else "newer" })
        val created = first.createSession()
        first.saveMessages(created.id, listOf(message("hello")))
        val newer = first.createSession()
        first.saveMessages(newer.id, listOf(message("newer")))
        val recreated = FileChatSessionRepository(root, now = { clock++ }, idFactory = { "second" })

        assertEquals(listOf(newer.id, created.id), recreated.sessions.first().map { it.id })
        assertEquals(listOf("hello"), recreated.loadSession(created.id)?.messages?.map { it.text })
    }

    @Test
    fun `save updates session timestamp and transcript`() = runTest {
        var clock = 1L
        val repository = FileChatSessionRepository(root, now = { clock++ }, idFactory = { "chat" })
        val session = repository.createSession()
        val before = repository.sessions.first().single().updatedAt
        repository.saveMessages(session.id, listOf(message("updated")))

        assertTrue(repository.sessions.first().single().updatedAt > before)
        assertEquals("updated", repository.loadSession(session.id)?.messages?.single()?.text)
    }

    @Test
    fun `title is normalized and capped`() = runTest {
        val repository = FileChatSessionRepository(root, idFactory = { "chat" })
        val session = repository.createSession()
        repository.updateTitle(session.id, "  \"one   two\"  ", ChatTitleSource.Generated)

        val stored = repository.sessions.first().single()
        assertEquals("one two", stored.title)
        assertEquals(ChatTitleSource.Generated, stored.titleSource)
        assertTrue(stored.title!!.length <= 60)
    }

    @Test
    fun `title metadata does not change conversation activity ordering`() = runTest {
        var clock = 0L
        val repository = FileChatSessionRepository(root, now = { ++clock }, idFactory = { "chat-$clock" })
        val older = repository.createSession()
        val newer = repository.createSession()
        val olderUpdatedAt = repository.listSessions().single { it.id == older.id }.updatedAt
        val newerUpdatedAt = repository.listSessions().single { it.id == newer.id }.updatedAt

        repository.updateTitle(older.id, "Older title", ChatTitleSource.Generated)

        val sessions = repository.listSessions()
        assertEquals(newer.id, sessions.first().id)
        assertEquals(olderUpdatedAt, sessions.single { it.id == older.id }.updatedAt)
        assertEquals(newerUpdatedAt, sessions.single { it.id == newer.id }.updatedAt)
    }

    @Test
    fun `manual title wins over generated title at repository boundary`() = runTest {
        val repository = FileChatSessionRepository(root, idFactory = { "chat" })
        val session = repository.createSession()
        repository.markTitleGenerationAttempted(session.id)
        repository.updateTitle(session.id, "Manual title", ChatTitleSource.Manual)

        repository.updateTitle(session.id, "Late generated title", ChatTitleSource.Generated)

        val stored = repository.listSessions().single()
        assertEquals("Manual title", stored.title)
        assertEquals(ChatTitleSource.Manual, stored.titleSource)
    }

    @Test
    fun `invalid index and transcript fail closed`() = runTest {
        File(root, "chat-index.json").writeText("not json")
        val repository = FileChatSessionRepository(root, idFactory = { "chat" })
        assertTrue(repository.sessions.first().isEmpty())

        val session = repository.createSession()
        File(root, "${session.id}.json").writeText("not json")
        assertNull(repository.loadSession(session.id))
    }

    @Test
    fun `unknown session writes are ignored and delete removes files`() = runTest {
        val repository = FileChatSessionRepository(root, idFactory = { "chat" })
        repository.saveMessages("missing", listOf(message("ignored")))
        assertFalse(File(root, "missing.json").exists())

        val session = repository.createSession()
        repository.deleteSession(session.id)
        assertTrue(repository.sessions.first().isEmpty())
        assertFalse(File(root, "${session.id}.json").exists())
    }

    private fun message(text: String) = ConversationMessage(ConversationRole.User, text)
}
