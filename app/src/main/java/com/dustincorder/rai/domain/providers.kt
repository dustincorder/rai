package com.dustincorder.rai.domain

/** Future boundary for a local or remote language model. */
interface LlmProvider {
    suspend fun generateReply(input: String): String
}

/** Future boundary for microphone-to-text implementations. */
interface SpeechRecognitionProvider {
    suspend fun recognize(): String
}

/** Future boundary for text-to-speech implementations. */
interface SpeechSynthesisProvider {
    suspend fun speak(text: String)
}
