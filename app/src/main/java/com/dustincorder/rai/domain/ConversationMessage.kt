package com.dustincorder.rai.domain

enum class ConversationRole {
    User,
    Assistant,
}

data class ConversationMessage(
    val role: ConversationRole,
    val text: String,
)