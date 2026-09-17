package com.dustincorder.rai.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RayaErrorCode {
    ReplyUnavailable,
    RecognitionStartFailed,
    RecognitionFailed,
    VoicePipelineFailed,
}

@Serializable
enum class RayaNoticeCode {
    InactivityEnded,
}
