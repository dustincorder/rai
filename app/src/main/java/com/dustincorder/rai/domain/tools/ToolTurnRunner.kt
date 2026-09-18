package com.dustincorder.rai.domain.tools

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.RayaResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

data class ToolLoopBudget(
    val maxModelRounds: Int = 3,
    val maxTotalToolCalls: Int = 5,
    val perToolTimeoutMs: Long = 10_000L,
    val wholeTurnTimeoutMs: Long = 45_000L,
    val maxResultBytes: Int = 16_384,
)

sealed interface ToolTurnResult {
    data class Completed(
        val response: RayaResponse,
        val executedCalls: List<ExecutedToolCallSnapshot> = emptyList(),
    ) : ToolTurnResult

    data class ConfirmationRequired(
        val pending: PendingToolConfirmation,
    ) : ToolTurnResult

    data class Failed(val error: ToolTurnError) : ToolTurnResult
}

@Serializable
data class ExecutedToolCallSnapshot(
    val callId: String,
    val toolId: ToolId,
    val toolName: String,
    val success: Boolean,
    val timestamp: Long,
)

data class PendingToolConfirmation(
    val token: ActionConfirmationToken,
    val call: ToolCall,
    val definition: ToolDefinition,
    val steps: List<ModelRoundStep> = emptyList(),
    val executedCalls: List<ExecutedToolCallSnapshot> = emptyList(),
    val round: Int = 1,
    val remainingCallsInBatch: List<ToolCall> = emptyList(),
    val currentBatchFeedbacks: List<ModelRoundStep.ToolExecutionFeedback> = emptyList(),
    val totalCallsRequested: Int = 0,
)

sealed interface ToolTurnError {
    data class RoundBudgetExceeded(val roundsRun: Int) : ToolTurnError
    data class CallBudgetExceeded(val callsAttempted: Int) : ToolTurnError
    data class TurnTimeout(val elapsedMs: Long) : ToolTurnError
    data class ModelError(val message: String) : ToolTurnError
}

