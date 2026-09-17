package com.dustincorder.rai.ui

import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.normalizeBaseUrl
import com.dustincorder.rai.presentation.ApiKeyStatus

internal const val ONBOARDING_STEP_COUNT = 6

internal fun canContinueAiSetup(
    draft: AppSettings,
    typedKey: String,
    statusProvider: LlmProviderPreset?,
    status: ApiKeyStatus,
): Boolean {
    val validProviderConfig = draft.provider != LlmProviderPreset.Custom ||
        runCatching { normalizeBaseUrl(draft.customBaseUrl) }.isSuccess
    val authAvailable = !draft.provider.requiresApiKey || typedKey.isNotBlank() ||
        (statusProvider == draft.provider && status == ApiKeyStatus.Configured)
    return validProviderConfig && authAvailable && draft.resolvedModelId().isNotBlank()
}
