package com.dustincorder.rai.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTailPolicyTest {
    @Test
    fun `bottom plus passive assistant update keeps tail follow`() {
        val state = ConversationTailPolicyState()
            .onViewportSample(totalItems = 10, currentItems = 10, atBottom = true)

        assertTrue(state.followTail)
        assertFalse(state.pendingUserTurnScroll)
    }

    @Test
    fun `scrolled up plus passive assistant update stays in history`() {
        val state = ConversationTailPolicyState()
            .onViewportSample(totalItems = 10, currentItems = 10, atBottom = false)
            .onManualScroll()

        assertFalse(state.followTail)
        assertFalse(state.onViewportSample(11, 10, atBottom = true).followTail)
    }

    @Test
    fun `accepted user turn explicitly re-enters tail follow`() {
        val state = ConversationTailPolicyState()
            .onViewportSample(30, 30, atBottom = false)
            .onUserTurnRevision(1)

        assertTrue(state.followTail)
        assertTrue(state.handledUserTurnRevision == 1L)
        assertTrue(state.pendingUserTurnScroll)

        assertTrue(
            "stale synchronized viewport sample cannot consume the latch",
            state.onViewportSample(31, 31, atBottom = false).followTail,
        )
    }

    @Test
    fun `manual scroll after user turn disables follow again`() {
        val state = ConversationTailPolicyState()
            .onUserTurnRevision(1)
            .onViewportSample(31, 31, atBottom = false)
            .onManualScroll()

        assertFalse(state.followTail)
        assertFalse(state.pendingUserTurnScroll)
        assertTrue(state.manualOverride)
    }

    @Test
    fun `partial final thinking assistant updates preserve active tail`() {
        val state = ConversationTailPolicyState()
            .onUserTurnRevision(1)
        val afterPartial = state.onViewportSample(30, 30, atBottom = true)
        val afterFinal = afterPartial.onViewportSample(31, 31, atBottom = true)
        val afterThinking = afterFinal.onViewportSample(32, 32, atBottom = true)

        assertTrue(afterPartial.followTail)
        assertTrue(afterFinal.followTail)
        assertTrue(afterThinking.followTail)
        assertFalse(afterThinking.pendingUserTurnScroll)

        val passiveAssistantAfterSettled = afterThinking.onViewportSample(33, 33, atBottom = false)
        assertTrue(passiveAssistantAfterSettled.followTail)
    }

    @Test
    fun `manual override remains off for later passive updates`() {
        val state = ConversationTailPolicyState()
            .onUserTurnRevision(1)
            .onManualScroll()

        assertFalse(state.onViewportSample(32, 32, atBottom = false).followTail)
        assertFalse(state.onViewportSample(33, 33, atBottom = true).followTail)
    }
}
