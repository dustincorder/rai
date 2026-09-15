package com.dustincorder.rai.ui

/** Pure tail-follow state. User-turn revisions are explicit navigation intent. */
internal data class ConversationTailPolicyState(
    val followTail: Boolean = true,
    val handledUserTurnRevision: Long = 0L,
)

internal fun ConversationTailPolicyState.onUserTurnRevision(revision: Long): ConversationTailPolicyState =
    if (revision == handledUserTurnRevision) {
        this
    } else {
        copy(followTail = true, handledUserTurnRevision = revision)
    }

internal fun ConversationTailPolicyState.onViewportSample(
    totalItems: Int,
    currentItems: Int,
    atBottom: Boolean,
): ConversationTailPolicyState =
    if (totalItems != currentItems) this else copy(followTail = atBottom)
