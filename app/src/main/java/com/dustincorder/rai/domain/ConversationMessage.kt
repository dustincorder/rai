package com.dustincorder.rai.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ConversationRole {
    User,
    Assistant,
    Notice,
}

@Serializable
data class ConversationMessage(
    val role: ConversationRole,
    val text: String = "",
    val contextText: String = text,
    val noticeCode: RayaNoticeCode? = null,
)
