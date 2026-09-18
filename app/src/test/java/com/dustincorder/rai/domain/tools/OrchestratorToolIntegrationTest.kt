package com.dustincorder.rai.domain.tools

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import com.dustincorder.rai.domain.InteractionMode
import com.dustincorder.rai.domain.RayaEmotion
import com.dustincorder.rai.domain.RayaOrchestrator
import com.dustincorder.rai.domain.RayaResponse
import com.dustincorder.rai.domain.RayaState
import com.dustincorder.rai.domain.RecognitionRequest
import com.dustincorder.rai.domain.ReplyEvent
import com.dustincorder.rai.domain.ReplyProvider
import com.dustincorder.rai.domain.SpeechRecognitionEvent
import com.dustincorder.rai.domain.SpeechRecognitionProvider
import com.dustincorder.rai.domain.SpeechSynthesisProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorToolIntegrationTest {

    private class EmitterRecognition : SpeechRecognitionProvider {
        val flow = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 10)
        override val events: Flow<SpeechRecognitionEvent> = flow
        override suspend fun startListening(request: RecognitionRequest) {}
        override fun cancel() {}
        override fun release() {}
        suspend fun emit(event: SpeechRecognitionEvent) {
            flow.emit(event)
        }
    }

    private class NoOpRecognition : SpeechRecognitionProvider {
        override val events: Flow<SpeechRecognitionEvent> = emptyFlow()
        override suspend fun startListening(request: RecognitionRequest) {}
        override fun cancel() {}
        override fun release() {}
    }

    private class NoOpSynthesis : SpeechSynthesisProvider {
        override suspend fun speak(text: String, locale: Locale) {}
        override fun stop() {}
        override fun shutdown() {}
    }

    private class DualReplyAndInvoker(
        private val roundEvents: List<List<ModelRoundStreamEvent>>,
    ) : ReplyProvider, ModelTurnInvoker {
        var round = 0

        override suspend fun reply(messages: List<ConversationMessage>, languageTag: String?): RayaResponse {
            return RayaResponse("Direct reply", RayaEmotion.Calm)
        }

        override fun streamReply(messages: List<ConversationMessage>, languageTag: String?): Flow<ReplyEvent> = flow {
            emit(ReplyEvent.Completed(RayaResponse("Streaming direct reply", RayaEmotion.Calm)))
        }

        override fun streamRound(
            messages: List<ConversationMessage>,
            exposedTools: List<ToolDefinition>,
            steps: List<ModelRoundStep>,
            languageTag: String?,
        ): Flow<ModelRoundStreamEvent> = flow {
            val events = roundEvents.getOrElse(round++) { emptyList() }
            for (event in events) {
                emit(event)
            }
        }
    }

    private class SimpleTool(
        override val definition: ToolDefinition,
    ) : RayaTool {
        var executed = false
        override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject): ToolResult {
            executed = true
            return ToolResult.Success(buildJsonObject { put("result", "ok") })
        }
    }

    private val permissiveValidator = object : JsonSchemaValidator {
        override fun validate(schema: kotlinx.serialization.json.JsonObject, instance: kotlinx.serialization.json.JsonElement) = SchemaValidationResult.Valid
    }

    @Test
    fun `tool requiring confirmation pauses turn and surfaces token`() = runTest {
        val toolDef = ToolDefinition(
            id = ToolId("delete_alarm"),
            name = "delete_alarm",
            description = "Delete an alarm",
            effect = ToolEffect.Destructive,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = buildJsonObject {},
        )
        val tool = SimpleTool(toolDef)
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, permissiveValidator)

        val invoker = DualReplyAndInvoker(
            listOf(
                listOf(ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("call_1", "delete_alarm", buildJsonObject {})))),
                listOf(ModelRoundStreamEvent.Completed(RayaResponse("Alarm deleted", RayaEmotion.Calm))),
            ),
        )

        val orchestrator = RayaOrchestrator(
            scope = this,
            speechRecognition = NoOpRecognition(),
            speechSynthesis = NoOpSynthesis(),
            replyProvider = invoker,
            toolTurnRunner = runner,
            toolRegistry = registry,
        )

        orchestrator.submitText("Delete alarm")
        runCurrent()

        // Confirmation should be surfaced in pendingToolConfirmation
        val pending = orchestrator.pendingToolConfirmation.value
        assertNotNull(pending)
        assertEquals("delete_alarm", pending?.definition?.name)
        assertFalse(tool.executed)

        // Now confirm using the token
        orchestrator.confirmPendingTool(pending!!.token)
        runCurrent()

        // Confirmation token should be cleared and tool executed
        assertNull(orchestrator.pendingToolConfirmation.value)
        assertTrue(tool.executed)
        assertEquals(RayaState.Idle, orchestrator.state.value)
        val lastMsg = orchestrator.conversation.value.last()
        assertEquals("Alarm deleted", lastMsg.text)
    }

    @Test
    fun `rejecting confirmation clears pending confirmation`() = runTest {
        val toolDef = ToolDefinition(
            id = ToolId("del"),
            name = "del",
            description = "desc",
            effect = ToolEffect.Destructive,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = buildJsonObject {},
        )
        val tool = SimpleTool(toolDef)
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, permissiveValidator)
        val invoker = DualReplyAndInvoker(
            listOf(
                listOf(ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c1", "del", buildJsonObject {})))),
            ),
        )

        val orchestrator = RayaOrchestrator(
            scope = this,
            speechRecognition = NoOpRecognition(),
            speechSynthesis = NoOpSynthesis(),
            replyProvider = invoker,
            toolTurnRunner = runner,
            toolRegistry = registry,
        )

        orchestrator.submitText("Delete")
        runCurrent()

        assertNotNull(orchestrator.pendingToolConfirmation.value)
        orchestrator.rejectPendingTool()
        assertNull(orchestrator.pendingToolConfirmation.value)
        assertFalse(tool.executed)
    }

    @Test
    fun `replaceConversation and clearConversation invalidate pending confirmation`() = runTest {
        val toolDef = ToolDefinition(
            id = ToolId("del"),
            name = "del",
            description = "desc",
            effect = ToolEffect.Destructive,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = buildJsonObject {},
        )
        val registry = InMemoryToolRegistry(listOf(SimpleTool(toolDef)))
        val runner = ToolTurnRunner(registry, permissiveValidator)
        val invoker = DualReplyAndInvoker(
            listOf(
                listOf(ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c1", "del", buildJsonObject {})))),
            ),
        )

        val orchestrator = RayaOrchestrator(
            scope = this,
            speechRecognition = NoOpRecognition(),
            speechSynthesis = NoOpSynthesis(),
            replyProvider = invoker,
            toolTurnRunner = runner,
            toolRegistry = registry,
        )

        orchestrator.submitText("Delete")
        runCurrent()
        assertNotNull(orchestrator.pendingToolConfirmation.value)

        // Clear conversation clears confirmation
        orchestrator.clearConversation()
        assertNull(orchestrator.pendingToolConfirmation.value)
    }

    @Test
    fun `voice tool call requiring confirmation keeps token valid for approval`() = runTest {
        val toolDef = ToolDefinition(
            id = ToolId("voice_destruct"),
            name = "voice_destruct",
            description = "Destructive voice action",
            effect = ToolEffect.Destructive,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = buildJsonObject {},
        )
        val tool = SimpleTool(toolDef)
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, permissiveValidator)

        val invoker = DualReplyAndInvoker(
            listOf(
                listOf(ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("v_call_1", "voice_destruct", buildJsonObject {})))),
                listOf(ModelRoundStreamEvent.Completed(RayaResponse("Voice action approved and done", RayaEmotion.Happy))),
            ),
        )

        val recognition = EmitterRecognition()
        val orchestrator = RayaOrchestrator(
            scope = this,
            speechRecognition = recognition,
            speechSynthesis = NoOpSynthesis(),
            replyProvider = invoker,
            toolTurnRunner = runner,
            toolRegistry = registry,
        )

        // Start voice session
        orchestrator.startVoiceSession()
        runCurrent()
        assertTrue(orchestrator.voiceSessionActive.value)

        // Simulate voice recognition returning query addressed to Raya
        recognition.emit(SpeechRecognitionEvent.Final("Рая, выполни действие", "ru-RU"))
        runCurrent()

        // Voice session ends because confirmation is required, returning to idle/text
        assertFalse(orchestrator.voiceSessionActive.value)
        assertEquals(InteractionMode.Text, orchestrator.interactionMode.value)
        val pending = orchestrator.pendingToolConfirmation.value
        assertNotNull("Pending confirmation must exist after voice tool call", pending)
        assertEquals("voice_destruct", pending?.definition?.name)
        assertFalse("Tool must not execute prior to confirmation", tool.executed)

        // Approving the token must succeed without being invalidated by voice session end
        orchestrator.confirmPendingTool(pending!!.token)
        runCurrent()

        assertTrue("Tool must be executed after confirmation approval", tool.executed)
        assertNull(orchestrator.pendingToolConfirmation.value)
        assertEquals(RayaState.Idle, orchestrator.state.value)
        val lastMsg = orchestrator.conversation.value.last()
        assertEquals("Voice action approved and done", lastMsg.text)
    }

    @Test
    fun `voice pending confirmation invalidated by cancel replace or new text turn`() = runTest {
        val toolDef = ToolDefinition(
            id = ToolId("voice_del"),
            name = "voice_del",
            description = "desc",
            effect = ToolEffect.Destructive,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = buildJsonObject {},
        )
        val tool = SimpleTool(toolDef)
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, permissiveValidator)
        val invoker = DualReplyAndInvoker(
            listOf(
                listOf(ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("v1", "voice_del", buildJsonObject {})))),
                listOf(ModelRoundStreamEvent.Completed(RayaResponse("Text done", RayaEmotion.Calm))),
            ),
        )
        val recognition = EmitterRecognition()
        val orchestrator = RayaOrchestrator(
            scope = this,
            speechRecognition = recognition,
            speechSynthesis = NoOpSynthesis(),
            replyProvider = invoker,
            toolTurnRunner = runner,
            toolRegistry = registry,
        )

        // Trigger voice confirmation
        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, удали", "ru-RU"))
        runCurrent()

        val pending = orchestrator.pendingToolConfirmation.value
        assertNotNull(pending)
        val savedToken = pending!!.token

        // Invalidate via conversation replacement
        orchestrator.replaceConversation(listOf(ConversationMessage(ConversationRole.User, "Reset")))
        assertNull(orchestrator.pendingToolConfirmation.value)

        // Attempting to confirm with old token must do nothing
        orchestrator.confirmPendingTool(savedToken)
        runCurrent()
        assertFalse("Tool must not execute with invalidated token", tool.executed)
    }

    @Test
    fun `active registry with ordinary model reply streams deltas and executes zero tools`() = runTest {
        val toolDef = ToolDefinition(
            id = ToolId("dummy_tool"),
            name = "dummy_tool",
            description = "A registered tool",
            effect = ToolEffect.ReadOnly,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = buildJsonObject {},
        )
        val tool = SimpleTool(toolDef)
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, permissiveValidator)

        val invoker = DualReplyAndInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.TextDelta("Привет! "),
                    ModelRoundStreamEvent.TextDelta("Как я могу помочь?"),
                    ModelRoundStreamEvent.Completed(RayaResponse("Привет! Как я могу помочь?", RayaEmotion.Happy)),
                ),
            ),
        )

        val orchestrator = RayaOrchestrator(
            scope = this,
            speechRecognition = NoOpRecognition(),
            speechSynthesis = NoOpSynthesis(),
            replyProvider = invoker,
            toolTurnRunner = runner,
            toolRegistry = registry,
        )

        orchestrator.submitText("Привет")
        runCurrent()

        // Verified: zero tools executed
        assertFalse(tool.executed)
        assertEquals(RayaState.Idle, orchestrator.state.value)
        val lastMsg = orchestrator.conversation.value.last()
        assertEquals(ConversationRole.Assistant, lastMsg.role)
        assertEquals("Привет! Как я могу помочь?", lastMsg.text)
    }
}
