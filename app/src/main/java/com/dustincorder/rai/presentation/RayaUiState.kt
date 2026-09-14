package com.dustincorder.rai.presentation

enum class RayaEmotion {
    Calm,
    Listening,
    Thinking,
    Speaking,
    Error,
}

data class RayaUiState(
    val emotion: RayaEmotion = RayaEmotion.Calm,
    val status: String = "Готова к разговору",
    val userText: String = "Нажми кнопку, чтобы начать демонстрацию.",
    val responseText: String = "Я рядом и жду сигнала.",
    val isBusy: Boolean = false,
)
