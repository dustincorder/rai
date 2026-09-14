package com.dustincorder.rai.domain

import kotlinx.coroutines.flow.Flow
import java.util.Locale

sealed interface SpeechRecognitionEvent {
    data class Partial(val text: String) : SpeechRecognitionEvent
    data class Final(val text: String, val detectedLanguageTag: String? = null) : SpeechRecognitionEvent
    data class Error(val message: String) : SpeechRecognitionEvent
}

interface SpeechRecognitionProvider {
    val events: Flow<SpeechRecognitionEvent>

    fun startListening(request: RecognitionRequest)
    fun cancel()
    fun release()
}

interface SpeechSynthesisProvider {
    suspend fun speak(text: String, locale: Locale = Locale("ru", "RU"))
    fun stop()
    fun shutdown()
}

interface ReplyProvider {
    suspend fun reply(input: String, languageTag: String?): String
}

class MockReplyProvider : ReplyProvider {
    override suspend fun reply(input: String, languageTag: String?): String = "Я тебя слышу."
}

interface ConversationLanguageProvider {
    suspend fun currentLanguage(): ConversationLanguage
}
