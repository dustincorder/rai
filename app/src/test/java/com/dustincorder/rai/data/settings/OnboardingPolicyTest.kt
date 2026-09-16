package com.dustincorder.rai.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPolicyTest {
    @Test fun `fresh install requires onboarding`() {
        assertTrue(OnboardingPolicy.shouldShow(completed = null, legacySettingsExist = false))
    }

    @Test fun `legacy installation skips onboarding without flag`() {
        assertFalse(OnboardingPolicy.shouldShow(completed = null, legacySettingsExist = true))
    }

    @Test fun `explicit completed flag skips onboarding`() {
        assertFalse(OnboardingPolicy.shouldShow(completed = true, legacySettingsExist = false))
    }

    @Test fun `explicit false shows onboarding even for non legacy`() {
        assertTrue(OnboardingPolicy.shouldShow(completed = false, legacySettingsExist = false))
    }

    @Test fun `raya appearance is default and persists in settings value`() {
        assertTrue(AppSettings().appearanceMode == AppearanceMode.Raya)
        val selected = AppSettings(appearanceMode = AppearanceMode.Dynamic)
        assertTrue(selected.appearanceMode == AppearanceMode.Dynamic)
    }
}
