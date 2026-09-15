package com.dustincorder.rai.presentation.model

enum class RayaFaceEmotion {
    Calm,
    Listening,
    Thinking,
    SemanticThinking,
    Happy,
    Excited,
    Playful,
    Curious,
    Skeptical,
    Confused,
    Concerned,
    Sad,
    Embarrassed,
    Surprised,
    Angry,
    Annoyed,
    Tired,
    Error,
}

enum class RayaGaze {
    Center,
    Alert,
    Side,
    Up,
    Down,
    Wide,
}

/** Visual state produced by presentation and rendered by the face UI. */
data class RayaFaceState(
    val emotion: RayaFaceEmotion = RayaFaceEmotion.Calm,
    val blinking: Boolean = false,
    val gaze: RayaGaze = RayaGaze.Center,
    val speaking: Boolean = false,
)
