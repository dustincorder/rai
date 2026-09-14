package com.dustincorder.rai.presentation

import com.dustincorder.rai.presentation.model.RayaFaceState

data class RayaUiState(
    val face: RayaFaceState = RayaFaceState(),
    val status: String = "Готова к разговору",
    val userText: String = "Нажми кнопку, чтобы начать демонстрацию.",
    val responseText: String = "Я рядом и жду сигнала.",
    val isBusy: Boolean = false,
)
