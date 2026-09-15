package com.dustincorder.rai.domain

enum class ConversationRole {
    User,
    Assistant,
    Notice,
}

data class ConversationMessage(
    val role: ConversationRole,
    val text: String = "",
    val contextText: String = text,
    val noticeCode: RayaNoticeCode? = null,
)
