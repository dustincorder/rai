package com.dustincorder.rai.domain.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Monotonic time abstraction (Android-free pure Kotlin / JVM).
 */
fun interface MonotonicClock {
    fun markMonotonicMs(): Long
}

/**
 * Single-use, cryptographic token tying an approved or proposed tool execution
 * to its exact parameters, execution kind, turn epoch, and monotonic expiration time.
 */
@Serializable
data class ActionConfirmationToken(
    val tokenId: String = UUID.randomUUID().toString(),
    val toolId: ToolId,
    val executionKind: ExecutionKind,
    val canonicalArgumentsHash: String,
    val turnEpoch: Int,
    val expiresAtMs: Long,
) {
    fun isValidFor(
        targetToolId: ToolId,
        targetExecutionKind: ExecutionKind,
        arguments: JsonObject,
        currentTurnEpoch: Int,
        nowMonotonicMs: Long,
    ): Boolean {
        if (toolId != targetToolId) return false
        if (executionKind != targetExecutionKind) return false
        if (turnEpoch != currentTurnEpoch) return false
        if (nowMonotonicMs >= expiresAtMs) return false
        val currentHash = computeCanonicalArgumentsHash(arguments)
        return canonicalArgumentsHash == currentHash
    }
}

sealed interface ToolPolicyDecision {
    data object Allow : ToolPolicyDecision
    data class RequiresConfirmation(
        val token: ActionConfirmationToken,
        val explanation: String,
    ) : ToolPolicyDecision
    data class Deny(val reason: String) : ToolPolicyDecision
}

interface ToolSecurityPolicy {
    fun evaluate(
        tool: ToolDefinition,
        arguments: JsonObject,
        turnEpoch: Int,
        confirmationToken: ActionConfirmationToken? = null,
    ): ToolPolicyDecision
}

class DefaultToolSecurityPolicy(
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L },
    private val tokenValidityDurationMs: Long = 60_000L,
) : ToolSecurityPolicy {

    override fun evaluate(
        tool: ToolDefinition,
        arguments: JsonObject,
        turnEpoch: Int,
        confirmationToken: ActionConfirmationToken?,
    ): ToolPolicyDecision {
        if (!tool.enabled) {
            return ToolPolicyDecision.Deny("Инструмент '${tool.name}' отключён.")
        }

        val requiresConfirmation = when (tool.effect) {
            ToolEffect.ReadOnly -> false
            ToolEffect.LocalReversible -> false
            ToolEffect.ExternalWrite -> true
            ToolEffect.Destructive -> true
            ToolEffect.SensitiveDataAccess -> true
        }

        if (!requiresConfirmation) {
            return ToolPolicyDecision.Allow
        }

        val currentMonotonic = clock.markMonotonicMs()
        if (confirmationToken != null && confirmationToken.isValidFor(tool.id, tool.executionKind, arguments, turnEpoch, currentMonotonic)) {
            return ToolPolicyDecision.Allow
        }

        val freshToken = ActionConfirmationToken(
            toolId = tool.id,
            executionKind = tool.executionKind,
            canonicalArgumentsHash = computeCanonicalArgumentsHash(arguments),
            turnEpoch = turnEpoch,
            expiresAtMs = currentMonotonic + tokenValidityDurationMs,
        )

        return ToolPolicyDecision.RequiresConfirmation(
            token = freshToken,
            explanation = "Действие '${tool.name}' требует подтверждения пользователя перед выполнением.",
        )
    }
}

/**
 * Deterministically normalizes and computes the SHA-256 hash of a JsonObject by sorting keys.
 */
fun computeCanonicalArgumentsHash(element: JsonObject): String {
    val canonicalJsonString = canonicalizeJson(element).toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJsonString.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun canonicalizeJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> buildJsonObject {
        element.keys.sorted().forEach { key ->
            put(key, canonicalizeJson(element.getValue(key)))
        }
    }
    is JsonArray -> JsonArray(element.map(::canonicalizeJson))
    is JsonPrimitive -> element
}
