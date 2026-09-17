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
    private val _activeConversation = MutableStateFlow<ActiveConversation>(ActiveConversation.NewDraft)
    val activeConversation: StateFlow<ActiveConversation> = _activeConversation
    private val mutationMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val existing = repository.listSessions()
            _sessions.value = existing
            val active = existing.firstOrNull()?.let { ActiveConversation.Persistent(it.id) }
                ?: ActiveConversation.NewDraft
            _activeConversation.value = active
            if (active is ActiveConversation.Persistent) {
                conversationOwner.replaceConversation(repository.loadSession(active.sessionId)?.messages.orEmpty())
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { observeConversation() }
            ready.complete(Unit)
        }
    }

    fun createNewChat() = launchReady {
        if (!conversationOwner.canReplaceConversation()) return@launchReady
        if (_activeConversation.value is ActiveConversation.NewDraft && conversationOwner.conversation.value.isEmpty()) {
            return@launchReady
        }
        if (!conversationOwner.replaceConversation(emptyList())) return@launchReady
        _activeConversation.value = ActiveConversation.NewDraft
    }

    fun openChat(id: String) = launchReady {
        if (!conversationOwner.canReplaceConversation()) return@launchReady
        if (repository.listSessions().none { it.id == id }) return@launchReady
        val messages = repository.loadSession(id)?.messages.orEmpty()
        if (!conversationOwner.replaceConversation(messages)) return@launchReady
        _activeConversation.value = ActiveConversation.Persistent(id)
        _sessions.value = repository.listSessions()
    }

    fun deleteChat(id: String) = launchReady {
        if (!conversationOwner.canReplaceConversation()) return@launchReady
        if (repository.listSessions().none { it.id == id }) return@launchReady
        repository.deleteSession(id)
        var sessions = repository.listSessions()
        if ((_activeConversation.value as? ActiveConversation.Persistent)?.sessionId == id) {
            val next = sessions.firstOrNull()
            if (next == null) {
                if (!conversationOwner.replaceConversation(emptyList())) return@launchReady
                _activeConversation.value = ActiveConversation.NewDraft
            } else {
                val messages = repository.loadSession(next.id)?.messages.orEmpty()
                if (!conversationOwner.replaceConversation(messages)) return@launchReady
                _activeConversation.value = ActiveConversation.Persistent(next.id)
            }
            sessions = repository.listSessions()
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
                val sessionId = when (val active = _activeConversation.value) {
                    ActiveConversation.NewDraft -> {
                        if (messages.none { it.role == ConversationRole.User && it.contextText.isNotBlank() }) {
                            return@withLock
                        }
                        val session = repository.createSession()
                        repository.saveMessages(session.id, messages)
                        _activeConversation.value = ActiveConversation.Persistent(session.id)
                        session.id
                    }
                    is ActiveConversation.Persistent -> {
                        if (!isInitialEmission) repository.saveMessages(active.sessionId, messages)
                        active.sessionId
                    }
                }
                _sessions.value = repository.listSessions()
                val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return@withLock
                if (shouldGenerateChatTitle(session, messages)) {
                    repository.markTitleGenerationAttempted(sessionId)
                    _sessions.value = repository.listSessions()
                    titleRequest = sessionId to messages
                }
            }
            titleRequest?.let { (id, snapshot) -> launchTitleAttempt(id, snapshot) }
        }
    }

    private fun launchTitleAttempt(id: String, snapshot: List<ConversationMessage>) {
        scope.launch {
            val generated = titleGenerator?.let { generator ->
                runCatching { generator.generate(snapshot) }.getOrNull()
            }
            val generatedTitle = sanitizeChatTitle(generated)
            val fallbackTitle = deriveChatTitle(snapshot)
            val title = generatedTitle ?: fallbackTitle ?: return@launch
            val source = if (generatedTitle != null) ChatTitleSource.Generated else ChatTitleSource.Derived
            mutationMutex.withLock {
                val currentMessages = repository.loadSession(id)?.messages
                val canApply = if (generatedTitle != null) {
                    currentMessages == snapshot
                } else {
                    currentMessages?.any { it.role == ConversationRole.User && it.contextText.isNotBlank() } == true
                }
                if (canApply) {
                    repository.updateTitle(id, title, source)
                    _sessions.value = repository.listSessions()
                }
            }
        }
    }
}
