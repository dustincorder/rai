package com.dustincorder.rai.presentation

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.ConversationRole
import com.dustincorder.rai.domain.RayaEmotion
import com.dustincorder.rai.domain.RayaState
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `speaking maps to the semantic emotion face with speaking animation`() {
        val state = rayaUiStateFor(
            RayaState.Speaking("test"),
            semanticEmotion = RayaEmotion.Happy,
        )

        assertEquals(RayaFaceEmotion.Happy, state.face.emotion)
        assertTrue("speaking animation flag must be set", state.face.speaking)
        assertTrue(state.isSpeaking)
    }

    @Test
    fun `error still has priority over semantic emotion`() {
        val state = rayaUiStateFor(
            RayaState.Error("failure"),
            semanticEmotion = RayaEmotion.Happy,
        )

        assertEquals(RayaFaceEmotion.Error, state.face.emotion)
        assertFalse(state.face.speaking)
    }

    @Test
    fun `listening and thinking interaction states override semantic emotion`() {
        assertEquals(
            RayaFaceEmotion.Listening,
            rayaUiStateFor(RayaState.Listening, semanticEmotion = RayaEmotion.Angry).face.emotion,
        )
        assertEquals(
            RayaFaceEmotion.Thinking,
            rayaUiStateFor(RayaState.Thinking, semanticEmotion = RayaEmotion.Surprised).face.emotion,
        )
        assertEquals(
            RayaFaceEmotion.SemanticThinking,
            rayaUiStateFor(RayaState.Idle, semanticEmotion = RayaEmotion.Thinking).face.emotion,
        )
    }

    @Test
    fun `after response idle shows semantic emotion`() {
        val state = rayaUiStateFor(RayaState.Idle, semanticEmotion = RayaEmotion.Concerned)

        assertEquals(RayaFaceEmotion.Concerned, state.face.emotion)
        assertFalse(state.isSpeaking)
    }

    @Test
    fun `each semantic emotion maps to the expected face`() {
        val mapping = mapOf(
            RayaEmotion.Calm to RayaFaceEmotion.Calm,
            RayaEmotion.Happy to RayaFaceEmotion.Happy,
            RayaEmotion.Excited to RayaFaceEmotion.Excited,
            RayaEmotion.Playful to RayaFaceEmotion.Playful,
            RayaEmotion.Curious to RayaFaceEmotion.Curious,
            RayaEmotion.Thinking to RayaFaceEmotion.SemanticThinking,
            RayaEmotion.Skeptical to RayaFaceEmotion.Skeptical,
            RayaEmotion.Confused to RayaFaceEmotion.Confused,
            RayaEmotion.Concerned to RayaFaceEmotion.Concerned,
            RayaEmotion.Sad to RayaFaceEmotion.Sad,
            RayaEmotion.Embarrassed to RayaFaceEmotion.Embarrassed,
            RayaEmotion.Surprised to RayaFaceEmotion.Surprised,
            RayaEmotion.Angry to RayaFaceEmotion.Angry,
            RayaEmotion.Annoyed to RayaFaceEmotion.Annoyed,
            RayaEmotion.Tired to RayaFaceEmotion.Tired,
        )
        mapping.forEach { (emotion, face) ->
            assertEquals(face, emotion.toFaceEmotion())
        }
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
