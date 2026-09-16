package com.dustincorder.rai.presentation

import com.dustincorder.rai.domain.RayaState
import org.junit.Assert.assertEquals
import org.junit.Test

class RayaStateMapperStreamingTest {
    @Test
    fun `thinking status changes after first visible streamed text`() {
        assertEquals(com.dustincorder.rai.R.string.status_thinking, rayaUiStateFor(RayaState.Thinking).statusResId)
        assertEquals(com.dustincorder.rai.R.string.status_answering, rayaUiStateFor(RayaState.Thinking, streamingText = "Привет").statusResId)
    }
}
