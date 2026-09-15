package com.dustincorder.rai.presentation

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.InteractionMode
import com.dustincorder.rai.presentation.model.RayaFaceState

data class RayaUiState(
    val face: RayaFaceState = RayaFaceState(),
    val status: String = "Готова к разговору",
    val userText: String = "",
    val conversation: List<ConversationMessage> = emptyList(),
    val errorMessage: String? = null,
    val isBusy: Boolean = false,
    val isSpeaking: Boolean = false,
    val userTurnRevision: Long = 0L,
    val interactionMode: InteractionMode = InteractionMode.Text,
    val voiceSessionActive: Boolean = false,
    val microphoneEnabled: Boolean = true,
)
