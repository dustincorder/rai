package com.dustincorder.rai.speech

import com.dustincorder.rai.domain.BargeInHandoff
import com.dustincorder.rai.domain.BargeInHandoffGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BargeInGenerationTest {
    @Test
    fun `one handoff generation can callback only once`() {
        val gate = BargeInHandoffGate()
        val first = BargeInHandoff(byteArrayOf(1), 16_000)
        val second = BargeInHandoff(byteArrayOf(2), 16_000)

        assertNotNull(gate.claim(first))
        assertEquals(null, gate.claim(second))
    }

    @Test
    fun `a new generation owns a separate handoff gate`() {
        val generationOne = BargeInHandoffGate()
        val generationTwo = BargeInHandoffGate()

        assertNotNull(generationOne.claim(BargeInHandoff(byteArrayOf(1), 16_000)))
        assertNotNull(generationTwo.claim(BargeInHandoff(byteArrayOf(2), 16_000)))
    }
}
