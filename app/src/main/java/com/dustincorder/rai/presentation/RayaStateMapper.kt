package com.dustincorder.rai.presentation

import com.dustincorder.rai.R
import com.dustincorder.rai.domain.ConversationMessage
import com.dustincorder.rai.domain.InteractionMode
import com.dustincorder.rai.domain.RayaEmotion
import com.dustincorder.rai.domain.RayaState
import com.dustincorder.rai.presentation.model.RayaFaceEmotion
import com.dustincorder.rai.presentation.model.RayaFaceState
import com.dustincorder.rai.presentation.model.RayaGaze

/** Maps the structured semantic emotion onto the existing visual face expressions. */
fun RayaEmotion.toFaceEmotion(): RayaFaceEmotion = when (this) {
    RayaEmotion.Calm -> RayaFaceEmotion.Calm
    RayaEmotion.Happy -> RayaFaceEmotion.Happy
    RayaEmotion.Excited -> RayaFaceEmotion.Excited
    RayaEmotion.Playful -> RayaFaceEmotion.Playful
    RayaEmotion.Curious -> RayaFaceEmotion.Curious
    RayaEmotion.Thinking -> RayaFaceEmotion.SemanticThinking
    RayaEmotion.Skeptical -> RayaFaceEmotion.Skeptical
    RayaEmotion.Confused -> RayaFaceEmotion.Confused
    RayaEmotion.Concerned -> RayaFaceEmotion.Concerned
    RayaEmotion.Sad -> RayaFaceEmotion.Sad
    RayaEmotion.Embarrassed -> RayaFaceEmotion.Embarrassed
    RayaEmotion.Surprised -> RayaFaceEmotion.Surprised
    RayaEmotion.Angry -> RayaFaceEmotion.Angry
    RayaEmotion.Annoyed -> RayaFaceEmotion.Annoyed
    RayaEmotion.Tired -> RayaFaceEmotion.Tired
}

private val RayaEmotion.semanticGaze: RayaGaze
    get() = when (this) {
        RayaEmotion.Curious -> RayaGaze.Alert
        RayaEmotion.Thinking -> RayaGaze.Side
        RayaEmotion.Skeptical -> RayaGaze.Side
        RayaEmotion.Confused -> RayaGaze.Center
        RayaEmotion.Concerned -> RayaGaze.Up
        RayaEmotion.Sad,
        RayaEmotion.Tired,
        -> RayaGaze.Down
        RayaEmotion.Embarrassed -> RayaGaze.Center
        else -> RayaGaze.Center
    }

fun rayaUiStateFor(
    state: RayaState,
    recognizedText: String = "",
    conversation: List<ConversationMessage> = emptyList(),
    interactionMode: InteractionMode = InteractionMode.Text,
    voiceSessionActive: Boolean = false,
    microphoneEnabled: Boolean = true,
    semanticEmotion: RayaEmotion = RayaEmotion.Calm,
    userTurnRevision: Long = 0L,
    streamingText: String = "",
): RayaUiState = when (state) {
    RayaState.Idle -> RayaUiState(
        face = RayaFaceState(
            emotion = semanticEmotion.toFaceEmotion(),
            gaze = semanticEmotion.semanticGaze,
        ),
        conversation = conversation,
        interactionMode = interactionMode,
        voiceSessionActive = voiceSessionActive,
        microphoneEnabled = microphoneEnabled,
        userTurnRevision = userTurnRevision,
        streamingText = streamingText,
    )
    RayaState.Listening -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Listening,
            gaze = RayaGaze.Alert,
        ),
        statusResId = R.string.status_listening,
        userText = recognizedText,
        conversation = conversation,
        isBusy = true,
        interactionMode = interactionMode,
        voiceSessionActive = voiceSessionActive,
        microphoneEnabled = microphoneEnabled,
        userTurnRevision = userTurnRevision,
        streamingText = streamingText,
    )
    RayaState.Thinking -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Thinking,
            gaze = RayaGaze.Up,
        ),
        statusResId = if (streamingText.isBlank()) R.string.status_thinking else R.string.status_answering,
        conversation = conversation,
        isBusy = true,
        interactionMode = interactionMode,
        voiceSessionActive = voiceSessionActive,
        microphoneEnabled = microphoneEnabled,
        userTurnRevision = userTurnRevision,
        streamingText = streamingText,
    )
    is RayaState.Speaking -> RayaUiState(
        face = RayaFaceState(
            emotion = semanticEmotion.toFaceEmotion(),
            gaze = semanticEmotion.semanticGaze,
            speaking = true,
        ),
        statusResId = R.string.status_speaking,
        conversation = conversation,
        isBusy = true,
        isSpeaking = true,
        interactionMode = interactionMode,
        voiceSessionActive = voiceSessionActive,
        microphoneEnabled = microphoneEnabled,
        userTurnRevision = userTurnRevision,
        streamingText = streamingText,
    )
    is RayaState.Error -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Error,
            gaze = RayaGaze.Wide,
        ),
        statusResId = R.string.status_error,
        conversation = conversation,
        errorMessage = state.message,
        errorCode = state.code,
        interactionMode = interactionMode,
        voiceSessionActive = voiceSessionActive,
        microphoneEnabled = microphoneEnabled,
        userTurnRevision = userTurnRevision,
        streamingText = streamingText,
    )
}
