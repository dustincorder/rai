package com.dustincorder.rai.ui

import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.domain.ActiveConversation
import com.dustincorder.rai.ui.raya.face.RayaFaceRenderMode

enum class SettingsNavigationAction {
    Open,
    EndVoiceThenOpen,
}

fun activeConversationLabel(active: ActiveConversation, title: String?, newChat: String, temporary: String): String = when (active) {
    ActiveConversation.NewDraft -> newChat
    ActiveConversation.Temporary -> temporary
    is ActiveConversation.Persistent -> title?.takeIf { it.isNotBlank() } ?: newChat
}

fun modelDisplayLabel(settings: AppSettings): String {
    val provider = when (settings.provider) {
        LlmProviderPreset.OpenAI -> "OpenAI"
        LlmProviderPreset.Groq -> "Groq"
        LlmProviderPreset.Anthropic -> "Anthropic"
        LlmProviderPreset.Gemini -> "Gemini"
        LlmProviderPreset.Custom -> "Custom"
    }
    return "$provider · ${settings.resolvedModelId()}"
}

fun faceRenderMode(active: ActiveConversation): RayaFaceRenderMode =
    if (active is ActiveConversation.Temporary) RayaFaceRenderMode.Temporary else RayaFaceRenderMode.Normal

fun settingsNavigationAction(voiceSessionActive: Boolean): SettingsNavigationAction =
    if (voiceSessionActive) SettingsNavigationAction.EndVoiceThenOpen else SettingsNavigationAction.Open
