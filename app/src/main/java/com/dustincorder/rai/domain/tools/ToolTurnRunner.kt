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
        val executedCalls: List<ExecutedToolCallSnapshot>,
    ) : ToolTurnResult

    data class ConfirmationRequired(val pending: PendingToolConfirmation) : ToolTurnResult

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
    suspend fun runTurn(
        messages: List<ConversationMessage>,
        turnEpoch: Int,
        languageTag: String?,
        modelInvoker: ModelTurnInvoker,
        confirmationToken: ActionConfirmationToken? = null,
    ): ToolTurnResult {
        val startedAt = now()
        val steps = mutableListOf<ModelRoundStep>()
        val executedSnapshots = mutableListOf<ExecutedToolCallSnapshot>()
        var round = 0
        var totalCallsExecuted = 0

        try {
            return withTimeout(budget.wholeTurnTimeoutMs) {
                while (round < budget.maxModelRounds) {
                    currentCoroutineContext().ensureActive()
                    round++

                    val activeTools = registry.activeTools().map { it.definition }
                    val response = try {
                        modelInvoker.invokeRound(messages, activeTools, steps, languageTag)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        return@withTimeout ToolTurnResult.Failed(
                            ToolTurnError.ModelError(failure.message ?: "Model invocation failed")
                        )
                    }

                    when (response) {
                        is ModelRoundResponse.FinalReply -> {
                            return@withTimeout ToolTurnResult.Completed(response.response, executedSnapshots)
                        }

                        is ModelRoundResponse.ToolCalls -> {
                            val calls = response.calls
                            if (calls.isEmpty()) {
                                return@withTimeout ToolTurnResult.Failed(
                                    ToolTurnError.ModelError("Model returned empty tool calls list without reply")
                                )
                            }

                            if (totalCallsExecuted + calls.size > budget.maxTotalToolCalls) {
                                return@withTimeout ToolTurnResult.Failed(
                                    ToolTurnError.CallBudgetExceeded(totalCallsExecuted + calls.size)
                                )
                            }

                            steps.add(ModelRoundStep.AssistantToolCalls(calls))

                            // Sequential execution of model tool calls
                            for (call in calls) {
                                currentCoroutineContext().ensureActive()
                                val tool = registry.get(call.toolName)

                                if (tool == null) {
                                    val errorResult = ToolResult.Error(
                                        ToolErrorKind.ValidationFailed,
                                        "Неизвестный инструмент: '${call.toolName}'"
                                    )
                                    val projected = projector.project(errorResult, budget.maxResultBytes)
                                    steps.add(ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected))
                                    continue
                                }

                                if (!tool.definition.enabled) {
                                    val errorResult = ToolResult.Error(
                                        ToolErrorKind.PolicyDenied,
                                        "Инструмент '${call.toolName}' отключён политикой."
                                    )
                                    val projected = projector.project(errorResult, budget.maxResultBytes)
                                    steps.add(ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected))
                                    continue
                                }

                                // Schema validation before execution
                                val validation = schemaValidator.validate(tool.definition.inputSchema, call.arguments)
                                if (validation is SchemaValidationResult.Invalid) {
                                    val errorsSummary = validation.errors.joinToString("; ") { "${it.path}: ${it.error}" }
                                    val errorResult = ToolResult.Error(
                                        ToolErrorKind.ValidationFailed,
                                        "Аргументы не соответствуют JSON Schema: $errorsSummary"
                                    )
                                    val projected = projector.project(errorResult, budget.maxResultBytes)
                                    steps.add(ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected))
                                    continue
                                }

                                // Preflight security policy check BEFORE execution
                                val policyDecision = securityPolicy.evaluate(
                                    tool = tool.definition,
                                    arguments = call.arguments,
                                    turnEpoch = turnEpoch,
                                    confirmationToken = confirmationToken,
                                )

                                when (policyDecision) {
                                    is ToolPolicyDecision.Deny -> {
                                        val errorResult = ToolResult.Error(
                                            ToolErrorKind.PolicyDenied,
                                            "Действие отклонено политикой безопасности: ${policyDecision.reason}"
                                        )
                                        val projected = projector.project(errorResult, budget.maxResultBytes)
                                        steps.add(ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected))
                                    }

                                    is ToolPolicyDecision.RequiresConfirmation -> {
                                        // Stop execution immediately; surface confirmation to orchestrator
                                        return@withTimeout ToolTurnResult.ConfirmationRequired(
                                            PendingToolConfirmation(
                                                token = policyDecision.token,
                                                call = call,
                                                definition = tool.definition,
                                                steps = steps.toList(),
                                                executedCalls = executedSnapshots.toList(),
                                                round = round,
                                            )
                                        )
                                    }

                                    is ToolPolicyDecision.Allow -> {
                                        // Execute tool with per-tool timeout
                                        val execResult = try {
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

                                        totalCallsExecuted++
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
                                        steps.add(ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projected))
                                    }
                                }
                            }
                        }
                    }
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
    ): ToolTurnResult {
        val startedAt = now()
        val steps = pending.steps.toMutableList()
        val executedSnapshots = pending.executedCalls.toMutableList()
        var round = pending.round
        var totalCallsExecuted = executedSnapshots.size

        if (!confirmationToken.isValidFor(pending.definition.id, pending.call.arguments, turnEpoch, now())) {
            return ToolTurnResult.Failed(ToolTurnError.ModelError("Действие отклонено: недействительный токен подтверждения."))
        }

        try {
            return withTimeout(budget.wholeTurnTimeoutMs) {
                val tool = registry.get(pending.call.toolName) ?: return@withTimeout ToolTurnResult.Failed(
                    ToolTurnError.ModelError("Инструмент '${pending.call.toolName}' не найден в реестре.")
                )

                // Execute the confirmed tool
                val execResult = try {
                    withTimeout(budget.perToolTimeoutMs) {
                        tool.execute(pending.call.arguments)
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

                totalCallsExecuted++
                executedSnapshots.add(
                    ExecutedToolCallSnapshot(
                        callId = pending.call.callId,
                        toolId = tool.definition.id,
                        toolName = tool.definition.name,
                        success = execResult is ToolResult.Success,
                        timestamp = now(),
                    )
                )

                val projected = projector.project(execResult, budget.maxResultBytes)
                steps.add(ModelRoundStep.ToolExecutionFeedback(pending.call.callId, pending.call.toolName, projected))

                // Continue loop
                while (round < budget.maxModelRounds) {
                    currentCoroutineContext().ensureActive()
                    round++

                    val activeTools = registry.activeTools().map { it.definition }
                    val response = try {
                        modelInvoker.invokeRound(messages, activeTools, steps, languageTag)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        return@withTimeout ToolTurnResult.Failed(
                            ToolTurnError.ModelError(failure.message ?: "Model invocation failed")
                        )
                    }

                    when (response) {
                        is ModelRoundResponse.FinalReply -> {
                            return@withTimeout ToolTurnResult.Completed(response.response, executedSnapshots)
                        }

                        is ModelRoundResponse.ToolCalls -> {
                            val calls = response.calls
                            if (calls.isEmpty()) {
                                return@withTimeout ToolTurnResult.Failed(
                                    ToolTurnError.ModelError("Model returned empty tool calls list without reply")
                                )
                            }

                            if (totalCallsExecuted + calls.size > budget.maxTotalToolCalls) {
                                return@withTimeout ToolTurnResult.Failed(
                                    ToolTurnError.CallBudgetExceeded(totalCallsExecuted + calls.size)
                                )
                            }

                            steps.add(ModelRoundStep.AssistantToolCalls(calls))

                            for (call in calls) {
                                currentCoroutineContext().ensureActive()
                                val nextTool = registry.get(call.toolName)

                                if (nextTool == null) {
                                    val err = ToolResult.Error(ToolErrorKind.ValidationFailed, "Неизвестный инструмент: '${call.toolName}'")
                                    steps.add(ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projector.project(err, budget.maxResultBytes)))
                                    continue
                                }

                                if (!nextTool.definition.enabled) {
                                    val err = ToolResult.Error(ToolErrorKind.PolicyDenied, "Инструмент '${call.toolName}' отключён.")
                                    steps.add(ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projector.project(err, budget.maxResultBytes)))
                                    continue
                                }

                                val valRes = schemaValidator.validate(nextTool.definition.inputSchema, call.arguments)
                                if (valRes is SchemaValidationResult.Invalid) {
                                    val errors = valRes.errors.joinToString("; ") { "${it.path}: ${it.error}" }
                                    val err = ToolResult.Error(ToolErrorKind.ValidationFailed, "Аргументы не соответствуют JSON Schema: $errors")
                                    steps.add(ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projector.project(err, budget.maxResultBytes)))
                                    continue
                                }

                                val decision = securityPolicy.evaluate(nextTool.definition, call.arguments, turnEpoch, null)
                                when (decision) {
                                    is ToolPolicyDecision.Deny -> {
                                        val err = ToolResult.Error(ToolErrorKind.PolicyDenied, "Отклонено: ${decision.reason}")
                                        steps.add(ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projector.project(err, budget.maxResultBytes)))
                                    }

                                    is ToolPolicyDecision.RequiresConfirmation -> {
                                        return@withTimeout ToolTurnResult.ConfirmationRequired(
                                            PendingToolConfirmation(
                                                token = decision.token,
                                                call = call,
                                                definition = nextTool.definition,
                                                steps = steps.toList(),
                                                executedCalls = executedSnapshots.toList(),
                                                round = round,
                                            )
                                        )
                                    }

                                    is ToolPolicyDecision.Allow -> {
                                        val subExec = try {
                                            withTimeout(budget.perToolTimeoutMs) { nextTool.execute(call.arguments) }
                                        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                                            ToolResult.Error(ToolErrorKind.Timeout, "Превышено время ожидания.")
                                        } catch (cancellation: CancellationException) {
                                            throw cancellation
                                        } catch (failure: Throwable) {
                                            ToolResult.Error(ToolErrorKind.ExecutionFailed, "Ошибка: ${failure.message}")
                                        }

                                        totalCallsExecuted++
                                        executedSnapshots.add(
                                            ExecutedToolCallSnapshot(
                                                callId = call.callId,
                                                toolId = nextTool.definition.id,
                                                toolName = nextTool.definition.name,
                                                success = subExec is ToolResult.Success,
                                                timestamp = now(),
                                            )
                                        )
                                        steps.add(ModelRoundStep.ToolExecutionFeedback(call.callId, call.toolName, projector.project(subExec, budget.maxResultBytes)))
                                    }
                                }
                            }
                        }
                    }
                }

                ToolTurnResult.Failed(ToolTurnError.RoundBudgetExceeded(round))
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            return ToolTurnResult.Failed(ToolTurnError.TurnTimeout(now() - startedAt))
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }
}
