package com.dustincorder.rai.ui

/** Pure tail-follow state. User-turn revisions are explicit navigation intent. */
internal data class ConversationTailPolicyState(
    val followTail: Boolean = true,
    val handledUserTurnRevision: Long = 0L,
    val pendingUserTurnScroll: Boolean = false,
    val manualOverride: Boolean = false,
)

internal fun ConversationTailPolicyState.onUserTurnRevision(revision: Long): ConversationTailPolicyState =
    if (revision == handledUserTurnRevision) {
        this
    } else {
        copy(
            followTail = true,
            handledUserTurnRevision = revision,
            pendingUserTurnScroll = true,
            manualOverride = false,
        )
    }

internal fun ConversationTailPolicyState.onViewportSample(
    totalItems: Int,
    currentItems: Int,
    atBottom: Boolean,
): ConversationTailPolicyState =
    if (totalItems != currentItems || !atBottom || manualOverride) {
        this
    } else {
        copy(followTail = true, pendingUserTurnScroll = false)
    }

internal fun ConversationTailPolicyState.onManualScroll(): ConversationTailPolicyState =
    copy(followTail = false, pendingUserTurnScroll = false, manualOverride = true)
