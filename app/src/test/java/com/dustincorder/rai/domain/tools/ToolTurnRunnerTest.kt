package com.dustincorder.rai.domain.tools

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import com.dustincorder.rai.domain.RayaEmotion
import com.dustincorder.rai.domain.RayaResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
        private val rounds: List<List<ModelRoundStreamEvent>>,
        private val exposedFilter: (List<ToolDefinition>) -> List<ToolDefinition> = { it },
    ) : ModelTurnInvoker {
        var invocationCount = 0
        val historySteps = mutableListOf<List<ModelRoundStep>>()
        val exposedToolsSeen = mutableListOf<List<ToolDefinition>>()

        override fun streamRound(
            messages: List<ConversationMessage>,
            candidateTools: List<ToolDefinition>,
            steps: List<ModelRoundStep>,
            languageTag: String?,
        ): Flow<ModelRoundStreamEvent> = flow {
            historySteps.add(steps.toList())
            val exposed = exposedFilter(candidateTools)
            exposedToolsSeen.add(exposed)
            emit(ModelRoundStreamEvent.ExposedTools(exposed))
            if (invocationCount >= rounds.size) {
                error("No scripted response for round $invocationCount")
            }
            val events = rounds[invocationCount++]
            for (event in events) {
                emit(event)
            }
        }
    }

    @Test
    fun `zero tool calls returns ordinary final response`() = runTest {
        val registry = InMemoryToolRegistry()
        val runner = ToolTurnRunner(registry, FakeValidator())
        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Hello there!", RayaEmotion.Calm, "en-US")),
                ),
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(
                        listOf(ToolCall("call_1", "echo", buildJsonObject { put("param", "apple") })),
                    ),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("You got apple", RayaEmotion.Happy, "en-US")),
                ),
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(
                        listOf(
                            ToolCall("call_a", "tool_a", buildJsonObject { put("param", "1") }),
                            ToolCall("call_b", "tool_b", buildJsonObject { put("param", "2") }),
                        ),
                    ),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Both executed", RayaEmotion.Calm)),
                ),
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c1", "t", buildJsonObject { put("param", "step1") }))),
                ),
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c2", "t", buildJsonObject { put("param", "step2") }))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("All done", RayaEmotion.Calm)),
                ),
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c_unknown", "non_existent_tool", buildJsonObject {}))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("I cannot find that tool", RayaEmotion.Confused)),
                ),
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
        assertEquals("PolicyDenied", feedback.result["error_kind"]?.jsonPrimitive?.content)
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c_disabled", "disabled", buildJsonObject {}))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Tool is disabled", RayaEmotion.Calm)),
                ),
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c1", "t", buildJsonObject { put("wrong", 123) }))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Schema invalid", RayaEmotion.Thinking)),
                ),
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c_fail", "fail", buildJsonObject { put("param", "x") }))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Tool failed to execute", RayaEmotion.Sad)),
                ),
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c_hang", "hang", buildJsonObject { put("param", "x") }))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Operation timed out", RayaEmotion.Annoyed)),
                ),
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(
                        listOf(
                            ToolCall("c1", "t", buildJsonObject { put("param", "1") }),
                            ToolCall("c2", "t", buildJsonObject { put("param", "2") }),
                            ToolCall("c3", "t", buildJsonObject { put("param", "3") }),
                        ),
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c1", "t", buildJsonObject { put("param", "1") }))),
                ),
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c2", "t", buildJsonObject { put("param", "2") }))),
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
        assertTrue(failed.error is ToolTurnError.RoundBudgetExceeded)
    }

    @Test
    fun `cancellation exception propagates without being swallowed`() = runTest {
        val registry = InMemoryToolRegistry()
        val runner = ToolTurnRunner(registry, FakeValidator())
        val invoker = object : ModelTurnInvoker {
            override fun streamRound(
                messages: List<ConversationMessage>,
                candidateTools: List<ToolDefinition>,
                steps: List<ModelRoundStep>,
                languageTag: String?,
            ): Flow<ModelRoundStreamEvent> = flow {
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
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("call_del", "delete", args))),
                ),
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
        assertEquals(0, sensitiveTool.callCount)

        val token = confirmation.pending.token
        assertNotNull(token)
        assertTrue(token.isValidFor(ToolId("delete_file"), ExecutionKind.LocalApi, args, 1, 0L))
    }

    @Test
    fun `batch continuation test A - safe then confirmation then safe`() = runTest {
        val callOrder = mutableListOf<String>()
        val safe1 = FakeTool(
            ToolDefinition(ToolId("safe1"), "safe1", "safe", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        ) {
            callOrder.add("safe1")
            ToolResult.Success(buildJsonObject { put("r", "1") })
        }
        val sensitive = FakeTool(
            ToolDefinition(ToolId("sens"), "sens", "sensitive", ToolEffect.Destructive, ExecutionKind.LocalApi, simpleSchema),
        ) {
            callOrder.add("sens")
            ToolResult.Success(buildJsonObject { put("r", "2") })
        }
        val safe2 = FakeTool(
            ToolDefinition(ToolId("safe2"), "safe2", "safe", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        ) {
            callOrder.add("safe2")
            ToolResult.Success(buildJsonObject { put("r", "3") })
        }

        val registry = InMemoryToolRegistry(listOf(safe1, sensitive, safe2))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.ToolCalls(
                        listOf(
                            ToolCall("c1", "safe1", buildJsonObject { put("param", "a") }),
                            ToolCall("c2", "sens", buildJsonObject { put("param", "b") }),
                            ToolCall("c3", "safe2", buildJsonObject { put("param", "c") }),
                        ),
                    ),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("All three completed", RayaEmotion.Calm)),
                ),
            ),
        )

        // 1. Initial turn pauses on sens (c2)
        val initialResult = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run all")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(initialResult is ToolTurnResult.ConfirmationRequired)
        val pending = (initialResult as ToolTurnResult.ConfirmationRequired).pending
        assertEquals("sens", pending.call.toolName)
        assertEquals(listOf("safe1"), callOrder)
        assertEquals(1, pending.remainingCallsInBatch.size)
        assertEquals("safe2", pending.remainingCallsInBatch[0].toolName)
        assertEquals(1, pending.currentBatchFeedbacks.size)
        assertEquals("c1", pending.currentBatchFeedbacks[0].callId)

        // 2. Resume with approval token
        val resumeResult = runner.resumeConfirmedTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run all")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            pending = pending,
            confirmationToken = pending.token,
        )

        assertTrue(resumeResult is ToolTurnResult.Completed)
        assertEquals(listOf("safe1", "sens", "safe2"), callOrder)
        val completed = resumeResult as ToolTurnResult.Completed
        assertEquals(3, completed.executedCalls.size)

        // 3. Provider history before round 2 has exactly one feedback per call ID
        val round2Steps = invoker.historySteps[1]
        assertEquals(4, round2Steps.size)
        val feedbackIds = round2Steps.filterIsInstance<ModelRoundStep.ToolExecutionFeedback>().map { it.callId }
        assertEquals(listOf("c1", "c2", "c3"), feedbackIds)
    }

    @Test
    fun `batch continuation test B - confirmation then safe`() = runTest {
        val callOrder = mutableListOf<String>()
        val sens = FakeTool(
            ToolDefinition(ToolId("sens"), "sens", "desc", ToolEffect.Destructive, ExecutionKind.LocalApi, simpleSchema),
        ) {
            callOrder.add("sens")
            ToolResult.Success(buildJsonObject {})
        }
        val safe = FakeTool(
            ToolDefinition(ToolId("safe"), "safe", "desc", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        ) {
            callOrder.add("safe")
            ToolResult.Success(buildJsonObject {})
        }
        val registry = InMemoryToolRegistry(listOf(sens, safe))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.ToolCalls(
                        listOf(
                            ToolCall("c1", "sens", buildJsonObject { put("param", "1") }),
                            ToolCall("c2", "safe", buildJsonObject { put("param", "2") }),
                        ),
                    ),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Done", RayaEmotion.Calm)),
                ),
            ),
        )

        val result1 = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "go")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result1 is ToolTurnResult.ConfirmationRequired)
        val pending = (result1 as ToolTurnResult.ConfirmationRequired).pending
        assertEquals("sens", pending.call.toolName)
        assertEquals(emptyList<String>(), callOrder)

        val result2 = runner.resumeConfirmedTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "go")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            pending = pending,
            confirmationToken = pending.token,
        )

        assertTrue(result2 is ToolTurnResult.Completed)
        assertEquals(listOf("sens", "safe"), callOrder)
    }

    @Test
    fun `batch continuation test C - safe then confirmation then confirmation`() = runTest {
        val callOrder = mutableListOf<String>()
        val safe = FakeTool(
            ToolDefinition(ToolId("safe"), "safe", "desc", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        ) {
            callOrder.add("safe")
            ToolResult.Success(buildJsonObject {})
        }
        val sens1 = FakeTool(
            ToolDefinition(ToolId("sens1"), "sens1", "desc", ToolEffect.Destructive, ExecutionKind.LocalApi, simpleSchema),
        ) {
            callOrder.add("sens1")
            ToolResult.Success(buildJsonObject {})
        }
        val sens2 = FakeTool(
            ToolDefinition(ToolId("sens2"), "sens2", "desc", ToolEffect.Destructive, ExecutionKind.LocalApi, simpleSchema),
        ) {
            callOrder.add("sens2")
            ToolResult.Success(buildJsonObject {})
        }
        val registry = InMemoryToolRegistry(listOf(safe, sens1, sens2))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.ToolCalls(
                        listOf(
                            ToolCall("c1", "safe", buildJsonObject { put("param", "1") }),
                            ToolCall("c2", "sens1", buildJsonObject { put("param", "2") }),
                            ToolCall("c3", "sens2", buildJsonObject { put("param", "3") }),
                        ),
                    ),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Done", RayaEmotion.Calm)),
                ),
            ),
        )

        val res1 = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )
        assertTrue(res1 is ToolTurnResult.ConfirmationRequired)
        val pending1 = (res1 as ToolTurnResult.ConfirmationRequired).pending
        assertEquals("sens1", pending1.call.toolName)
        assertEquals(listOf("safe"), callOrder)

        val res2 = runner.resumeConfirmedTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            pending = pending1,
            confirmationToken = pending1.token,
        )
        assertTrue(res2 is ToolTurnResult.ConfirmationRequired)
        val pending2 = (res2 as ToolTurnResult.ConfirmationRequired).pending
        assertEquals("sens2", pending2.call.toolName)
        assertEquals(listOf("safe", "sens1"), callOrder)

        val res3 = runner.resumeConfirmedTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            pending = pending2,
            confirmationToken = pending2.token,
        )
        assertTrue(res3 is ToolTurnResult.Completed)
        assertEquals(listOf("safe", "sens1", "sens2"), callOrder)
    }

    @Test
    fun `confirmation token is strictly single-use`() = runTest {
        val sens = FakeTool(
            ToolDefinition(ToolId("sens"), "sens", "desc", ToolEffect.Destructive, ExecutionKind.LocalApi, simpleSchema),
        )
        val registry = InMemoryToolRegistry(listOf(sens))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c1", "sens", buildJsonObject { put("param", "x") }))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Done", RayaEmotion.Calm)),
                ),
            ),
        )

        val res1 = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "go")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )
        assertTrue(res1 is ToolTurnResult.ConfirmationRequired)
        val pending = (res1 as ToolTurnResult.ConfirmationRequired).pending
        val token = pending.token

        val res2 = runner.resumeConfirmedTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "go")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            pending = pending,
            confirmationToken = token,
        )
        assertTrue(res2 is ToolTurnResult.Completed)
        assertEquals(1, sens.callCount)

        val replayRes = runner.resumeConfirmedTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "go")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            pending = pending,
            confirmationToken = token,
        )
        assertTrue(replayRes is ToolTurnResult.Failed)
        assertEquals(1, sens.callCount)
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
            executionKind = ExecutionKind.LocalApi,
            canonicalArgumentsHash = computeCanonicalArgumentsHash(originalArgs),
            turnEpoch = 1,
            expiresAtMs = 60_000L,
        )

        assertFalse(tokenForOriginal.isValidFor(ToolId("delete_file"), ExecutionKind.LocalApi, tamperedArgs, 1, 0L))

        val pending = PendingToolConfirmation(
            token = tokenForOriginal,
            call = ToolCall("call_del", "delete", tamperedArgs),
            definition = sensitiveTool.definition,
        )

        val result = runner.resumeConfirmedTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "delete tampered")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = ScriptedModelInvoker(emptyList()),
            pending = pending,
            confirmationToken = tokenForOriginal,
        )

        assertTrue(result is ToolTurnResult.Failed)
        assertEquals(0, sensitiveTool.callCount)
    }

    @Test
    fun `streaming deltas flow when active registry and model returns ordinary text`() = runTest {
        val tool = FakeTool(
            ToolDefinition(ToolId("t"), "t", "desc", ToolEffect.ReadOnly, ExecutionKind.LocalApi, simpleSchema),
        )
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val streamedDeltas = mutableListOf<String>()
        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.TextDelta("Hello "),
                    ModelRoundStreamEvent.TextDelta("world!"),
                    ModelRoundStreamEvent.Completed(RayaResponse("Hello world!", RayaEmotion.Calm)),
                ),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "hi")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            onTextDelta = { streamedDeltas.add(it) },
        )

        assertTrue(result is ToolTurnResult.Completed)
        assertEquals(listOf("Hello ", "world!"), streamedDeltas)
        assertEquals("Hello world!", (result as ToolTurnResult.Completed).response.text)
        assertEquals(0, tool.callCount)
    }

    @Test
    fun `all requested tool calls count against total call budget`() = runTest {
        val registry = InMemoryToolRegistry()
        val runner = ToolTurnRunner(registry, FakeValidator(), budget = ToolLoopBudget(maxTotalToolCalls = 2))

        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.ToolCalls(
                        listOf(
                            ToolCall("c1", "unknown1", buildJsonObject {}),
                            ToolCall("c2", "unknown2", buildJsonObject {}),
                            ToolCall("c3", "unknown3", buildJsonObject {}),
                        ),
                    ),
                ),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "call 3")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Failed)
        val failed = result as ToolTurnResult.Failed
        assertTrue(failed.error is ToolTurnError.CallBudgetExceeded)
    }

    @Test
    fun `output schema validation succeeds for valid result`() = runTest {
        val outputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("result_code", buildJsonObject { put("type", "integer") })
            })
            put("required", buildJsonArray { add(JsonPrimitive("result_code")) })
        }
        val toolDef = ToolDefinition(
            id = ToolId("calc"),
            name = "calc",
            description = "desc",
            effect = ToolEffect.ReadOnly,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = simpleSchema,
            outputSchema = outputSchema,
        )
        val tool = FakeTool(toolDef) {
            ToolResult.Success(buildJsonObject { put("result_code", 100) })
        }
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, FakeValidator(valid = true))

        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c1", "calc", buildJsonObject { put("param", "x") }))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Done", RayaEmotion.Calm)),
                ),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "calc")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        val feedback = invoker.historySteps[1][1] as ModelRoundStep.ToolExecutionFeedback
        assertEquals(100, feedback.result["result_code"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `output schema validation fails and converts to typed execution error`() = runTest {
        val outputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("result_code", buildJsonObject { put("type", "integer") })
            })
        }
        val toolDef = ToolDefinition(
            id = ToolId("calc"),
            name = "calc",
            description = "desc",
            effect = ToolEffect.ReadOnly,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = simpleSchema,
            outputSchema = outputSchema,
        )
        val tool = FakeTool(toolDef) {
            ToolResult.Success(buildJsonObject { put("secret_key", "secret") })
        }
        val validator = object : JsonSchemaValidator {
            override fun validate(schema: kotlinx.serialization.json.JsonObject, instance: kotlinx.serialization.json.JsonElement): SchemaValidationResult {
                if (schema == outputSchema) {
                    return SchemaValidationResult.Invalid(listOf(SchemaValidationError("/result_code", "Missing required field")))
                }
                return SchemaValidationResult.Valid
            }
        }
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, validator)

        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c1", "calc", buildJsonObject { put("param", "x") }))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Error handled", RayaEmotion.Calm)),
                ),
            ),
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "calc")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        val feedback = invoker.historySteps[1][1] as ModelRoundStep.ToolExecutionFeedback
        assertEquals("error", feedback.result["status"]?.jsonPrimitive?.content)
        assertEquals("ExecutionFailed", feedback.result["error_kind"]?.jsonPrimitive?.content)
        assertFalse("Secret data must not be sent to model", feedback.result.containsKey("secret_key"))
    }

    @Test
    fun `unsupported schema tool is omitted and fails closed if called`() = runTest {
        val toolDef = ToolDefinition(
            id = ToolId("omitted_tool"),
            name = "omitted",
            description = "desc",
            effect = ToolEffect.ReadOnly,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = simpleSchema,
        )
        val tool = FakeTool(toolDef)
        val registry = InMemoryToolRegistry(listOf(tool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val invoker = ScriptedModelInvoker(
            rounds = listOf(
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c1", "omitted", buildJsonObject { put("param", "x") }))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Refused", RayaEmotion.Calm)),
                ),
            ),
            exposedFilter = { emptyList() },
        )

        val result = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "call")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )

        assertTrue(result is ToolTurnResult.Completed)
        assertEquals(0, tool.callCount)
        val feedback = invoker.historySteps[1][1] as ModelRoundStep.ToolExecutionFeedback
        assertEquals("PolicyDenied", feedback.result["error_kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `expired confirmation token is rejected`() {
        val args = buildJsonObject { put("param", "test") }
        val expiredToken = ActionConfirmationToken(
            toolId = ToolId("tool_1"),
            executionKind = ExecutionKind.LocalApi,
            canonicalArgumentsHash = computeCanonicalArgumentsHash(args),
            turnEpoch = 1,
            expiresAtMs = 1000L,
        )

        assertFalse(expiredToken.isValidFor(ToolId("tool_1"), ExecutionKind.LocalApi, args, 1, nowMonotonicMs = 2000L))
    }

    @Test
    fun `confirmation token with wrong epoch is rejected`() {
        val args = buildJsonObject { put("param", "test") }
        val token = ActionConfirmationToken(
            toolId = ToolId("tool_1"),
            executionKind = ExecutionKind.LocalApi,
            canonicalArgumentsHash = computeCanonicalArgumentsHash(args),
            turnEpoch = 1,
            expiresAtMs = 60_000L,
        )

        assertFalse(token.isValidFor(ToolId("tool_1"), ExecutionKind.LocalApi, args, currentTurnEpoch = 2, nowMonotonicMs = 1000L))
    }

    @Test
    fun `confirmation token with mismatched execution kind is rejected`() {
        val args = buildJsonObject { put("param", "test") }
        val token = ActionConfirmationToken(
            toolId = ToolId("tool_1"),
            executionKind = ExecutionKind.LocalApi,
            canonicalArgumentsHash = computeCanonicalArgumentsHash(args),
            turnEpoch = 1,
            expiresAtMs = 60_000L,
        )

        assertFalse(token.isValidFor(ToolId("tool_1"), ExecutionKind.RemoteMcp("test-server"), args, currentTurnEpoch = 1, nowMonotonicMs = 1000L))
    }

    @Test
    fun `tool definition changed before confirmation approval fails closed`() = runTest {
        val originalDef = ToolDefinition(
            id = ToolId("modify_system"),
            name = "modify",
            description = "Original modify tool",
            effect = ToolEffect.Destructive,
            executionKind = ExecutionKind.LocalApi,
            inputSchema = simpleSchema,
        )
        val originalTool = FakeTool(originalDef)
        val registry = InMemoryToolRegistry(listOf(originalTool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val args = buildJsonObject { put("param", "val1") }
        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("call_1", "modify", args))),
                ),
            ),
        )

        val turnResult = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run modify")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )
        assertTrue(turnResult is ToolTurnResult.ConfirmationRequired)
        val pending = (turnResult as ToolTurnResult.ConfirmationRequired).pending

        // Attacker or dynamic system replaces tool in registry with changed definition (e.g. executionKind changed)
        val changedDef = ToolDefinition(
            id = ToolId("modify_system"),
            name = "modify",
            description = "Changed modify tool",
            effect = ToolEffect.Destructive,
            executionKind = ExecutionKind.AndroidIntent, // changed!
            inputSchema = simpleSchema,
        )
        registry.register(FakeTool(changedDef))

        val resumeResult = runner.resumeConfirmedTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run modify")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            pending = pending,
            confirmationToken = pending.token,
        )

        assertTrue(resumeResult is ToolTurnResult.Failed)
        val failed = resumeResult as ToolTurnResult.Failed
        assertTrue(failed.error is ToolTurnError.ModelError && (failed.error as ToolTurnError.ModelError).message.contains("определение инструмента изменилось"))
        assertEquals(0, originalTool.callCount)
    }

    @Test
    fun `single use confirmation token executes once and replay fails closed`() = runTest {
        val sensitiveTool = FakeTool(
            ToolDefinition(
                id = ToolId("action_1"),
                name = "action",
                description = "Sensitive action",
                effect = ToolEffect.Destructive,
                executionKind = ExecutionKind.LocalApi,
                inputSchema = simpleSchema,
            ),
        )
        val registry = InMemoryToolRegistry(listOf(sensitiveTool))
        val runner = ToolTurnRunner(registry, FakeValidator())

        val args = buildJsonObject { put("param", "v") }
        val invoker = ScriptedModelInvoker(
            listOf(
                listOf(
                    ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c1", "action", args))),
                ),
                listOf(
                    ModelRoundStreamEvent.Completed(RayaResponse("Done", RayaEmotion.Calm)),
                ),
            ),
        )

        val turn1 = runner.runTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
        )
        assertTrue(turn1 is ToolTurnResult.ConfirmationRequired)
        val pending = (turn1 as ToolTurnResult.ConfirmationRequired).pending

        // First resume: succeeds and executes tool once
        val resume1 = runner.resumeConfirmedTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            pending = pending,
            confirmationToken = pending.token,
        )
        assertTrue(resume1 is ToolTurnResult.Completed)
        assertEquals(1, sensitiveTool.callCount)

        // Second resume (replay attempt with same confirmation): fails closed
        val replayResult = runner.resumeConfirmedTurn(
            messages = listOf(ConversationMessage(ConversationRole.User, "run")),
            turnEpoch = 1,
            languageTag = null,
            modelInvoker = invoker,
            pending = pending,
            confirmationToken = pending.token,
        )
        assertTrue(replayResult is ToolTurnResult.Failed)
        val failed = replayResult as ToolTurnResult.Failed
        assertTrue(failed.error is ToolTurnError.ModelError && (failed.error as ToolTurnError.ModelError).message.contains("уже был использован"))
        assertEquals(1, sensitiveTool.callCount) // tool was NOT executed again
    }

    @Test
    fun `later unrelated confirmation works normally after previous confirmation`() = runTest {
        val toolA = FakeTool(
            ToolDefinition(ToolId("t_a"), "t_a", "desc", ToolEffect.Destructive, ExecutionKind.LocalApi, simpleSchema),
        )
        val toolB = FakeTool(
            ToolDefinition(ToolId("t_b"), "t_b", "desc", ToolEffect.Destructive, ExecutionKind.LocalApi, simpleSchema),
        )
        val registry = InMemoryToolRegistry(listOf(toolA, toolB))
        val runner = ToolTurnRunner(registry, FakeValidator())

        // Turn A
        val invokerA = ScriptedModelInvoker(
            listOf(
                listOf(ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c_a", "t_a", buildJsonObject { put("param", "1") })))),
                listOf(ModelRoundStreamEvent.Completed(RayaResponse("Done A", RayaEmotion.Calm))),
            ),
        )
        val resultA = runner.runTurn(listOf(ConversationMessage(ConversationRole.User, "A")), 1, null, invokerA)
        val pendingA = (resultA as ToolTurnResult.ConfirmationRequired).pending
        val resumeA = runner.resumeConfirmedTurn(listOf(ConversationMessage(ConversationRole.User, "A")), 1, null, invokerA, pendingA, pendingA.token)
        assertTrue(resumeA is ToolTurnResult.Completed)
        assertEquals(1, toolA.callCount)

        // Turn B (unrelated later confirmation)
        val invokerB = ScriptedModelInvoker(
            listOf(
                listOf(ModelRoundStreamEvent.ToolCalls(listOf(ToolCall("c_b", "t_b", buildJsonObject { put("param", "2") })))),
                listOf(ModelRoundStreamEvent.Completed(RayaResponse("Done B", RayaEmotion.Calm))),
            ),
        )
        val resultB = runner.runTurn(listOf(ConversationMessage(ConversationRole.User, "B")), 2, null, invokerB)
        val pendingB = (resultB as ToolTurnResult.ConfirmationRequired).pending
        val resumeB = runner.resumeConfirmedTurn(listOf(ConversationMessage(ConversationRole.User, "B")), 2, null, invokerB, pendingB, pendingB.token)
        assertTrue(resumeB is ToolTurnResult.Completed)
        assertEquals(1, toolB.callCount)
    }
}
