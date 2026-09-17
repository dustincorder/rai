package com.dustincorder.rai.domain

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.dustincorder.rai.data.chat.FileChatSessionRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionCoordinatorTest {
    private lateinit var root: java.io.File

    @Before
    fun setUp() { root = Files.createTempDirectory("rai-coordinator-test-").toFile() }

    @After
    fun tearDown() { root.deleteRecursively() }

    @Test
    fun `empty repository creates and activates one empty session`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)

        coordinator.start()
        advanceUntilIdle()

        assertEquals(1, coordinator.sessions.value.size)
        assertNotNull(coordinator.activeId.value)
        assertTrue(owner.conversation.value.isEmpty())
    }

    @Test
    fun `new chat becomes active and clears visible conversation`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()
        val oldId = coordinator.activeId.value!!
        owner.set(listOf(user("old")))
        advanceUntilIdle()

        coordinator.createNewChat()
        advanceUntilIdle()

        assertNotEquals(oldId, coordinator.activeId.value)
        assertTrue(owner.conversation.value.isEmpty())
        assertTrue(repository.loadSession(coordinator.activeId.value!!)?.messages.orEmpty().isEmpty())
    }

    @Test
    fun `opening chat loads its transcript and changes active id`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val first = repository.createSession()
        repository.saveMessages(first.id, listOf(user("first")))
        val second = repository.createSession()
        repository.saveMessages(second.id, listOf(user("second")))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()

        coordinator.openChat(first.id)
        advanceUntilIdle()

        assertEquals(first.id, coordinator.activeId.value)
        assertEquals("first", owner.conversation.value.single().text)
    }

    @Test
    fun `deleting active chat selects remaining chat and loads it`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val first = repository.createSession()
        repository.saveMessages(first.id, listOf(user("first")))
        val second = repository.createSession()
        repository.saveMessages(second.id, listOf(user("second")))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()
        coordinator.openChat(second.id)
        advanceUntilIdle()

        coordinator.deleteChat(second.id)
        advanceUntilIdle()

        assertEquals(first.id, coordinator.activeId.value)
        assertEquals("first", owner.conversation.value.single().text)
        assertNull(repository.loadSession(second.id))
    }

    @Test
    fun `deleting only active chat creates a new empty chat`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val only = repository.createSession()
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()

        coordinator.deleteChat(only.id)
        advanceUntilIdle()

        assertNotEquals(only.id, coordinator.activeId.value)
        assertEquals(1, coordinator.sessions.value.size)
        assertTrue(owner.conversation.value.isEmpty())
    }

    @Test
    fun `conversation changes persist only to active chat`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val first = repository.createSession()
        val second = repository.createSession()
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()

        coordinator.openChat(first.id)
        advanceUntilIdle()
        owner.set(listOf(user("first-only")))
        advanceUntilIdle()

        assertEquals("first-only", repository.loadSession(first.id)?.messages?.single()?.text)
        assertTrue(repository.loadSession(second.id)?.messages.orEmpty().isEmpty())
    }

    @Test
    fun `title failure leaves conversation state unchanged and blocks retry`() = runTest {
        val owner = FakeConversationOwner()
        val generator = ControlledTitleGenerator(failure = true)
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner, generator)
        coordinator.start()
        advanceUntilIdle()
        val before = owner.conversation.value
        owner.set(eligibleMessages())
        advanceUntilIdle()

        val session = repository.listSessions().single()
        assertNotEquals(before, owner.conversation.value)
        assertEquals(eligibleMessages(), owner.conversation.value)
        assertTrue(session.titleGenerationAttempted)
        assertEquals(1, generator.calls)
        generator.release()
        advanceUntilIdle()
        owner.set(eligibleMessages() + user("again"))
        advanceUntilIdle()
        assertEquals(1, generator.calls)
    }

    @Test
    fun `successful title preserves conversation updatedAt`() = runTest {
        val owner = FakeConversationOwner()
        val generator = ControlledTitleGenerator(result = "Project plan")
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner, generator)
        coordinator.start()
        advanceUntilIdle()
        owner.set(eligibleMessages())
        advanceUntilIdle()
        val before = repository.listSessions().single().updatedAt

        generator.release()
        advanceUntilIdle()

        val session = repository.listSessions().single()
        assertEquals("Project plan", session.title)
        assertEquals(before, session.updatedAt)
    }

    @Test
    fun `manual title wins over delayed generated result`() = runTest {
        val owner = FakeConversationOwner()
        val generator = ControlledTitleGenerator(result = "Generated title")
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner, generator)
        coordinator.start()
        advanceUntilIdle()
        owner.set(eligibleMessages())
        advanceUntilIdle()
        repository.updateTitle(coordinator.activeId.value!!, "Manual title", ChatTitleSource.Manual)
        generator.release()
        advanceUntilIdle()

        val session = repository.listSessions().single()
        assertEquals("Manual title", session.title)
        assertEquals(ChatTitleSource.Manual, session.titleSource)
    }

    @Test
    fun `stale generated result does not title changed conversation`() = runTest {
        val owner = FakeConversationOwner()
        val generator = ControlledTitleGenerator(result = "Stale title")
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner, generator)
        coordinator.start()
        advanceUntilIdle()
        owner.set(eligibleMessages())
        advanceUntilIdle()
        owner.set(eligibleMessages() + user("Changed after title request"))
        advanceUntilIdle()
        generator.release()
        advanceUntilIdle()

        assertNull(repository.listSessions().single().title)
    }

    @Test
    fun `busy conversation rejects chat switch without changing active id`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val first = repository.createSession()
        val second = repository.createSession()
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()
        coordinator.openChat(first.id)
        advanceUntilIdle()
        owner.allowReplacement = false

        coordinator.openChat(second.id)
        advanceUntilIdle()

        assertEquals(first.id, coordinator.activeId.value)
        assertTrue(owner.conversation.value.isEmpty())
    }

    private fun repository(dispatcher: kotlinx.coroutines.CoroutineDispatcher): FileChatSessionRepository =
        FileChatSessionRepository(root, ioDispatcher = dispatcher)

    private fun coordinatorScope(parent: CoroutineScope, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): CoroutineScope =
        CoroutineScope(parent.coroutineContext + UnconfinedTestDispatcher(scheduler))

    private class FakeConversationOwner : ConversationOwner {
        private val _conversation = MutableStateFlow<List<ConversationMessage>>(emptyList())
        override val conversation: StateFlow<List<ConversationMessage>> = _conversation
        var allowReplacement = true
        override fun canReplaceConversation(): Boolean = allowReplacement
        override fun replaceConversation(messages: List<ConversationMessage>): Boolean {
            if (!allowReplacement) return false
            _conversation.value = messages
            return true
        }
        fun set(messages: List<ConversationMessage>) { _conversation.value = messages }
    }

    private class ControlledTitleGenerator(
        private val result: String? = null,
        private val failure: Boolean = false,
    ) : ChatTitleGenerator {
        private val gate = CompletableDeferred<Unit>()
        var calls = 0
            private set
        override suspend fun generate(messages: List<ConversationMessage>): String? {
            calls++
            gate.await()
            if (failure) error("title failed")
            return result
        }
        fun release() { gate.complete(Unit) }
    }

    private fun eligibleMessages() = listOf(
        user("Please help me plan a detailed project schedule for next month"),
        ConversationMessage(ConversationRole.Assistant, "I can help with that."),
    )

    private fun user(text: String) = ConversationMessage(ConversationRole.User, text)
}
