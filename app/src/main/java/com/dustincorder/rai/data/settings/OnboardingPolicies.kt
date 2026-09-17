package com.dustincorder.rai.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

enum class InitialDestination {
    Onboarding,
    Main,
}

suspend fun resolveInitialDestination(
    completionFlow: Flow<Boolean?>,
    legacySettingsLoader: suspend () -> Boolean,
): InitialDestination {
    val completion = completionFlow.first()
    val legacy = legacySettingsLoader()
    return if (OnboardingPolicy.shouldShow(completion, legacy)) {
        InitialDestination.Onboarding
    } else {
        InitialDestination.Main
    }
}

fun previousOnboardingStep(step: Int): Int? = step.takeIf { it > 0 }?.minus(1)

fun effectiveAppearance(persisted: AppearanceMode, onboardingOverride: AppearanceMode?): AppearanceMode =
    onboardingOverride ?: persisted
