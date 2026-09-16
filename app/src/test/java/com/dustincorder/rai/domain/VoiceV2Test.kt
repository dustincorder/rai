package com.dustincorder.rai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceV2Test {
    @Test
    fun `100ms speech silence 100ms speech does not confirm`() {
        val detector = VoiceActivityDetector(sampleRateHz = 1_000)
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(100) { 4_000 }))
        detector.acceptPcm16(ShortArray(150))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(100) { 4_000 }))
    }

    @Test
    fun `199ms consecutive speech does not confirm`() {
        val detector = VoiceActivityDetector(sampleRateHz = 1_000)
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(199) { 4_000 }))
    }

    @Test
    fun `200ms consecutive speech confirms exactly once`() {
        val detector = VoiceActivityDetector(sampleRateHz = 1_000)
        assertEquals(EndpointDecision.SpeechConfirmed, detector.acceptPcm16(ShortArray(200) { 4_000 }))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(200) { 4_000 }))
    }

    @Test
    fun `post confirmation 500ms pause remains same utterance`() {
        val detector = VoiceActivityDetector(sampleRateHz = 1_000)
        assertEquals(EndpointDecision.SpeechConfirmed, detector.acceptPcm16(ShortArray(200) { 4_000 }))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(500)))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(100) { 4_000 }))
    }

    @Test
    fun `post confirmation 1400ms silence ends utterance`() {
        val detector = VoiceActivityDetector(sampleRateHz = 1_000)
        assertEquals(EndpointDecision.SpeechConfirmed, detector.acceptPcm16(ShortArray(200) { 4_000 }))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(50) { 4_000 }))
        assertEquals(EndpointDecision.EndUtterance, detector.acceptPcm16(ShortArray(1_400)))
    }
    @Test
    fun `short pause does not end utterance after speech`() {
        val detector = VoiceActivityDetector(
            minimumSpeechMs = 100,
            trailingSilenceMs = 500,
            sampleRateHz = 1_000,
        )
        val speech = ShortArray(200) { 4_000 }
        val silence = ShortArray(200)

        assertEquals(EndpointDecision.SpeechConfirmed, detector.acceptPcm16(speech))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(silence))
    }

    @Test
    fun `default conversational pauses through 800ms do not finalize`() {
        val detector = VoiceActivityDetector(sampleRateHz = 1_000)
        assertEquals(EndpointDecision.SpeechConfirmed, detector.acceptPcm16(ShortArray(250) { 4_000 }))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(300)))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(500)))
    }

    @Test
    fun `default trailing silence finalizes after conservative endpoint`() {
        val detector = VoiceActivityDetector(sampleRateHz = 1_000)
        assertEquals(EndpointDecision.SpeechConfirmed, detector.acceptPcm16(ShortArray(250) { 4_000 }))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(800)))
        assertEquals(EndpointDecision.EndUtterance, detector.acceptPcm16(ShortArray(600)))
    }

    @Test
    fun `speech confirmation is emitted before endpoint silence`() {
        val detector = VoiceActivityDetector(sampleRateHz = 1_000)
        assertEquals(EndpointDecision.SpeechConfirmed, detector.acceptPcm16(ShortArray(250) { 4_000 }))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(500)))
    }

    @Test
    fun `short isolated noise never confirms speech`() {
        val detector = VoiceActivityDetector(sampleRateHz = 1_000)
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(100) { 4_000 }))
        assertEquals(EndpointDecision.DropTooShort, detector.acceptPcm16(ShortArray(1_400)))
    }

    @Test
    fun `long trailing silence ends utterance`() {
        val detector = VoiceActivityDetector(
            minimumSpeechMs = 100,
            trailingSilenceMs = 300,
            sampleRateHz = 1_000,
        )
        assertEquals(EndpointDecision.SpeechConfirmed, detector.acceptPcm16(ShortArray(200) { 4_000 }))
        assertEquals(EndpointDecision.EndUtterance, detector.acceptPcm16(ShortArray(300)))
    }

    @Test
    fun `too short speech is dropped after silence`() {
        val detector = VoiceActivityDetector(
            minimumSpeechMs = 300,
            trailingSilenceMs = 200,
            sampleRateHz = 1_000,
        )
        detector.acceptPcm16(ShortArray(100) { 4_000 })
        assertEquals(EndpointDecision.DropTooShort, detector.acceptPcm16(ShortArray(200)))
    }

    @Test
    fun `maximum duration forces endpoint`() {
        val detector = VoiceActivityDetector(
            maximumUtteranceMs = 300,
            sampleRateHz = 1_000,
        )
        assertEquals(EndpointDecision.EndUtterance, detector.acceptPcm16(ShortArray(300) { 4_000 }))
    }

    @Test
    fun `silence before speech keeps recording`() {
        val detector = VoiceActivityDetector(sampleRateHz = 1_000)
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(800)))
        assertTrue(true)
    }

    @Test
    fun `real vad abstraction confirms quiet classified speech above energy floor`() {
        val detector = VoiceActivityDetector(
            sampleRateHz = 1_000,
            rmsThreshold = 0.003,
            speechClassifier = object : SpeechFrameClassifier {
                override fun isSpeech(frame: ShortArray) = true
            },
        )

        assertEquals(EndpointDecision.SpeechConfirmed, detector.acceptPcm16(ShortArray(250) { 200 }))
    }

    @Test
    fun `speech classifier rejects impulse even when impulse is loud`() {
        val detector = VoiceActivityDetector(
            sampleRateHz = 1_000,
            speechClassifier = object : SpeechFrameClassifier {
                override fun isSpeech(frame: ShortArray) = frame.size > 200
            },
        )

        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(100) { 20_000 }))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(1_400)))
    }

    @Test
    fun `reset clears detector and resets stateful classifier once per utterance`() {
        val classifier = CountingSpeechClassifier()
        val detector = VoiceActivityDetector(
            sampleRateHz = 1_000,
            speechClassifier = classifier,
        )

        detector.acceptPcm16(ShortArray(250) { 200 })
        detector.reset()
        detector.acceptPcm16(ShortArray(250) { 200 })

        assertEquals(1, classifier.resetCount)
        assertEquals(2, classifier.frameCount)
    }

    private class CountingSpeechClassifier : SpeechFrameClassifier {
        var frameCount = 0
        var resetCount = 0
        override fun isSpeech(frame: ShortArray): Boolean {
            frameCount++
            return true
        }
        override fun reset() { resetCount++ }
    }

    @Test
    fun `barge in handoff gate claims audio only once`() {
        val gate = BargeInHandoffGate()
        val handoff = BargeInHandoff(byteArrayOf(1), 16_000)

        assertEquals(handoff, gate.claim(handoff))
        assertEquals(null, gate.claim(handoff))
    }
}
