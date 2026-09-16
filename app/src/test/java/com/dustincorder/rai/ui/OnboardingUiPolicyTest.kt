package com.dustincorder.rai.ui

import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.presentation.ApiKeyStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingUiPolicyTest {
    @Test
    fun `onboarding has six logical steps`() {
        assertTrue(ONBOARDING_STEP_COUNT == 6)
    }

    @Test
    fun `ai setup requires provider auth and model`() {
        val draft = AppSettings(provider = LlmProviderPreset.Groq, modelId = "model")
        assertFalse(canContinueAiSetup(draft, "", LlmProviderPreset.Groq, ApiKeyStatus.Missing))
        assertTrue(canContinueAiSetup(draft, "typed-key", LlmProviderPreset.Groq, ApiKeyStatus.Missing))
        assertFalse(canContinueAiSetup(draft.copy(modelId = ""), "typed-key", LlmProviderPreset.Groq, ApiKeyStatus.Missing))
    }

    @Test
    fun `ai setup accepts configured key only for current provider`() {
        val draft = AppSettings(provider = LlmProviderPreset.Groq, modelId = "model")

        assertFalse(canContinueAiSetup(draft, "", LlmProviderPreset.OpenAI, ApiKeyStatus.Configured))
        assertTrue(canContinueAiSetup(draft, "", LlmProviderPreset.Groq, ApiKeyStatus.Configured))
    }

    @Test
    fun `custom provider requires valid base url`() {
        val draft = AppSettings(
            provider = LlmProviderPreset.Custom,
            customBaseUrl = "not-a-url",
            modelId = "model",
        )
        assertFalse(canContinueAiSetup(draft, "", null, ApiKeyStatus.Unknown))
    }
}
