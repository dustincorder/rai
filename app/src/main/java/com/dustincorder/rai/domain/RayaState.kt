package com.dustincorder.rai.domain

sealed interface RayaState {
    data object Idle : RayaState
    data object Listening : RayaState
    data object Thinking : RayaState
    data class Speaking(val text: String) : RayaState
    data class Error(val message: String, val code: RayaErrorCode? = null) : RayaState
}
