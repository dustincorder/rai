package com.dustincorder.rai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dustincorder.rai.R
import com.dustincorder.rai.data.settings.AppearanceMode
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.data.settings.OnboardingPolicy
import com.dustincorder.rai.data.llm.ModelListState
import com.dustincorder.rai.data.llm.chatModels
import com.dustincorder.rai.presentation.ApiKeyStatus
import com.dustincorder.rai.presentation.RayaViewModel
import com.dustincorder.rai.presentation.SettingsViewModel
import com.dustincorder.rai.ui.designsystem.RayaPrimaryButton
import com.dustincorder.rai.ui.designsystem.RayaSection
import com.dustincorder.rai.ui.designsystem.RayaSpacing
import com.dustincorder.rai.ui.designsystem.RayaSurface
import com.dustincorder.rai.ui.theme.RayaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private object RayaRoute {
    const val Onboarding = "onboarding"
    const val Main = "main"
    const val Settings = "settings"
}

@Composable
fun RayaApp(
    application: com.dustincorder.rai.RayaApplication,
    rayaViewModel: RayaViewModel,
    settingsViewModel: SettingsViewModel,
    onVoiceChatClick: () -> Unit,
) {
    val navController = rememberNavController()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val modelListState by settingsViewModel.modelListState.collectAsStateWithLifecycle()
    val completed by application.onboardingStore.completed.collectAsStateWithLifecycle(initialValue = null)
    val uiState by rayaViewModel.uiState.collectAsStateWithLifecycle()
    var legacyInstallation by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        val persisted = application.settingsRepository.hasPersistedSettings()
        val keyConfigured = LlmProviderPreset.entries.any {
            application.apiKeyStore.isConfigured(it)
        }
        legacyInstallation = persisted || keyConfigured
    }

    LaunchedEffect(completed, legacyInstallation) {
        if (legacyInstallation != null && !OnboardingPolicy.shouldShow(completed, legacyInstallation == true)) {
            navController.navigate(RayaRoute.Main) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    RayaTheme(appearanceMode = settings.appearanceMode) {
        NavHost(
            navController = navController,
            startDestination = RayaRoute.Onboarding,
        ) {
            composable(RayaRoute.Onboarding) {
                OnboardingScreen(
                    stored = settings,
                    apiKeyStatus = settingsViewModel.apiKeyStatus.collectAsStateWithLifecycle().value,
                    modelListState = modelListState,
                    onRefreshModels = { draft, key -> settingsViewModel.refreshModelList(draft, key) },
                    onProviderChanged = settingsViewModel::refreshApiKeyStatus,
                    onSave = { draft, key -> settingsViewModel.saveForOnboarding(draft, key) },
                    onComplete = {
                        application.onboardingStore.markCompleted()
                        navController.navigate(RayaRoute.Main) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
            composable(RayaRoute.Main) {
                RayaScreen(
                    state = uiState,
                    onSubmitText = rayaViewModel::submitText,
                    onVoiceChatClick = onVoiceChatClick,
                    onEndVoiceSession = rayaViewModel::endVoiceSession,
                    onToggleMicrophone = rayaViewModel::toggleMicrophone,
                    onInterruptSpeech = rayaViewModel::interruptSpeech,
                    onSettingsClick = {
                        if (uiState.voiceSessionActive) rayaViewModel.endVoiceSession()
                        navController.navigate(RayaRoute.Settings)
                    },
                    onClearConversation = rayaViewModel::clearConversation,
                )
            }
            composable(RayaRoute.Settings) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun OnboardingScreen(
    stored: AppSettings,
    apiKeyStatus: ApiKeyStatus,
    modelListState: ModelListState,
    onRefreshModels: (AppSettings, String) -> Unit,
    onProviderChanged: (LlmProviderPreset) -> Unit,
    onSave: suspend (AppSettings, String) -> Result<Unit>,
    onComplete: suspend () -> Unit,
) {
    val totalSteps = 8
    var step by remember { mutableIntStateOf(0) }
    var draft by remember(stored) { mutableStateOf(stored) }
    var apiKey by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val screenScope = rememberCoroutineScope()
    LaunchedEffect(step, draft.provider) {
        if (step == 4) onRefreshModels(draft, apiKey)
    }
    val titles = listOf(
        R.string.onboarding_welcome_title,
        R.string.onboarding_appearance_title,
        R.string.onboarding_ai_title,
        R.string.onboarding_key_title,
        R.string.onboarding_model_title,
        R.string.onboarding_voice_title,
        R.string.onboarding_personality_title,
        R.string.onboarding_done_title,
    )

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(RayaSpacing.Screen).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_step_format, step + 1, totalSteps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            RayaSurface(modifier = Modifier.fillMaxWidth()) {
                RayaSection(stringResource(titles[step])) {
                    when (step) {
                        0 -> Text(stringResource(R.string.onboarding_welcome_body))
                        1 -> ChoiceRow(
                            options = listOf(AppearanceMode.Raya, AppearanceMode.Dynamic),
                            selected = draft.appearanceMode,
                            label = { if (it == AppearanceMode.Raya) stringResource(R.string.appearance_raya) else stringResource(R.string.appearance_dynamic) },
                            onSelect = { draft = draft.copy(appearanceMode = it) },
                        )
                        2 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChoiceRow(
                            options = LlmProviderPreset.entries.toList(),
                            selected = draft.provider,
                            label = { it.name },
                            onSelect = { draft = draft.copy(provider = it, modelId = it.defaultModel); apiKey = ""; onProviderChanged(it) },
                            )
                            if (draft.provider == LlmProviderPreset.Custom) {
                                ChoiceRow(
                                    options = LlmProtocol.entries.toList(),
                                    selected = draft.customProtocol,
                                    label = { it.name },
                                    onSelect = { draft = draft.copy(customProtocol = it) },
                                )
                                OutlinedTextField(
                                    value = draft.customBaseUrl,
                                    onValueChange = { draft = draft.copy(customBaseUrl = it) },
                                    label = { Text(stringResource(R.string.base_url)) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (draft.customBaseUrl.trim().startsWith("http://")) {
                                    androidx.compose.material3.Switch(
                                        checked = draft.customAllowInsecureHttp,
                                        onCheckedChange = { draft = draft.copy(customAllowInsecureHttp = it) },
                                    )
                                    Text(stringResource(R.string.unsafe_http_warning), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        3 -> OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(stringResource(R.string.onboarding_api_key)) },
                            supportingText = { if (apiKeyStatus == ApiKeyStatus.Configured) Text(stringResource(R.string.api_key_configured)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        4 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val discovered = when (val value = modelListState) {
                                is ModelListState.Loaded -> value.models.chatModels()
                                is ModelListState.Cached -> value.models.chatModels()
                                is ModelListState.Failed -> value.cached.chatModels()
                                ModelListState.Loading -> emptyList()
                            }
                            if (discovered.isNotEmpty()) {
                                ChoiceRow(
                                    options = discovered,
                                    selected = discovered.firstOrNull { it.id == draft.modelId } ?: discovered.first(),
                                    label = { it.displayName },
                                    onSelect = { draft = draft.copy(modelId = it.id, useCustomModel = false) },
                                )
                            }
                            OutlinedTextField(
                                value = if (draft.useCustomModel) draft.customModelId else draft.modelId,
                                onValueChange = { draft = draft.copy(useCustomModel = true, customModelId = it) },
                                label = { Text(stringResource(R.string.model_id)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        5 -> Text(stringResource(R.string.onboarding_system_tts))
                        6 -> Text(stringResource(R.string.onboarding_personality_body))
                        7 -> Text(stringResource(R.string.onboarding_done_body))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (step > 0) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { step-- },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.onboarding_back)) }
                }
                RayaPrimaryButton(
                    text = stringResource(if (step == totalSteps - 1) R.string.onboarding_finish else R.string.onboarding_continue),
                    onClick = {
                        if (step == totalSteps - 1) {
                            screenScope.launch {
                                saving = true
                                saveError = null
                                val result = onSave(draft, apiKey)
                                if (result.isSuccess) onComplete()
                                else saveError = result.exceptionOrNull()?.message ?: "Could not save setup."
                                saving = false
                            }
                        } else step++
                    },
                    enabled = !saving && (step != 3 || draft.provider.requiresApiKey.not() || apiKey.isNotBlank() || apiKeyStatus == ApiKeyStatus.Configured),
                )
            }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}
