package com.dustincorder.rai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-machine tests for the serialized recognizer lifecycle, together with a tiny
 * adapter driver that mirrors the Android adapter glue: start on StartNow, cancel the
 * recognizer when a start must wait for a settle, discard the source on settle timeout.
 *
 * These assert the exact race orderings the real SpeechRecognizer can produce.
 */
class RecognitionAttemptLifecycleTest {
    private class DriveRecorder {
        val forwarded = mutableListOf<SpeechRecognitionEvent>()
        var suppressed = 0
        var suppressedPartials = 0
        var startCount = 0
        var cancelCount = 0
        var recreateCount = 0
    }

    private class AdapterDriver(
        val lifecycle: RecognitionAttemptLifecycle,
        val recorder: DriveRecorder,
    ) {
        val startNow: Boolean get() = lifecycle.isReadyToStart

        fun startRequested() {
            when (lifecycle.onStartRequested()) {
                RecognitionAttemptAction.StartNow -> doStart()
                RecognitionAttemptAction.WaitForSettleThenStart -> cancelRecognizer()
            }
        }

        fun cancelRequested() {
            if (lifecycle.onCancelRequest()) cancelRecognizer()
        }

        fun partial(text: String) {
            if (lifecycle.shouldForwardPartial) {
                recorder.forwarded.add(SpeechRecognitionEvent.Partial(text))
            } else {
                recorder.suppressedPartials++
            }
        }

        fun terminal(event: SpeechRecognitionEvent) {
            if (lifecycle.onTerminalCallback()) {
                recorder.forwarded.add(event)
            } else {
                recorder.suppressed++
            }
            if (lifecycle.isReadyToStart) doStart()
        }

        fun settleTimeout() {
            lifecycle.onSettleTimeout()
            if (lifecycle.isReadyToStart) {
                discardSource() // timeout discard must happen before the promoted start
                doStart()
            }
        }

        private fun cancelRecognizer() {
            recorder.cancelCount++
        }

        private fun discardSource() {
            recorder.recreateCount++
        }

        private fun doStart() {
            recorder.startCount++
            lifecycle.acknowledgeStart()
        }
    }

    private fun driver(): AdapterDriver {
        val recorder = DriveRecorder()
        return AdapterDriver(RecognitionAttemptLifecycle(), recorder)
    }

