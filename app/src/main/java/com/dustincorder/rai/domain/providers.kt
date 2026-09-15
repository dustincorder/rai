package com.dustincorder.rai.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

sealed interface SpeechRecognitionEvent {
    data class Partial(val text: String) : SpeechRecognitionEvent
    data class Final(val text: String, val detectedLanguageTag: String? = null) : SpeechRecognitionEvent
    data class Error(val reason: SpeechRecognitionErrorReason, val message: String) : SpeechRecognitionEvent
}

sealed interface SpeechRecognitionErrorReason {
    data object NoSpeech : SpeechRecognitionErrorReason
    data object NoMatch : SpeechRecognitionErrorReason
    data object Network : SpeechRecognitionErrorReason
    data object Permission : SpeechRecognitionErrorReason
    data object Other : SpeechRecognitionErrorReason
}

interface SpeechRecognitionProvider {
    val events: Flow<SpeechRecognitionEvent>

    suspend fun startListening(request: RecognitionRequest)
    fun cancel()
    fun release()
}

interface SpeechSynthesisProvider {
    suspend fun speak(text: String, locale: Locale = Locale("ru", "RU"))
    fun stop()
    fun shutdown()
}

interface ReplyProvider {
    suspend fun reply(messages: List<ConversationMessage>, languageTag: String?): RayaResponse

    /**
     * Streams visible answer text as it arrives, then commits the final validated
     * [RayaResponse]. Failures surface as flow exceptions. Defaults to a single
     * [ReplyEvent.Completed] for non-streaming transports.
     */
    fun streamReply(
        messages: List<ConversationMessage>,
        languageTag: String?,
    ): Flow<ReplyEvent> = flow {
        emit(ReplyEvent.Completed(reply(messages, languageTag)))
    }
}

/**
 * Domain stream contract. Only visible answer text ever reaches the UI;
 * emotion/language metadata is committed once via [Completed].
 */
sealed interface ReplyEvent {
    data class TextDelta(val text: String) : ReplyEvent
    data class Completed(val response: RayaResponse) : ReplyEvent
}

class MockReplyProvider : ReplyProvider {
    override suspend fun reply(messages: List<ConversationMessage>, languageTag: String?): RayaResponse =
        RayaResponse(text = "Я тебя слышу.", emotion = RayaEmotion.Calm, languageTag = null)
}

interface ConversationLanguageProvider {
    suspend fun currentLanguage(): ConversationLanguage
}
