package com.dustincorder.rai.domain.tools

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
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
        private val roundResponses: List<ModelRoundResponse>,
    ) : ReplyProvider, ModelTurnInvoker {
        var round = 0

        override suspend fun reply(messages: List<ConversationMessage>, languageTag: String?): RayaResponse {
            return RayaResponse("Direct reply", RayaEmotion.Calm)
        }

        override fun streamReply(messages: List<ConversationMessage>, languageTag: String?): Flow<ReplyEvent> = flow {
            emit(ReplyEvent.Completed(RayaResponse("Streaming direct reply", RayaEmotion.Calm)))
        }

        override suspend fun invokeRound(
            messages: List<ConversationMessage>,
            activeTools: List<ToolDefinition>,
            steps: List<ModelRoundStep>,
            languageTag: String?,
        ): ModelRoundResponse {
            return roundResponses[round++]
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
        val runner = ToolTurnRunner(registry, object : JsonSchemaValidator {
            override fun validate(schema: kotlinx.serialization.json.JsonObject, instance: kotlinx.serialization.json.JsonElement) = SchemaValidationResult.Valid
        })

        val invoker = DualReplyAndInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("call_1", "delete_alarm", buildJsonObject {}))),
                ModelRoundResponse.FinalReply(RayaResponse("Alarm deleted", RayaEmotion.Calm)),
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
        val runner = ToolTurnRunner(registry, object : JsonSchemaValidator {
            override fun validate(schema: kotlinx.serialization.json.JsonObject, instance: kotlinx.serialization.json.JsonElement) = SchemaValidationResult.Valid
        })
        val invoker = DualReplyAndInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c1", "del", buildJsonObject {}))),
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
        val runner = ToolTurnRunner(registry, object : JsonSchemaValidator {
            override fun validate(schema: kotlinx.serialization.json.JsonObject, instance: kotlinx.serialization.json.JsonElement) = SchemaValidationResult.Valid
        })
        val invoker = DualReplyAndInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c1", "del", buildJsonObject {}))),
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
}
