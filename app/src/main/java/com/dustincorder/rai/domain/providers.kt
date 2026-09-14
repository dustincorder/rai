package com.dustincorder.rai.domain

import kotlinx.coroutines.flow.Flow
import java.util.Locale

sealed interface SpeechRecognitionEvent {
    data class Partial(val text: String) : SpeechRecognitionEvent
    data class Final(val text: String) : SpeechRecognitionEvent
    data class Error(val message: String) : SpeechRecognitionEvent
}

interface SpeechRecognitionProvider {
    val events: Flow<SpeechRecognitionEvent>

    fun startListening(locale: Locale = Locale("ru", "RU"))
    fun cancel()
    fun release()
}

interface SpeechSynthesisProvider {
    suspend fun speak(text: String, locale: Locale = Locale("ru", "RU"))
    fun stop()
    fun shutdown()
}

interface ReplyProvider {
    suspend fun reply(input: String): String
}

class MockReplyProvider : ReplyProvider {
    override suspend fun reply(input: String): String = "Я тебя слышу."
}
