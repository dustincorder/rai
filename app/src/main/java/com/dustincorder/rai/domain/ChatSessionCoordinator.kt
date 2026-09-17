package com.dustincorder.rai.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatSessionCoordinator(
    private val scope: CoroutineScope,
    private val repository: ChatSessionRepository,
    private val conversationOwner: ConversationOwner,
    private val titleGenerator: ChatTitleGenerator? = null,
) {
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId
    private val mutationMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val existing = repository.listSessions()
            _sessions.value = existing
            val active = existing.firstOrNull() ?: repository.createSession().also {
                _sessions.value = repository.listSessions()
            }
            _activeId.value = active.id
            conversationOwner.replaceConversation(repository.loadSession(active.id)?.messages.orEmpty())
            scope.launch(start = CoroutineStart.UNDISPATCHED) { observeConversation() }
            ready.complete(Unit)
        }
    }

    fun createNewChat() = launchReady {
        if (!conversationOwner.canReplaceConversation()) return@launchReady
        val session = repository.createSession()
        if (!conversationOwner.replaceConversation(emptyList())) {
            repository.deleteSession(session.id)
            return@launchReady
        }
        _activeId.value = session.id
        _sessions.value = repository.listSessions()
    }

    fun openChat(id: String) = launchReady {
        if (!conversationOwner.canReplaceConversation()) return@launchReady
        if (repository.listSessions().none { it.id == id }) return@launchReady
        val messages = repository.loadSession(id)?.messages.orEmpty()
        if (!conversationOwner.replaceConversation(messages)) return@launchReady
        _activeId.value = id
        _sessions.value = repository.listSessions()
    }

    fun deleteChat(id: String) = launchReady {
        if (!conversationOwner.canReplaceConversation()) return@launchReady
        repository.deleteSession(id)
        var sessions = repository.listSessions()
        if (_activeId.value == id) {
            val next = sessions.firstOrNull() ?: repository.createSession()
            sessions = repository.listSessions()
            val messages = repository.loadSession(next.id)?.messages.orEmpty()
            if (!conversationOwner.replaceConversation(messages)) return@launchReady
            _activeId.value = next.id
        }
        _sessions.value = sessions
    }

    private fun launchReady(block: suspend () -> Unit) {
        scope.launch {
            ready.await()
            mutationMutex.withLock { block() }
        }
    }

    private suspend fun observeConversation() {
        var initialEmission = true
        conversationOwner.conversation.collect { messages ->
            val isInitialEmission = initialEmission
            initialEmission = false
            var titleRequest: Pair<String, List<ConversationMessage>>? = null
            mutationMutex.withLock {
                val id = _activeId.value ?: return@withLock
                if (!isInitialEmission) repository.saveMessages(id, messages)
                _sessions.value = repository.listSessions()
                val session = _sessions.value.firstOrNull { it.id == id }
                if (titleGenerator != null && session != null && shouldGenerateChatTitle(session, messages)) {
                    repository.markTitleGenerationAttempted(id)
                    _sessions.value = repository.listSessions()
                    titleRequest = id to messages
                }
            }
            titleRequest?.let { (id, snapshot) ->
                val generator = titleGenerator ?: return@let
                scope.launch {
                    runCatching { generator.generate(snapshot) }.getOrNull()?.let { title ->
                        mutationMutex.withLock {
                            if (repository.loadSession(id)?.messages == snapshot) {
                                repository.updateTitle(id, title, ChatTitleSource.Generated)
                                _sessions.value = repository.listSessions()
                            }
                        }
                    }
                }
            }
        }
    }
}
