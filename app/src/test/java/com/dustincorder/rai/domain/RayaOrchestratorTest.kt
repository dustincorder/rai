package com.dustincorder.rai.domain

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RayaOrchestratorTest {
    @Test
    fun `mock demo follows listening thinking speaking idle sequence`() = runTest {
        val orchestrator = RayaOrchestrator(
            scope = this,
            listeningDelayMs = 10,
            thinkingDelayMs = 20,
            speakingDelayMs = 30,
        )

        orchestrator.startMockDemo()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)

        advanceTimeBy(10)
        runCurrent()
        assertEquals(RayaState.Thinking, orchestrator.state.value)

        advanceTimeBy(20)
        runCurrent()
        assertEquals(
            RayaState.Speaking("Я здесь. Системы работают нормально."),
            orchestrator.state.value,
        )

        advanceTimeBy(30)
        runCurrent()
        assertEquals(RayaState.Idle, orchestrator.state.value)
    }

    @Test
    fun `starting demo again cancels previous demo`() = runTest {
        val orchestrator = RayaOrchestrator(
            scope = this,
            listeningDelayMs = 100,
            thinkingDelayMs = 100,
            speakingDelayMs = 100,
        )

        orchestrator.startMockDemo()
        advanceTimeBy(50)
        orchestrator.startMockDemo()
        advanceUntilIdle()

        assertEquals(RayaState.Idle, orchestrator.state.value)
    }

    @Test
    fun `reset cancels demo and returns idle`() = runTest {
        val orchestrator = RayaOrchestrator(
            scope = this,
            listeningDelayMs = 100,
            thinkingDelayMs = 100,
            speakingDelayMs = 100,
        )

        orchestrator.startMockDemo()
        advanceTimeBy(20)
        orchestrator.reset()
        advanceUntilIdle()

        assertEquals(RayaState.Idle, orchestrator.state.value)
    }
}
