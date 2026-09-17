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

        assertTrue(coordinator.sessions.value.isEmpty())
        assertEquals(ActiveConversation.NewDraft, coordinator.activeConversation.value)
        assertTrue(owner.conversation.value.isEmpty())
    }

    @Test
    fun `new chat with no message leaves repository unchanged`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()

        coordinator.createNewChat()
        advanceUntilIdle()

        assertTrue(repository.listSessions().isEmpty())
        assertEquals(ActiveConversation.NewDraft, coordinator.activeConversation.value)
    }

    @Test
    fun `first typed user turn creates exactly one persistent session`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()

        owner.set(listOf(user("typed first message")))
        advanceUntilIdle()

        assertEquals(1, repository.listSessions().size)
        assertEquals(ActiveConversation.Persistent(repository.listSessions().single().id), coordinator.activeConversation.value)
        assertEquals("typed first message", repository.loadSession(coordinator.persistentId())?.messages?.single()?.text)
    }

    @Test
    fun `first voice originated user turn creates exactly one persistent session`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()

        owner.set(listOf(user("voice transcribed first message")))
        advanceUntilIdle()

        assertEquals(1, repository.listSessions().size)
        assertEquals(ActiveConversation.Persistent(repository.listSessions().single().id), coordinator.activeConversation.value)
    }

    @Test
    fun `assistant or notice only events do not create a draft session`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()

        owner.set(listOf(ConversationMessage(ConversationRole.Assistant, "orphan")))
        advanceUntilIdle()
        owner.set(listOf(ConversationMessage(ConversationRole.Notice, "notice")))
        advanceUntilIdle()

        assertTrue(repository.listSessions().isEmpty())
        assertEquals(ActiveConversation.NewDraft, coordinator.activeConversation.value)
    }

    @Test
    fun `opening existing chat abandons untouched draft without persistence`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()
        val existing = repository.createSession()

        coordinator.openChat(existing.id)
        advanceUntilIdle()

        assertEquals(listOf(existing.id), repository.listSessions().map { it.id })
        assertEquals(ActiveConversation.Persistent(existing.id), coordinator.activeConversation.value)
        assertTrue(owner.conversation.value.isEmpty())
    }

    @Test
    fun `new chat becomes active and clears visible conversation`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()
        owner.set(listOf(user("old")))
        advanceUntilIdle()
        val oldId = coordinator.persistentId()

        coordinator.createNewChat()
        advanceUntilIdle()

        assertEquals(1, repository.listSessions().size)
        assertEquals(ActiveConversation.NewDraft, coordinator.activeConversation.value)
        assertEquals(oldId, repository.listSessions().single().id)
        assertTrue(owner.conversation.value.isEmpty())
        assertEquals("old", repository.loadSession(oldId)?.messages?.single()?.text)
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

        assertEquals(first.id, coordinator.persistentId())
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

        assertEquals(first.id, coordinator.persistentId())
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

        assertEquals(ActiveConversation.NewDraft, coordinator.activeConversation.value)
        assertNotEquals(only.id, coordinator.persistentIdOrNull())
        assertEquals(0, coordinator.sessions.value.size)
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
        assertEquals(ChatTitleSource.Derived, repository.listSessions().single().titleSource)
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
        assertEquals("Project plan", coordinator.sessions.value.single().title)
    }

    @Test
    fun `null title falls back to first user message`() = runTest {
        val owner = FakeConversationOwner()
        val generator = ControlledTitleGenerator(result = null)
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
        assertTrue(session.title!!.startsWith("Please help me plan"))
        assertTrue(session.title!!.length <= 60)
        assertEquals(ChatTitleSource.Derived, session.titleSource)
        assertEquals(before, session.updatedAt)
        assertEquals(1, generator.calls)
    }

    @Test
    fun `blank title falls back without exception text`() = runTest {
        val owner = FakeConversationOwner()
        val generator = ControlledTitleGenerator(result = " \n ``` ")
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner, generator)
        coordinator.start()
        advanceUntilIdle()
        owner.set(eligibleMessages())
        advanceUntilIdle()
        generator.release()
        advanceUntilIdle()

        val session = repository.listSessions().single()
        assertTrue(session.title!!.startsWith("Please help"))
        assertEquals(ChatTitleSource.Derived, session.titleSource)
        assertEquals(1, generator.calls)
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
        repository.updateTitle(coordinator.persistentId(), "Manual title", ChatTitleSource.Manual)
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
        owner.set(listOf(user("Current conversation starts here"), ConversationMessage(ConversationRole.Assistant, "Current answer")))
        advanceUntilIdle()
        generator.release()
        advanceUntilIdle()

        val session = repository.listSessions().single()
        assertEquals("Current conversation starts here", session.title)
        assertEquals(ChatTitleSource.Derived, session.titleSource)
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

        assertEquals(first.id, coordinator.persistentId())
        assertTrue(owner.conversation.value.isEmpty())
    }

    @Test
    fun `temporary chat starts from draft and never enters history`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()

        coordinator.startTemporaryChat()
        advanceUntilIdle()
        owner.set(listOf(user("private note")))
        advanceUntilIdle()

        assertEquals(ActiveConversation.Temporary, coordinator.activeConversation.value)
        assertTrue(repository.listSessions().isEmpty())
        assertEquals("private note", owner.conversation.value.single().text)
    }

    @Test
    fun `temporary chat replaces persistent chat and switching discards it`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val persistent = repository.createSession()
        repository.saveMessages(persistent.id, listOf(user("saved")))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()

        coordinator.startTemporaryChat()
        advanceUntilIdle()
        owner.set(listOf(user("private")))
        advanceUntilIdle()
        coordinator.openChat(persistent.id)
        advanceUntilIdle()

        assertEquals(ActiveConversation.Persistent(persistent.id), coordinator.activeConversation.value)
        assertEquals("saved", owner.conversation.value.single().text)
        assertEquals(listOf(persistent.id), repository.listSessions().map { it.id })
    }

    @Test
    fun `temporary chat can be saved exactly once with complete transcript`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()
        coordinator.startTemporaryChat()
        advanceUntilIdle()
        val transcript = listOf(user("private"), assistant("answer"))
        owner.set(transcript)
        advanceUntilIdle()

        coordinator.saveTemporaryChat()
        advanceUntilIdle()
        coordinator.saveTemporaryChat()
        advanceUntilIdle()

        val sessions = repository.listSessions()
        assertEquals(1, sessions.size)
        assertEquals(ActiveConversation.Persistent(sessions.single().id), coordinator.activeConversation.value)
        assertEquals(transcript, repository.loadSession(sessions.single().id)?.messages)
    }

    @Test
    fun `temporary chat can switch to new draft without history entry`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()
        coordinator.startTemporaryChat()
        advanceUntilIdle()
        owner.set(listOf(user("private")))
        advanceUntilIdle()

        coordinator.createNewChat()
        advanceUntilIdle()

        assertEquals(ActiveConversation.NewDraft, coordinator.activeConversation.value)
        assertTrue(repository.listSessions().isEmpty())
        assertTrue(owner.conversation.value.isEmpty())
    }

    @Test
    fun `reconstructed coordinator cannot restore temporary mode`() = runTest {
        val owner = FakeConversationOwner()
        val repository = repository(UnconfinedTestDispatcher(testScheduler))
        val coordinator = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, owner)
        coordinator.start()
        advanceUntilIdle()
        coordinator.startTemporaryChat()
        advanceUntilIdle()
        owner.set(listOf(user("gone after process death")))
        advanceUntilIdle()

        val restoredOwner = FakeConversationOwner()
        val restored = ChatSessionCoordinator(coordinatorScope(backgroundScope, testScheduler), repository, restoredOwner)
        restored.start()
        advanceUntilIdle()

        assertEquals(ActiveConversation.NewDraft, restored.activeConversation.value)
        assertTrue(restoredOwner.conversation.value.isEmpty())
        assertTrue(repository.listSessions().isEmpty())
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
    private fun assistant(text: String) = ConversationMessage(ConversationRole.Assistant, text)

    private fun ChatSessionCoordinator.persistentId() =
        (activeConversation.value as ActiveConversation.Persistent).sessionId

    private fun ChatSessionCoordinator.persistentIdOrNull() =
        (activeConversation.value as? ActiveConversation.Persistent)?.sessionId
}
