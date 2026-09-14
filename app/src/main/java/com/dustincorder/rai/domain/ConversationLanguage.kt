package com.dustincorder.rai.domain

sealed interface ConversationLanguage {
    data object Auto : ConversationLanguage
    data object System : ConversationLanguage
    data class Explicit(val languageTag: String) : ConversationLanguage
}

data class RecognitionRequest(
    val language: ConversationLanguage,
    val systemLanguageTag: String,
)

data class RecognitionLanguagePlan(
    val languageTag: String?,
    val enableDetection: Boolean,
)

fun RecognitionRequest.toLanguagePlan(supportsDetection: Boolean): RecognitionLanguagePlan =
    when (val value = language) {
        ConversationLanguage.Auto -> RecognitionLanguagePlan(
            languageTag = if (supportsDetection) null else systemLanguageTag,
            enableDetection = supportsDetection,
        )
        ConversationLanguage.System -> RecognitionLanguagePlan(systemLanguageTag, false)
        is ConversationLanguage.Explicit -> RecognitionLanguagePlan(value.languageTag, false)
    }

fun ConversationLanguage.resolveLanguageTag(
    detectedLanguageTag: String?,
    systemLanguageTag: String,
): String = when (this) {
    ConversationLanguage.Auto -> detectedLanguageTag?.takeIf { it.isValidLanguageTag() } ?: systemLanguageTag
    ConversationLanguage.System -> systemLanguageTag
    is ConversationLanguage.Explicit -> languageTag
}

fun String.isValidLanguageTag(): Boolean {
    if (isBlank()) return false
    val locale = java.util.Locale.forLanguageTag(this)
    return locale.language.isNotBlank() && locale.toLanguageTag() != "und"
}

fun localNameResponse(resolvedLanguageTag: String?): String = when (resolvedLanguageTag?.primarySubtag()) {
    "ru" -> "Я здесь."
    "uk" -> "Я тут."
    "en" -> "I'm here."
    else -> "Я здесь."
}

private fun String.primarySubtag(): String? =
    substringBefore('-').takeIf { it.isNotBlank() }?.lowercase()
