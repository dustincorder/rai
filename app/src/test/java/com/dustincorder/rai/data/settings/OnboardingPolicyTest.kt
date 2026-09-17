package com.dustincorder.rai.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    @Test fun `explicit false overrides legacy installation`() {
        assertTrue(OnboardingPolicy.shouldShow(completed = false, legacySettingsExist = true))
    }

    @Test fun `raya appearance is default and persists in settings value`() {
        assertTrue(AppSettings().appearanceMode == AppearanceMode.Raya)
        val selected = AppSettings(appearanceMode = AppearanceMode.Dynamic)
        assertTrue(selected.appearanceMode == AppearanceMode.Dynamic)
    }

    @Test
    fun `bootstrap resolver waits for real completion emission`() = runBlocking {
        val completion = MutableSharedFlow<Boolean?>(replay = 0)
        var legacyWasRead = false
        val resolver = launch {
            assertEquals(
                InitialDestination.Onboarding,
                resolveInitialDestination(completion) { legacyWasRead = true; true },
            )
        }
        kotlinx.coroutines.yield()
        assertFalse(legacyWasRead)
        completion.emit(false)
        resolver.join()
        assertTrue(legacyWasRead)
    }

    @Test fun `bootstrap resolution covers all resolved input combinations`() = runBlocking {
        suspend fun resolve(value: Boolean?, legacy: Boolean) = resolveInitialDestination(
            kotlinx.coroutines.flow.flowOf(value),
        ) { legacy }
        assertEquals(InitialDestination.Main, resolve(true, false))
        assertEquals(InitialDestination.Onboarding, resolve(false, true))
        assertEquals(InitialDestination.Main, resolve(null, true))
        assertEquals(InitialDestination.Onboarding, resolve(null, false))
    }

    @Test fun `back policy has no previous step at first`() {
        assertEquals(2, previousOnboardingStep(3))
        assertEquals(0, previousOnboardingStep(1))
        assertEquals(null, previousOnboardingStep(0))
    }

    @Test fun `appearance preview overrides persisted only while active`() {
        assertEquals(AppearanceMode.Dynamic, effectiveAppearance(AppearanceMode.Raya, AppearanceMode.Dynamic))
        assertEquals(AppearanceMode.Raya, effectiveAppearance(AppearanceMode.Raya, null))
        assertEquals(AppearanceMode.Raya, effectiveAppearance(AppearanceMode.Dynamic, AppearanceMode.Raya))
    }
}
