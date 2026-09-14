package com.dustincorder.rai.presentation

import com.dustincorder.rai.domain.RayaState
import com.dustincorder.rai.ui.raya.face.RayaFaceEmotion
import org.junit.Assert.assertEquals
import org.junit.Test

class RayaStateMapperTest {
    @Test
    fun `idle maps to calm face`() {
        assertEquals(RayaFaceEmotion.Calm, rayaUiStateFor(RayaState.Idle).face.emotion)
    }

    @Test
    fun `listening maps to listening face`() {
        assertEquals(RayaFaceEmotion.Listening, rayaUiStateFor(RayaState.Listening).face.emotion)
    }

    @Test
    fun `thinking maps to thinking face`() {
        assertEquals(RayaFaceEmotion.Thinking, rayaUiStateFor(RayaState.Thinking).face.emotion)
    }

    @Test
    fun `speaking maps to speaking face`() {
        val state = rayaUiStateFor(RayaState.Speaking("test"))

        assertEquals(RayaFaceEmotion.Speaking, state.face.emotion)
        assertEquals(0.72f, state.face.mouthAmplitude)
    }

    @Test
    fun `error maps to error face`() {
        assertEquals(
            RayaFaceEmotion.Error,
            rayaUiStateFor(RayaState.Error("failure")).face.emotion,
        )
    }
}
