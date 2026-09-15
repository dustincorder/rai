package com.dustincorder.rai.domain

/**
 * Result of a [RecognitionAttemptLifecycle.onStartRequested] call.
 */
sealed interface RecognitionAttemptAction {
    /** No attempt active: the recognizer may start listening immediately. */
    data object StartNow : RecognitionAttemptAction

    /** The previous attempt must settle (or a stale listener source must be discarded) before the new request can start. */
    data object WaitForSettleThenStart : RecognitionAttemptAction
}

/**
 * Serializes recognition attempts on a single untagged `SpeechRecognizer` listener.
 *
 * Android delivers the consequence of `cancel()` (typically `ERROR_CLIENT`) through the SAME
 * shared recognition listener, asynchronously - it can arrive only after a newer attempt has
 * already been started. A shared untagged event stream cannot tell that stray terminal from a
 * genuine event of the new attempt, so the ambiguity is removed here at the source instead of
 * being guessed at:
 *
 *  - a cancelled attempt enters [State.Cancelling] and blocks any new attempt;
 *  - while [State.Cancelling], callbacks of the old attempt (partials AND terminals) are
 *    swallowed at the boundary and never enter the shared event stream; the FIRST terminal is
 *    the cancellation acknowledgement;
 *  - a new attempt may start only after the old one settled (acknowledgement) or after the
 *    settle timeout forced a discard (`onSettleTimeout` + adapter recreates the recognizer),
 *    so a new attempt and a cancelled old attempt never overlap on one listener;
 *  - once a new attempt is actually listening ([State.Listening]), every event - including a
 *    genuine `ERROR_CLIENT` - is forwarded normally; nothing is suppressed based on history.
 *
 * The state machine is Android-free; the Android adapter drives it and owns the `SpeechRecognizer`.
 */
class RecognitionAttemptLifecycle {
    enum class State { Idle, Listening, Cancelling }

    private var currentState: State = State.Idle
    private var attemptGeneration = 0L
    private var pendingStart = false
    private var readyToStart = false

    val state: State get() = currentState
    val generation: Long get() = attemptGeneration

    /** True when a pending start was promoted by a settle and the adapter should start it now. */
    val isReadyToStart: Boolean get() = readyToStart

    /** Only a live [State.Listening] attempt emits partials; cancelled attempts are muted. */
    val shouldForwardPartial: Boolean get() = currentState == State.Listening

    fun onStartRequested(): RecognitionAttemptAction {
        return when (currentState) {
            State.Idle -> {
                attemptGeneration++
                currentState = State.Listening
                RecognitionAttemptAction.StartNow
            }
            State.Listening -> {
                currentState = State.Cancelling
                pendingStart = true
                RecognitionAttemptAction.WaitForSettleThenStart
            }
            State.Cancelling -> {
                pendingStart = true
                RecognitionAttemptAction.WaitForSettleThenStart
            }
        }
    }

    /**
     * A user/owner cancellation of the active attempt.
     *
     * @return true when the recognizer must actually be cancelled (a live attempt existed);
     *         false when there is nothing to cancel and no consequence is expected.
     */
    fun onCancelRequest(): Boolean {
        return when (currentState) {
            State.Listening -> {
                currentState = State.Cancelling
                true
            }
            else -> false
        }
    }

    /**
     * A terminal (onError / onResults) callback from the recognizer.
     *
     * @return true when the terminal belongs to a live [State.Listening] attempt and must be
     *         forwarded; false for a cancellation acknowledgement (or a stray callback with no
     *         attempt), which must never be forwarded.
     */
    fun onTerminalCallback(): Boolean {
        return when (currentState) {
            State.Listening -> {
                currentState = State.Idle
                true
            }
            State.Cancelling -> {
                settleCancellation()
                false
            }
            State.Idle -> false
        }
    }

    /**
     * The adapter's bounded cancellation-settle deadline expired without a terminal
     * acknowledgement. The adapter must discard the current recognizer source before starting
     * any promoted pending request (see [isReadyToStart]).
     */
    fun onSettleTimeout() {
        if (currentState == State.Cancelling) settleCancellation()
    }

    /** Marks the promoted pending start as handed off to the recognizer. */
    fun acknowledgeStart() {
        readyToStart = false
    }

    private fun settleCancellation() {
        readyToStart = pendingStart
        pendingStart = false
        currentState = if (readyToStart) State.Listening else State.Idle
        if (readyToStart) attemptGeneration++
    }
}