package com.dustincorder.rai.presentation.model

enum class RayaFaceEmotion {
    Calm,
    Listening,
    Thinking,
    Speaking,
    Happy,
    Curious,
    Concerned,
    Surprised,
    Angry,
    Error,
}

enum class RayaGaze {
    Center,
    Alert,
    Up,
    Down,
    Wide,
}

/** Visual state produced by presentation and rendered by the face UI. */
data class RayaFaceState(
    val emotion: RayaFaceEmotion = RayaFaceEmotion.Calm,
    val blinking: Boolean = false,
    val gaze: RayaGaze = RayaGaze.Center,
)
