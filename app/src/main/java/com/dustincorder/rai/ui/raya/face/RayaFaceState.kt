package com.dustincorder.rai.ui.raya.face

enum class RayaFaceEmotion {
    Calm,
    Listening,
    Thinking,
    Speaking,
    Happy,
    Curious,
    Concerned,
    Error,
}

enum class RayaGaze {
    Center,
    Alert,
    Up,
    Down,
    Wide,
}

enum class RayaAntennaMotion {
    Resting,
    Responsive,
    Thinking,
    Alert,
}

/** Visual-only state. Domain orchestration must not depend on these fields. */
data class RayaFaceState(
    val emotion: RayaFaceEmotion = RayaFaceEmotion.Calm,
    val blinking: Boolean = false,
    val mouthAmplitude: Float = 0f,
    val gaze: RayaGaze = RayaGaze.Center,
    val antennaMotion: RayaAntennaMotion = RayaAntennaMotion.Resting,
)
