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

        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(speech))
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(silence))
    }

    @Test
    fun `long trailing silence ends utterance`() {
        val detector = VoiceActivityDetector(
            minimumSpeechMs = 100,
            trailingSilenceMs = 300,
            sampleRateHz = 1_000,
        )
        assertEquals(EndpointDecision.Continue, detector.acceptPcm16(ShortArray(200) { 4_000 }))
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
