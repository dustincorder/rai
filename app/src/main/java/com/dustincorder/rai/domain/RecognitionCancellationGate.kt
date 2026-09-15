package com.dustincorder.rai.domain

/**
 * Adapter-level ownership gate that keeps callbacks caused by an intentionally
 * cancelled (or superseded) recognition attempt from ever being interpreted as
 * events of a newer attempt.
 *
 * Android's [android.speech.SpeechRecognizer] delivers the consequence of a
 * [cancel] (typically [android.speech.SpeechRecognizer.ERROR_CLIENT]) through
 * the SAME recognition listener, asynchronously, and it may arrive only after a
 * newer recognition attempt has already been started. The orchestrator cannot
 * tell such a stray callback from a genuine event of the new attempt because it
 * is delivered after the new attempt's collector subscribed.
 *
 * This gate restores the ownership at the producer boundary with a single token:
 *
 *  - a cancellation (or a start-over) of an attempt that was still in flight
 *    arms [RecognitionCancellationGate] for exactly ONE consequential terminal
 *    event belonging to the cancelled attempt;
 *  - if a newer attempt has already been started when that stray terminal
 *    arrives, it is dropped here, before it ever reaches the event stream.
 *
 * The gate is intentionally narrow: it drops only the single expected
 * cancel-consequence (a terminal error), it requires [started] to be true (a new
 * attempt exists), and it only ever suppresses `ERROR_CLIENT`-mapped
 * [SpeechRecognitionErrorReason.Other] errors - never NoSpeech/NoMatch/Network
 * recovery errors, never Final results, and nothing when no attempt was
 * cancelled. It cannot be confused with "ignore every ERROR_CLIENT forever".
 */
class RecognitionCancellationGate {
    private var started = false
    private var awaitingConsequence = false

    fun markStarted() {
        if (started) awaitingConsequence = true
        started = true
    }

    fun onCancel() {
        if (started) awaitingConsequence = true
        started = false
    }

    fun shouldForward(event: SpeechRecognitionEvent): Boolean {
        val drop = awaitingConsequence && started &&
            event is SpeechRecognitionEvent.Error &&
            event.reason == SpeechRecognitionErrorReason.Other
        awaitingConsequence = false
        if (started && (event is SpeechRecognitionEvent.Final || event is SpeechRecognitionEvent.Error)) {
            started = false
        }
        return !drop
    }
}