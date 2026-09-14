package com.dustincorder.rai.presentation

import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.RayaState
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.presentation.model.RayaFaceState
import com.dustincorder.rai.presentation.model.RayaGaze

fun rayaUiStateFor(
    state: RayaState,
    recognizedText: String = "",
    conversation: List<ConversationMessage> = emptyList(),
): RayaUiState = when (state) {
    RayaState.Idle -> RayaUiState(
        face = RayaFaceState(),
        conversation = conversation,
    )
    RayaState.Listening -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Listening,
            gaze = RayaGaze.Alert,
        ),
        status = "Слушаю",
        userText = recognizedText,
        conversation = conversation,
        isBusy = true,
    )
    RayaState.Thinking -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Thinking,
            gaze = RayaGaze.Up,
        ),
        status = "Размышляю",
        conversation = conversation,
        isBusy = true,
    )
    is RayaState.Speaking -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Speaking,
            gaze = RayaGaze.Center,
        ),
        status = "Говорю",
        conversation = conversation,
        isBusy = true,
    )
    is RayaState.Error -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Error,
            gaze = RayaGaze.Wide,
        ),
        status = "Сбой системы",
        conversation = conversation,
    )
}