    private val errorClient = SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.Other, "client 5")

    @Test
    fun `A normal cancel swallows the acknowledgement and returns to idle`() {
        val d = driver()
        d.startRequested()
        assertEquals(1, d.recorder.startCount)
        assertEquals(RecognitionAttemptLifecycle.State.Listening, d.lifecycle.state)

        d.cancelRequested()
        assertEquals(1, d.recorder.cancelCount)
        assertEquals(RecognitionAttemptLifecycle.State.Cancelling, d.lifecycle.state)

        d.terminal(errorClient)
        assertEquals("cancel-consequence terminal must be swallowed", 0, d.recorder.forwarded.size)
        assertEquals(1, d.recorder.suppressed)
        assertEquals(RecognitionAttemptLifecycle.State.Idle, d.lifecycle.state)

        assertFalse(d.startNow)
    }

    @Test
    fun `B quick mic off then on does not overlap attempts on one listener`() {
        val d = driver()
        d.startRequested() // attempt A
        d.cancelRequested() // Mic Off: cancel requested, no terminal yet
        assertEquals(RecognitionAttemptLifecycle.State.Cancelling, d.lifecycle.state)

        d.startRequested() // Mic On requested BEFORE the old cancel terminal
        assertEquals(
            "new attempt must not listen while the old cancellation is unresolved",
            1,
            d.recorder.startCount,
        )
        assertEquals(RecognitionAttemptLifecycle.State.Cancelling, d.lifecycle.state)

        d.terminal(errorClient) // delayed ERROR_CLIENT from cancelled A
        assertEquals("stale cancel terminal must never leak into the new attempt", 0, d.recorder.forwarded.size)
        assertEquals(1, d.recorder.suppressed)
        assertEquals("attempt B only starts after A settled", 2, d.recorder.startCount)
        assertEquals(RecognitionAttemptLifecycle.State.Listening, d.lifecycle.state)

        d.terminal(SpeechRecognitionEvent.Final("новый текст", "ru-RU"))
        assertEquals(1, d.recorder.forwarded.size)
    }

    @Test
    fun `C partials from the cancelled attempt are muted and a pending start still waits`() {
        val d = driver()
        d.startRequested()
        d.cancelRequested()
        d.startRequested() // pending
        assertEquals(1, d.recorder.startCount)

        d.partial("старый частичный результат")
        assertEquals("old partial must be muted during cancellation", 1, d.recorder.suppressedPartials)
        assertEquals(1, d.recorder.startCount)

        d.terminal(errorClient)
        assertEquals(2, d.recorder.startCount)
        d.partial("новый частичный результат")
        assertEquals(1, d.recorder.forwarded.size)
        assertEquals("new attempt partials are forwarded normally", 1, d.recorder.forwarded.count { it is SpeechRecognitionEvent.Partial })
    }

    @Test
    fun `D any terminal type of the cancelled attempt is swallowed as acknowledgement`() {
        val d = driver()
        d.startRequested()
        d.cancelRequested()
        d.startRequested()

        d.terminal(SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.NoMatch, "не распознано"))
        assertTrue(d.recorder.forwarded.isEmpty())
        assertEquals(2, d.recorder.startCount)

        val d2 = driver()
        d2.startRequested()
        d2.cancelRequested()
        d2.startRequested()
        d2.terminal(SpeechRecognitionEvent.Final("пустой результат", null))
        assertEquals("empty-result Final of a cancelled attempt is not a new attempt event", 0, d2.recorder.forwarded.size)
        assertEquals(2, d2.recorder.startCount)
    }

    @Test
    fun `E genuine error of the new attempt is forwarded even right after a cancellation`() {
        val d = driver()
        d.startRequested()
        d.cancelRequested()
        d.startRequested()
        d.terminal(errorClient) // settle old attempt
        assertEquals(2, d.recorder.startCount)

        d.terminal(errorClient) // genuine ERROR_CLIENT of live attempt B
        assertEquals("live attempt error must not be swallowed because A was cancelled", 1, d.recorder.forwarded.size)
        assertEquals(1, d.recorder.suppressed)
    }

    @Test
    fun `F settle timeout forces a source discard before the pending start`() {
        val d = driver()
        d.startRequested()
        d.cancelRequested()
        d.startRequested()
        assertEquals(1, d.recorder.startCount)

        val recreatesBefore = d.recorder.recreateCount
        d.settleTimeout()
        assertEquals("old source must be discarded on settle timeout", recreatesBefore + 1, d.recorder.recreateCount)
        assertEquals("pending start proceeds after the discard", 2, d.recorder.startCount)
        assertEquals(RecognitionAttemptLifecycle.State.Listening, d.lifecycle.state)

        d.terminal(errorClient)
        assertEquals("fresh attempt events flow normally after discard", 1, d.recorder.forwarded.size)
    }

    @Test
    fun `G cancel without pending start and late callbacks are ignored`() {
        val d = driver()
        d.startRequested()
        d.cancelRequested()
        d.terminal(errorClient) // acknowledgement
        assertEquals(RecognitionAttemptLifecycle.State.Idle, d.lifecycle.state)

        d.terminal(errorClient) // late stray callback
        assertEquals("late callbacks after settle are ignored", 0, d.recorder.forwarded.size)
        assertEquals(2, d.recorder.suppressed)

        d.startRequested() // next session start is fresh
        assertEquals(2, d.recorder.startCount)
        assertEquals(RecognitionAttemptLifecycle.State.Listening, d.lifecycle.state)
    }

    @Test
    fun `H recoverable no match on a live attempt is forwarded and not treated as cancellation`() {
        val d = driver()
        d.startRequested()
        d.terminal(SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.NoMatch, "не услышал"))
        assertEquals("recoverable terminal of a live attempt is not swallowed", 1, d.recorder.forwarded.size)
        assertEquals(0, d.recorder.suppressed)
        assertEquals(RecognitionAttemptLifecycle.State.Idle, d.lifecycle.state)

        d.startRequested()
        assertEquals("recoverable restart starts a fresh attempt", 2, d.recorder.startCount)
    }

    @Test
    fun `start requested while a previous attempt is still listening cancels it internally`() {
        val d = driver()
        d.startRequested() // attempt A live
        d.startRequested() // start B requested while A still listening
        assertEquals(
            "old attempt must be cancelled before B can start",
            1,
            d.recorder.cancelCount,
        )
        assertEquals(1, d.recorder.startCount)
        assertEquals(RecognitionAttemptLifecycle.State.Cancelling, d.lifecycle.state)

        d.terminal(errorClient) // A settles
        assertEquals(2, d.recorder.startCount)
        assertEquals(RecognitionAttemptLifecycle.State.Listening, d.lifecycle.state)
    }
}