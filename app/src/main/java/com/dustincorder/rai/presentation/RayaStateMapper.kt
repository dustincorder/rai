package com.dustincorder.rai.presentation

import com.dustincorder.rai.domain.RayaState
import com.dustincorder.rai.ui.raya.face.RayaAntennaMotion
import com.dustincorder.rai.ui.raya.face.RayaFaceEmotion
import com.dustincorder.rai.ui.raya.face.RayaFaceState
import com.dustincorder.rai.ui.raya.face.RayaGaze

fun rayaUiStateFor(state: RayaState): RayaUiState = when (state) {
    RayaState.Idle -> RayaUiState(
        face = RayaFaceState(),
    )
    RayaState.Listening -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Listening,
            gaze = RayaGaze.Alert,
            antennaMotion = RayaAntennaMotion.Responsive,
        ),
        status = "Слушаю",
        userText = "Я слушаю тебя...",
        responseText = "",
        isBusy = true,
    )
    RayaState.Thinking -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Thinking,
            gaze = RayaGaze.Up,
            antennaMotion = RayaAntennaMotion.Thinking,
        ),
        status = "Размышляю",
        userText = "Привет, Райя",
        responseText = "Собираю ответ...",
        isBusy = true,
    )
    is RayaState.Speaking -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Speaking,
            mouthAmplitude = 0.72f,
            gaze = RayaGaze.Center,
            antennaMotion = RayaAntennaMotion.Responsive,
        ),
        status = "Говорю",
        userText = "Привет, Райя",
        responseText = state.text,
        isBusy = true,
    )
    is RayaState.Error -> RayaUiState(
        face = RayaFaceState(
            emotion = RayaFaceEmotion.Error,
            gaze = RayaGaze.Wide,
            antennaMotion = RayaAntennaMotion.Alert,
        ),
        status = "Сбой системы",
        userText = "Привет, Райя",
        responseText = state.message,
    )
}
