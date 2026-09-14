package com.dustincorder.rai.presentation

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import com.dustincorder.rai.domain.RayaState
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }

    @Test
    fun `error maps to error face`() {
        assertEquals(
            RayaFaceEmotion.Error,
            rayaUiStateFor(RayaState.Error("failure")).face.emotion,
        )
    }

    @Test
    fun `error exposes safe message to the ui`() {
        val state = rayaUiStateFor(RayaState.Error("Не удалось найти сервер провайдера."))

        assertEquals("Не удалось найти сервер провайдера.", state.errorMessage)
    }

    @Test
    fun `non error states expose no error message`() {
        val conversation = listOf(ConversationMessage(ConversationRole.User, "привет"))

        assertNull(rayaUiStateFor(RayaState.Idle, conversation = conversation).errorMessage)
        assertNull(rayaUiStateFor(RayaState.Listening, "частично", conversation).errorMessage)
        assertNull(rayaUiStateFor(RayaState.Thinking, conversation = conversation).errorMessage)
        assertNull(rayaUiStateFor(RayaState.Speaking("ответ"), conversation = conversation).errorMessage)
    }

    @Test
    fun `error message is transient and does not join conversation history`() {
        val conversation = listOf(ConversationMessage(ConversationRole.User, "что такое Марс?"))
        val error = rayaUiStateFor(RayaState.Error("Не удалось найти сервер провайдера."), conversation = conversation)

        assertEquals("Не удалось найти сервер провайдера.", error.errorMessage)
        assertEquals(conversation, error.conversation)

        val idle = rayaUiStateFor(RayaState.Idle, conversation = conversation)
        assertNull(idle.errorMessage)
        assertEquals(conversation, idle.conversation)
    }
}