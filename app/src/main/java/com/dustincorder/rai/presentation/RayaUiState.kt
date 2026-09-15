package com.dustincorder.rai.presentation

import androidx.annotation.StringRes
import com.dustincorder.rai.R

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.InteractionMode
import com.dustincorder.rai.presentation.model.RayaFaceState

data class RayaUiState(
    val face: RayaFaceState = RayaFaceState(),
    val status: String = "Готова к разговору",
    @StringRes val statusResId: Int = R.string.status_ready,
    val userText: String = "",
    val conversation: List<ConversationMessage> = emptyList(),
    val errorMessage: String? = null,
    val isBusy: Boolean = false,
    val isSpeaking: Boolean = false,
    val streamingText: String = "",
    val userTurnRevision: Long = 0L,
    val interactionMode: InteractionMode = InteractionMode.Text,
    val voiceSessionActive: Boolean = false,
    val microphoneEnabled: Boolean = true,
)
