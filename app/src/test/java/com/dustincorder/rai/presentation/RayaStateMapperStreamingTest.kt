package com.dustincorder.rai.presentation

import com.dustincorder.rai.domain.RayaState
import org.junit.Assert.assertEquals
import org.junit.Test

class RayaStateMapperStreamingTest {
    @Test
    fun `thinking status changes after first visible streamed text`() {
        assertEquals("Размышляю", rayaUiStateFor(RayaState.Thinking).status)
        assertEquals("Отвечаю", rayaUiStateFor(RayaState.Thinking, streamingText = "Привет").status)
    }
}
