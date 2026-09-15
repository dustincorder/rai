package com.dustincorder.rai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceV2Test {
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
}