class ToolTurnRunner(
    private val registry: ToolRegistry,
    private val schemaValidator: JsonSchemaValidator,
    private val securityPolicy: ToolSecurityPolicy = DefaultToolSecurityPolicy(),
    private val projector: ToolResultProjector = DefaultToolResultProjector(),
    private val budget: ToolLoopBudget = ToolLoopBudget(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val consumedTokenIds = ConcurrentHashMap.newKeySet<String>()

    suspend fun runTurn(
        messages: List<ConversationMessage>,
        turnEpoch: Int,
        languageTag: String?,
        modelInvoker: ModelTurnInvoker,
        onTextDelta: suspend (String) -> Unit = {},
    ): ToolTurnResult {
        val startedAt = now()
        val steps = mutableListOf<ModelRoundStep>()
        val executedSnapshots = mutableListOf<ExecutedToolCallSnapshot>()
        var round = 0
        var totalCallsRequested = 0

        try {
            return withTimeout(budget.wholeTurnTimeoutMs) {
                while (round < budget.maxModelRounds) {
                    currentCoroutineContext().ensureActive()
                    round++

                    val activeTools = registry.activeTools().map { it.definition }
                    val exposedTools = modelInvoker.filterExposedTools(activeTools)

                    var stepCompletedResponse: RayaResponse? = null
                    var stepToolCalls: List<ToolCall>? = null

                    try {
                        modelInvoker.streamRound(messages, exposedTools, steps, languageTag).collect { event ->
                            when (event) {
                                is ModelRoundStreamEvent.TextDelta -> {
                                    onTextDelta(event.text)
                                }
                                is ModelRoundStreamEvent.ToolCalls -> {
                                    stepToolCalls = event.calls
                                }
                                is ModelRoundStreamEvent.Completed -> {
                                    stepCompletedResponse = event.response
                                }
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        return@withTimeout ToolTurnResult.Failed(
                            ToolTurnError.ModelError(failure.message ?: "Model invocation failed")
                        )
                    }

                    if (stepCompletedResponse != null && stepToolCalls.isNullOrEmpty()) {
                        return@withTimeout ToolTurnResult.Completed(stepCompletedResponse!!, executedSnapshots)
                    }

                    val calls = stepToolCalls.orEmpty()
                    if (calls.isEmpty()) {
                        return@withTimeout ToolTurnResult.Failed(
                            ToolTurnError.ModelError("Model returned empty tool calls list without reply")
                        )
                    }

                    val duplicateCallId = calls.groupBy { it.callId }.filter { it.value.size > 1 }.keys.firstOrNull()
                    if (duplicateCallId != null) {
                        return@withTimeout ToolTurnResult.Failed(
                            ToolTurnError.ModelError("Обнаружен дубликат callId '$duplicateCallId' в одном раунде модели.")
                        )
                    }

                    steps.add(ModelRoundStep.AssistantToolCalls(calls))
                    val batchFeedbacks = mutableListOf<ModelRoundStep.ToolExecutionFeedback>()

                    for (index in calls.indices) {
                        currentCoroutineContext().ensureActive()
                        val call = calls[index]
                        totalCallsRequested++
                        if (totalCallsRequested > budget.maxTotalToolCalls) {
                            return@withTimeout ToolTurnResult.Failed(
                                ToolTurnError.CallBudgetExceeded(totalCallsRequested)
                            )
                        }

                        val executionOutcome = processCall(
                            call = call,
                            exposedTools = exposedTools,
                            turnEpoch = turnEpoch,
                            executedSnapshots = executedSnapshots,
                        )

                        when (executionOutcome) {
                            is CallProcessOutcome.Feedback -> {
                                batchFeedbacks.add(executionOutcome.feedback)
                            }
                            is CallProcessOutcome.ConfirmationNeeded -> {
                                return@withTimeout ToolTurnResult.ConfirmationRequired(
                                    PendingToolConfirmation(
                                        token = executionOutcome.token,
                                        call = call,
                                        definition = executionOutcome.definition,
                                        steps = steps.toList(),
                                        executedCalls = executedSnapshots.toList(),
                                        round = round,
                                        remainingCallsInBatch = calls.subList(index + 1, calls.size),
                                        currentBatchFeedbacks = batchFeedbacks.toList(),
                                        totalCallsRequested = totalCallsRequested,
                                    )
                                )
                            }
                        }
                    }

                    steps.addAll(batchFeedbacks)
                }

                ToolTurnResult.Failed(ToolTurnError.RoundBudgetExceeded(round))
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            return ToolTurnResult.Failed(ToolTurnError.TurnTimeout(now() - startedAt))
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    suspend fun resumeConfirmedTurn(
        messages: List<ConversationMessage>,
        turnEpoch: Int,
        languageTag: String?,
        modelInvoker: ModelTurnInvoker,
        pending: PendingToolConfirmation,
        confirmationToken: ActionConfirmationToken,
        onTextDelta: suspend (String) -> Unit = {},
    ): ToolTurnResult {
        val startedAt = now()
        val steps = pending.steps.toMutableList()
        val executedSnapshots = pending.executedCalls.toMutableList()
        var round = pending.round
        var totalCallsRequested = pending.totalCallsRequested

        if (!consumedTokenIds.add(confirmationToken.tokenId)) {
            return ToolTurnResult.Failed(ToolTurnError.ModelError("Действие отклонено: токен подтверждения уже был использован."))
        }

        if (!confirmationToken.isValidFor(pending.definition.id, pending.call.arguments, turnEpoch, now())) {
            return ToolTurnResult.Failed(ToolTurnError.ModelError("Действие отклонено: недействительный токен подтверждения."))
        }

        try {
            return withTimeout(budget.wholeTurnTimeoutMs) {
                val tool = registry.get(pending.call.toolName) ?: return@withTimeout ToolTurnResult.Failed(
                    ToolTurnError.ModelError("Инструмент '${pending.call.toolName}' не найден в реестре.")
                )

                // Execute the confirmed tool
                val confirmedResult = executeToolWithValidation(tool, pending.call)
                executedSnapshots.add(
                    ExecutedToolCallSnapshot(
                        callId = pending.call.callId,
                        toolId = tool.definition.id,
                        toolName = tool.definition.name,
                        success = confirmedResult is ToolResult.Success,
                        timestamp = now(),
                    )
                )
                val confirmedProjected = projector.project(confirmedResult, budget.maxResultBytes)
                val batchFeedbacks = pending.currentBatchFeedbacks.toMutableList()
                batchFeedbacks.add(
                    ModelRoundStep.ToolExecutionFeedback(
                        pending.call.callId,
                        pending.call.toolName,
                        confirmedProjected,
                    )
                )

                val activeTools = registry.activeTools().map { it.definition }
                val exposedTools = modelInvoker.filterExposedTools(activeTools)

                // Process any remaining calls from the paused batch
                val remaining = pending.remainingCallsInBatch
                for (index in remaining.indices) {
                    currentCoroutineContext().ensureActive()
                    val call = remaining[index]
                    totalCallsRequested++
                    if (totalCallsRequested > budget.maxTotalToolCalls) {
                        return@withTimeout ToolTurnResult.Failed(
                            ToolTurnError.CallBudgetExceeded(totalCallsRequested)
                        )
                    }

                    val outcome = processCall(
                        call = call,
                        exposedTools = exposedTools,
                        turnEpoch = turnEpoch,
                        executedSnapshots = executedSnapshots,
                    )

                    when (outcome) {
                        is CallProcessOutcome.Feedback -> {
                            batchFeedbacks.add(outcome.feedback)
                        }
                        is CallProcessOutcome.ConfirmationNeeded -> {
                            return@withTimeout ToolTurnResult.ConfirmationRequired(
                                PendingToolConfirmation(
                                    token = outcome.token,
                                    call = call,
                                    definition = outcome.definition,
                                    steps = steps.toList(),
                                    executedCalls = executedSnapshots.toList(),
                                    round = round,
                                    remainingCallsInBatch = remaining.subList(index + 1, remaining.size),
                                    currentBatchFeedbacks = batchFeedbacks.toList(),
                                    totalCallsRequested = totalCallsRequested,
                                )
                            )
                        }
                    }
                }

                steps.addAll(batchFeedbacks)

                // Continue multi-round loop
                while (round < budget.maxModelRounds) {
                    currentCoroutineContext().ensureActive()
                    round++

                    val currentActive = registry.activeTools().map { it.definition }
                    val currentExposed = modelInvoker.filterExposedTools(currentActive)

                    var stepCompletedResponse: RayaResponse? = null
                    var stepToolCalls: List<ToolCall>? = null

                    try {
                        modelInvoker.streamRound(messages, currentExposed, steps, languageTag).collect { event ->
                            when (event) {
                                is ModelRoundStreamEvent.TextDelta -> {
                                    onTextDelta(event.text)
                                }
                                is ModelRoundStreamEvent.ToolCalls -> {
                                    stepToolCalls = event.calls
                                }
                                is ModelRoundStreamEvent.Completed -> {
                                    stepCompletedResponse = event.response
                                }
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        return@withTimeout ToolTurnResult.Failed(
                            ToolTurnError.ModelError(failure.message ?: "Model invocation failed")
                        )
                    }

                    if (stepCompletedResponse != null && stepToolCalls.isNullOrEmpty()) {
                        return@withTimeout ToolTurnResult.Completed(stepCompletedResponse!!, executedSnapshots)
                    }

                    val calls = stepToolCalls.orEmpty()
                    if (calls.isEmpty()) {
                        return@withTimeout ToolTurnResult.Failed(
                            ToolTurnError.ModelError("Model returned empty tool calls list without reply")
                        )
                    }

                    val duplicateCallId = calls.groupBy { it.callId }.filter { it.value.size > 1 }.keys.firstOrNull()
                    if (duplicateCallId != null) {
                        return@withTimeout ToolTurnResult.Failed(
                            ToolTurnError.ModelError("Обнаружен дубликат callId '$duplicateCallId' в одном раунде модели.")
                        )
                    }

                    steps.add(ModelRoundStep.AssistantToolCalls(calls))
                    val nextBatchFeedbacks = mutableListOf<ModelRoundStep.ToolExecutionFeedback>()

                    for (index in calls.indices) {
                        currentCoroutineContext().ensureActive()
                        val call = calls[index]
                        totalCallsRequested++
                        if (totalCallsRequested > budget.maxTotalToolCalls) {
                            return@withTimeout ToolTurnResult.Failed(
                                ToolTurnError.CallBudgetExceeded(totalCallsRequested)
                            )
                        }

                        val executionOutcome = processCall(
                            call = call,
                            exposedTools = currentExposed,
                            turnEpoch = turnEpoch,
                            executedSnapshots = executedSnapshots,
                        )

                        when (executionOutcome) {
                            is CallProcessOutcome.Feedback -> {
                                nextBatchFeedbacks.add(executionOutcome.feedback)
                            }
                            is CallProcessOutcome.ConfirmationNeeded -> {
                                return@withTimeout ToolTurnResult.ConfirmationRequired(
                                    PendingToolConfirmation(
                                        token = executionOutcome.token,
                                        call = call,
                                        definition = executionOutcome.definition,
                                        steps = steps.toList(),
                                        executedCalls = executedSnapshots.toList(),
                                        round = round,
                                        remainingCallsInBatch = calls.subList(index + 1, calls.size),
                                        currentBatchFeedbacks = nextBatchFeedbacks.toList(),
                                        totalCallsRequested = totalCallsRequested,
                                    )
                                )
                            }
                        }
                    }

                    steps.addAll(nextBatchFeedbacks)
                }

                ToolTurnResult.Failed(ToolTurnError.RoundBudgetExceeded(round))
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            return ToolTurnResult.Failed(ToolTurnError.TurnTimeout(now() - startedAt))
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    private sealed interface CallProcessOutcome {
        data class Feedback(val feedback: ModelRoundStep.ToolExecutionFeedback) : CallProcessOutcome
        data class ConfirmationNeeded(val token: ActionConfirmationToken, val definition: ToolDefinition) : CallProcessOutcome
    }

    private suspend fun processCall(
        call: ToolCall,
        exposedTools: List<ToolDefinition>,
        turnEpoch: Int,
        executedSnapshots: MutableList<ExecutedToolCallSnapshot>,
    ): CallProcessOutcome {
        val exposed = exposedTools.firstOrNull { it.name == call.toolName }
        if (exposed == null) {
            val errorResult = ToolResult.Error(
                ToolErrorKind.PolicyDenied,
                "Инструмент '${call.toolName}' не был предоставлен модели в этом раунде."
            )
            val projected = projector.project(errorResult, budget.maxResultBytes)
            return CallProcessOutcome.Feedback(
                ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected)
            )
        }

        val tool = registry.get(call.toolName)
        if (tool == null) {
            val errorResult = ToolResult.Error(
                ToolErrorKind.PolicyDenied,
                "Инструмент '${call.toolName}' не найден в реестре."
            )
            val projected = projector.project(errorResult, budget.maxResultBytes)
            return CallProcessOutcome.Feedback(
                ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected)
            )
        }

        if (!tool.definition.enabled) {
            val errorResult = ToolResult.Error(
                ToolErrorKind.PolicyDenied,
                "Инструмент '${call.toolName}' отключён политикой."
            )
            val projected = projector.project(errorResult, budget.maxResultBytes)
            return CallProcessOutcome.Feedback(
                ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected)
            )
        }

        val validation = schemaValidator.validate(tool.definition.inputSchema, call.arguments)
        if (validation is SchemaValidationResult.Invalid) {
            val errorsSummary = validation.errors.joinToString("; ") { "${it.path}: ${it.error}" }
            val errorResult = ToolResult.Error(
                ToolErrorKind.ValidationFailed,
                "Аргументы не соответствуют JSON Schema: $errorsSummary"
            )
            val projected = projector.project(errorResult, budget.maxResultBytes)
            return CallProcessOutcome.Feedback(
                ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected)
            )
        }

        val policyDecision = securityPolicy.evaluate(
            tool = tool.definition,
            arguments = call.arguments,
            turnEpoch = turnEpoch,
            confirmationToken = null,
        )

        return when (policyDecision) {
            is ToolPolicyDecision.Deny -> {
                val errorResult = ToolResult.Error(
                    ToolErrorKind.PolicyDenied,
                    "Действие отклонено политикой безопасности: ${policyDecision.reason}"
                )
                val projected = projector.project(errorResult, budget.maxResultBytes)
                CallProcessOutcome.Feedback(
                    ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected)
                )
            }
            is ToolPolicyDecision.RequiresConfirmation -> {
                CallProcessOutcome.ConfirmationNeeded(policyDecision.token, tool.definition)
            }
            is ToolPolicyDecision.Allow -> {
                val execResult = executeToolWithValidation(tool, call)
                executedSnapshots.add(
                    ExecutedToolCallSnapshot(
                        callId = call.callId,
                        toolId = tool.definition.id,
                        toolName = tool.definition.name,
                        success = execResult is ToolResult.Success,
                        timestamp = now(),
                    )
                )
                val projected = projector.project(execResult, budget.maxResultBytes)
                CallProcessOutcome.Feedback(
                    ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected)
                )
            }
        }
    }

    private suspend fun executeToolWithValidation(tool: RayaTool, call: ToolCall): ToolResult {
        val rawResult = try {
            withTimeout(budget.perToolTimeoutMs) {
                tool.execute(call.arguments)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult.Error(
                ToolErrorKind.Timeout,
                "Превышено время ожидания выполнения инструмента (${budget.perToolTimeoutMs}мс)."
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            ToolResult.Error(
                ToolErrorKind.ExecutionFailed,
                "Ошибка при выполнении: ${failure.message ?: failure::class.java.simpleName}"
            )
        }

        if (rawResult is ToolResult.Success && tool.definition.outputSchema != null) {
            val outValidation = schemaValidator.validate(tool.definition.outputSchema!!, rawResult.data)
            if (outValidation is SchemaValidationResult.Invalid) {
                val errors = outValidation.errors.joinToString("; ") { "${it.path}: ${it.error}" }
                return ToolResult.Error(
                    ToolErrorKind.ExecutionFailed,
                    "Результат выполнения не соответствует выходной схеме: $errors"
                )
            }
        }

        return rawResult
    }
}
