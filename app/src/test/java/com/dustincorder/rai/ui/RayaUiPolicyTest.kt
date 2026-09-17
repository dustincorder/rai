package com.dustincorder.rai.ui

import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.domain.ActiveConversation
import com.dustincorder.rai.ui.raya.face.RayaFaceRenderMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RayaUiPolicyTest {
    @Test
    fun `conversation labels identify all modes`() {
        assertEquals("New", activeConversationLabel(ActiveConversation.NewDraft, null, "New", "Temporary"))
        assertEquals("Temporary", activeConversationLabel(ActiveConversation.Temporary, null, "New", "Temporary"))
        assertEquals("Project", activeConversationLabel(ActiveConversation.Persistent("id"), "Project", "New", "Temporary"))
    }

    @Test
    fun `model label uses current resolved provider and model`() {
        assertEquals("Groq · llama", modelDisplayLabel(AppSettings(provider = LlmProviderPreset.Groq, modelId = "llama")))
    }

    @Test
    fun `temporary mode selects outlined face renderer`() {
        assertEquals(RayaFaceRenderMode.Temporary, faceRenderMode(ActiveConversation.Temporary))
        assertEquals(RayaFaceRenderMode.Normal, faceRenderMode(ActiveConversation.NewDraft))
    }

    @Test
    fun `settings navigation ends active voice before opening`() {
        assertEquals(SettingsNavigationAction.EndVoiceThenOpen, settingsNavigationAction(true))
        assertEquals(SettingsNavigationAction.Open, settingsNavigationAction(false))
    }
}
