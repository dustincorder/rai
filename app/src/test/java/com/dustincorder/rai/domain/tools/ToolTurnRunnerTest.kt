package com.dustincorder.rai.domain.tools

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import com.dustincorder.rai.domain.RayaEmotion
import com.dustincorder.rai.domain.RayaResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolTurnRunnerTest {

    private val simpleSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("param", buildJsonObject { put("type", "string") })
        })
        put("required", buildJsonArray { add(JsonPrimitive("param")) })
    }

    private class FakeValidator(
        private val valid: Boolean = true,
    ) : JsonSchemaValidator {
        override fun validate(schema: kotlinx.serialization.json.JsonObject, instance: kotlinx.serialization.json.JsonElement): SchemaValidationResult {
            return if (valid) SchemaValidationResult.Valid
            else SchemaValidationResult.Invalid(listOf(SchemaValidationError("/param", "Invalid parameter")))
        }
    }

    private class FakeTool(
        override val definition: ToolDefinition,
        private val onExecute: suspend (kotlinx.serialization.json.JsonObject) -> ToolResult = {
            ToolResult.Success(buildJsonObject { put("status", "ok") })
        },
    ) : RayaTool {
        var callCount = 0
        var receivedArgs: kotlinx.serialization.json.JsonObject? = null

        override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject): ToolResult {
            callCount++
            receivedArgs = arguments
            return onExecute(arguments)
        }
    }

    private class ScriptedModelInvoker(
        private val responses: List<ModelRoundResponse>,
    ) : ModelTurnInvoker {
        var invocationCount = 0
        val historySteps = mutableListOf<List<ModelRoundStep>>()

        override suspend fun invokeRound(
            messages: List<ConversationMessage>,
            activeTools: List<ToolDefinition>,
            steps: List<ModelRoundStep>,
            languageTag: String?,
        ): ModelRoundResponse {
            historySteps.add(steps.toList())
            if (invocationCount >= responses.size) {
                error("No scripted response for round $invocationCount")
            }
            return responses[invocationCount++]
        }
    }

    @Test
    fun `zero tool calls returns ordinary final response`() = runTest {
        val registry = InMemoryToolRegistry()
        val runner = ToolTurnRunner(registry, FakeValidator())
        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.FinalReply(RayaResponse("Hello there!", RayaEmotion.Calm, "en-US")),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "hi")),
            turnEpoch = 1,
            languageTag = "en-US",
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        val completed = result as ToolTurnResult.Completed
        assertEquals("Hello there!", completed.response.text)
        assertEquals(0, completed.executedCalls.size)
        assertEquals(1, invoker.invocationCount)
    }

    @Test
    fun `one tool call executes and produces final response`() = runTest {
        val toolDef = ToolDefinition(
            id = ToolId("echo_tool"),
            name = "echo",
            description = "Echo tool",
            effect = ToolEffect.ReadOnly,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = simpleSchema,
        )
        val tool = FakeTool(toolDef) { args ->
            ToolResult.Success(buildJsonObject { put("echoed", args["param"]?.jsonPrimitive?.content ?: "") })
        }
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(
                    listOf(
                        ToolCall("call_1", "echo", buildJsonObject { put("param", "apple") }),
                    ),
                ),
                ModelRoundResponse.FinalReply(RayaResponse("You got apple", RayaEmotion.Happy, "en-US")),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "Echo apple")),
            turnEpoch = 1,
            languageTag = "en-US",
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        val completed = result as ToolTurnResult.Completed
        assertEquals("You got apple", completed.response.text)
        assertEquals(1, completed.executedCalls.size)
        assertEquals("call_1", completed.executedCalls[0].callId)
        assertEquals(1, tool.callCount)
        assertEquals("apple", tool.receivedArgs?.get("param")?.jsonPrimitive?.content)

        // Check that invoker received tool result feedback in round 2
        val secondRoundSteps = invoker.historySteps[1]
        assertEquals(2, secondRoundSteps.size)
        val feedback = secondRoundSteps[1] as ModelRoundStep.ToolExecutionFeedback
        assertEquals("call_1", feedback.callId)
        assertEquals("echo", feedback.toolName)
        assertEquals("apple", feedback.result["echoed"]?.jsonPrimitive?.content)
    }

    @Test
    fun `multiple tool calls in one model round execute sequentially`() = runTest {
        val executionOrder = mutableListOf<String>()
        val toolA = FakeTool(
            ToolDefinition(ToolId("tool_a"), "tool_a", "desc A", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        ) {
            executionOrder.add("tool_a")
            ToolResult.Success(buildJsonObject { put("a", "done") })
        }
        val toolB = FakeTool(
            ToolDefinition(ToolId("tool_b"), "tool_b", "desc B", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        ) {
            executionOrder.add("tool_b")
            ToolResult.Success(buildJsonObject { put("b", "done") })
        }

        val registry = InMemoryToolRegistry(listOf(toolA, toolB))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(
                    listOf(
                        ToolCall("call_a", "tool_a", buildJsonObject { put("param", "1") }),
                        ToolCall("call_b", "tool_b", buildJsonObject { put("param", "2") }),
                    ),
                ),
                ModelRoundResponse.FinalReply(RayaResponse("Both executed", RayaEmotion.Calm)),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run both")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        val completed = result as ToolTurnResult.Completed
        assertEquals(2, completed.executedCalls.size)
        assertEquals(listOf("tool_a", "tool_b"), executionOrder)
    }

    @Test
    fun `multiple model rounds with tool calls`() = runTest {
        val tool = FakeTool(
            ToolDefinition(ToolId("t"), "t", "desc", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        )

        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c1", "t", buildJsonObject { put("param", "step1") }))),
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c2", "t", buildJsonObject { put("param", "step2") }))),
                ModelRoundResponse.FinalReply(RayaResponse("All done", RayaEmotion.Calm)),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "go")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        assertEquals(3, invoker.invocationCount)
        assertEquals(2, tool.callCount)
    }

    @Test
    fun `unknown tool provides validation error feedback without failing runner`() = runTest {
        val registry = InMemoryToolRegistry()
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c_unknown", "non_existent_tool", buildJsonObject {}))),
                ModelRoundResponse.FinalReply(RayaResponse("I cannot find that tool", RayaEmotion.Confused)),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run missing")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        val secondRoundSteps = invoker.historySteps[1]
        val feedback = secondRoundSteps[1] as ModelRoundStep.ToolExecutionFeedback
        assertEquals("c_unknown", feedback.callId)
        assertEquals("error", feedback.result["status"]?.jsonPrimitive?.content)
        assertEquals("ValidationFailed", feedback.result["error_kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `disabled tool provides policy error feedback`() = runTest {
        val disabledTool = FakeTool(
            ToolDefinition(ToolId("disabled"), "disabled", "desc", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema, enabled = false),
        )
        val registry = InMemoryToolRegistry(listOf(disabledTool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c_disabled", "disabled", buildJsonObject {}))),
                ModelRoundResponse.FinalReply(RayaResponse("Tool is disabled", RayaEmotion.Calm)),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run disabled")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        assertEquals(0, disabledTool.callCount)
        val feedback = invoker.historySteps[1][1] as ModelRoundStep.ToolExecutionFeedback
        assertEquals("PolicyDenied", feedback.result["error_kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `malformed arguments rejected by schema validation`() = runTest {
        val tool = FakeTool(
            ToolDefinition(ToolId("t"), "t", "desc", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        )
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, FakeValidator(valid = false))

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c1", "t", buildJsonObject { put("wrong", 123) }))),
                ModelRoundResponse.FinalReply(RayaResponse("Schema invalid", RayaEmotion.Thinking)),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        assertEquals(0, tool.callCount)
        val feedback = invoker.historySteps[1][1] as ModelRoundStep.ToolExecutionFeedback
        assertEquals("ValidationFailed", feedback.result["error_kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool failure feeds execution error back to model`() = runTest {
        val failingTool = FakeTool(
            ToolDefinition(ToolId("fail"), "fail", "desc", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        ) {
            ToolResult.Error(ToolErrorKind.ExecutionFailed, "Database connection crashed")
        }
        val registry = InMemoryToolRegistry(listOf(failingTool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c_fail", "fail", buildJsonObject { put("param", "x") }))),
                ModelRoundResponse.FinalReply(RayaResponse("Tool failed to execute", RayaEmotion.Sad)),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        val feedback = invoker.historySteps[1][1] as ModelRoundStep.ToolExecutionFeedback
        assertEquals("ExecutionFailed", feedback.result["error_kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `per-tool timeout feeds timeout error to model`() = runTest {
        val hangingTool = FakeTool(
            ToolDefinition(ToolId("hang"), "hang", "desc", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        ) {
            delay(10_000L)
            ToolResult.Success(buildJsonObject {})
        }
        val registry = InMemoryToolRegistry(listOf(hangingTool))
        val runner = ToolTurnRunner(
            registry = registry,
            schemaValidator = FakeValidator(),
            budget = ToolLoopBudget(perToolTimeoutMs = 100L),
        )

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c_hang", "hang", buildJsonObject { put("param", "x") }))),
                ModelRoundResponse.FinalReply(RayaResponse("Operation timed out", RayaEmotion.Annoyed)),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "hang")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        val feedback = invoker.historySteps[1][1] as ModelRoundStep.ToolExecutionFeedback
        assertEquals("Timeout", feedback.result["error_kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `total call budget exceeded fails turn`() = runTest {
        val tool = FakeTool(
            ToolDefinition(ToolId("t"), "t", "desc", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        )
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(
            registry = registry,
            schemaValidator = FakeValidator(),
            budget = ToolLoopBudget(maxTotalToolCalls = 2),
        )

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(
                    listOf(
                        ToolCall("c1", "t", buildJsonObject { put("param", "1") }),
                        ToolCall("c2", "t", buildJsonObject { put("param", "2") }),
                        ToolCall("c3", "t", buildJsonObject { put("param", "3") }),
                    ),
                ),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Failed)
        val failed = result as ToolTurnResult.Failed
        assertTrue(failed.error is ToolTurnError.CallBudgetExceeded)
    }

    @Test
    fun `round budget exceeded fails turn`() = runTest {
        val tool = FakeTool(
            ToolDefinition(ToolId("t"), "t", "desc", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        )
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(
            registry = registry,
            schemaValidator = FakeValidator(),
            budget = ToolLoopBudget(maxModelRounds = 2),
        )

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c1", "t", buildJsonObject { put("param", "1") }))),
                ModelRoundResponse.ToolCalls(listOf(ToolCall("c2", "t", buildJsonObject { put("param", "2") }))),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Failed)
        val failed = result as ToolTurnResult.Failed
        assertTrue(failed.error is ToolTurnError.RoundBudgetExceeded)
    }

    @Test
    fun `cancellation exception propagates without being swallowed`() = runTest {
        val registry = InMemoryToolRegistry()
        val runner = ToolTurnRunner(registry, FakeValidator())
        val invoker = object : ModelTurnInvoker {
            override suspend fun invokeRound(
                messages: List<ConversationMessage>,
                activeTools: List<ToolDefinition>,
                steps: List<ModelRoundStep>,
                languageTag: String?,
            ): ModelRoundResponse {
                throw CancellationException("Turn cancelled")
            }
        }

        try {
            runner.runTurn(
                messages = listOf(ConversationMessage(ConversationRole.User, "run")),
                turnEpoch = 1,
                languageTag = null,
                modelInvoker = invoker,
            )
            fail("Expected CancellationException to be thrown")
        } catch (expected: CancellationException) {
            assertEquals("Turn cancelled", expected.message)
        }
    }

    @Test
    fun `confirmation required prevents tool execute and returns pending confirmation`() = runTest {
        val sensitiveTool = FakeTool(
            ToolDefinition(
                id = ToolId("delete_file"),
                name = "delete",
                description = "Destructive file delete",
                effect = ToolEffect.Destructive,
                executionKind = ExecutionKind.LocalApi,
                inputSchema = simpleSchema,
            ),
        )
        val registry = InMemoryToolRegistry(listOf(sensitiveTool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val args = buildJsonObject { put("param", "important.txt") }
        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("call_del", "delete", args))),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "delete important.txt")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.ConfirmationRequired)
        val confirmation = result as ToolTurnResult.ConfirmationRequired
        assertEquals("delete_file", confirmation.pending.definition.id.value)
        assertEquals("call_del", confirmation.pending.call.callId)
        assertEquals(0, sensitiveTool.callCount) // Tool must NOT have been executed!

        val token = confirmation.pending.token
        assertNotNull(token)
        assertTrue(token.isValidFor(ToolId("delete_file"), args, 1, System.currentTimeMillis()))
    }

    @Test
    fun `confirmation token allows execution when arguments match`() = runTest {
        val sensitiveTool = FakeTool(
            ToolDefinition(
                id = ToolId("delete_file"),
                name = "delete",
                description = "Destructive file delete",
                effect = ToolEffect.Destructive,
                executionKind = ExecutionKind.LocalApi,
                inputSchema = simpleSchema,
            ),
        )
        val registry = InMemoryToolRegistry(listOf(sensitiveTool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val args = buildJsonObject { put("param", "important.txt") }
        val validToken = ActionConfirmationToken(
            toolId = ToolId("delete_file"),
            canonicalArgumentsHash = computeCanonicalArgumentsHash(args),
            turnEpoch = 1,
            expiresAtMs = System.currentTimeMillis() + 60_000L,
        )

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("call_del", "delete", args))),
                ModelRoundResponse.FinalReply(RayaResponse("Deleted successfully", RayaEmotion.Calm)),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "delete important.txt")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            confirmationToken = validToken,
        )

        assertTrue(result is ToolTurnResult.Completed)
        assertEquals(1, sensitiveTool.callCount)
    }

    @Test
    fun `confirmation token with mismatched args is rejected`() = runTest {
        val sensitiveTool = FakeTool(
            ToolDefinition(
                id = ToolId("delete_file"),
                name = "delete",
                description = "Destructive file delete",
                effect = ToolEffect.Destructive,
                executionKind = ExecutionKind.LocalApi,
                inputSchema = simpleSchema,
            ),
        )
        val registry = InMemoryToolRegistry(listOf(sensitiveTool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val originalArgs = buildJsonObject { put("param", "important.txt") }
        val tamperedArgs = buildJsonObject { put("param", "system32.dll") }

        val tokenForOriginal = ActionConfirmationToken(
            toolId = ToolId("delete_file"),
            canonicalArgumentsHash = computeCanonicalArgumentsHash(originalArgs),
            turnEpoch = 1,
            expiresAtMs = System.currentTimeMillis() + 60_000L,
        )

        assertFalse(tokenForOriginal.isValidFor(ToolId("delete_file"), tamperedArgs, 1, System.currentTimeMillis()))

        val invoker = ScriptedModelInvoker(
            listOf(
                ModelRoundResponse.ToolCalls(listOf(ToolCall("call_del", "delete", tamperedArgs))),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "delete tampered")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            confirmationToken = tokenForOriginal,
        )

        // Must require confirmation again because arguments were tampered!
        assertTrue(result is ToolTurnResult.ConfirmationRequired)
        assertEquals(0, sensitiveTool.callCount)
    }

    @Test
    fun `expired confirmation token is rejected`() {
        val args = buildJsonObject { put("param", "test") }
        val expiredToken = ActionConfirmationToken(
            toolId = ToolId("tool_1"),
            canonicalArgumentsHash = computeCanonicalArgumentsHash(args),
            turnEpoch = 1,
            expiresAtMs = 1000L,
        )

        assertFalse(expiredToken.isValidFor(ToolId("tool_1"), args, 1, nowMs = 2000L))
    }

    @Test
    fun `confirmation token with wrong epoch is rejected`() {
        val args = buildJsonObject { put("param", "test") }
        val token = ActionConfirmationToken(
            toolId = ToolId("tool_1"),
            canonicalArgumentsHash = computeCanonicalArgumentsHash(args),
            turnEpoch = 1,
            expiresAtMs = System.currentTimeMillis() + 60_000L,
        )

        assertFalse(token.isValidFor(ToolId("tool_1"), args, currentTurnEpoch = 2, nowMs = System.currentTimeMillis()))
    }
}
