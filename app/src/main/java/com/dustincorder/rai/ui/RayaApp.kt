package com.dustincorder.rai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
    const val Bootstrap = "bootstrap"
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
        val persisted = runCatching { application.settingsRepository.hasPersistedSettings() }.getOrDefault(false)
        val keyConfigured = LlmProviderPreset.entries.any {
            runCatching { application.apiKeyStore.isConfigured(it) }.getOrDefault(false)
        }
        legacyInstallation = persisted || keyConfigured
    }

    LaunchedEffect(completed, legacyInstallation) {
        if (legacyInstallation != null) {
            val destination = if (OnboardingPolicy.shouldShow(completed, legacyInstallation == true)) {
                RayaRoute.Onboarding
            } else {
                RayaRoute.Main
            }
            navController.navigate(destination) {
                popUpTo(RayaRoute.Bootstrap) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    RayaTheme(appearanceMode = settings.appearanceMode) {
        NavHost(
            navController = navController,
            startDestination = RayaRoute.Bootstrap,
        ) {
            composable(RayaRoute.Bootstrap) { BootstrapScreen() }
            composable(RayaRoute.Onboarding) {
                OnboardingScreen(
                    stored = settings,
                    apiKeyStatus = settingsViewModel.apiKeyStatus.collectAsStateWithLifecycle().value,
                    apiKeyStatusProvider = settingsViewModel.apiKeyStatusProvider.collectAsStateWithLifecycle().value,
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
    apiKeyStatusProvider: LlmProviderPreset?,
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
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    val screenScope = rememberCoroutineScope()
    val genericSaveError = stringResource(R.string.onboarding_save_failed)
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
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { providerMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(draft.provider.name) }
                                DropdownMenu(
                                    expanded = providerMenuExpanded,
                                    onDismissRequest = { providerMenuExpanded = false },
                                ) {
                                    LlmProviderPreset.entries.forEach { provider ->
                                        DropdownMenuItem(
                                            text = { Text(provider.name) },
                                            onClick = {
                                                draft = draft.copy(provider = provider, modelId = provider.defaultModel)
                                                apiKey = ""
                                                providerMenuExpanded = false
                                                onProviderChanged(provider)
                                            },
                                        )
                                    }
                                }
                            }
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
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { modelMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        discovered.firstOrNull { it.id == draft.modelId }?.displayName
                                            ?: draft.modelId,
                                    )
                                }
                                DropdownMenu(
                                    expanded = modelMenuExpanded,
                                    onDismissRequest = { modelMenuExpanded = false },
                                ) {
                                    discovered.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model.displayName) },
                                            onClick = {
                                                draft = draft.copy(modelId = model.id, useCustomModel = false)
                                                modelMenuExpanded = false
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.custom_model)) },
                                        onClick = {
                                            draft = draft.copy(useCustomModel = true)
                                            modelMenuExpanded = false
                                        },
                                    )
                                }
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
                                else saveError = genericSaveError
                                saving = false
                            }
                        } else step++
                    },
                    enabled = !saving && (step != 3 || draft.provider.requiresApiKey.not() || apiKey.isNotBlank() ||
                        (apiKeyStatusProvider == draft.provider && apiKeyStatus == ApiKeyStatus.Configured)),
                )
            }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun BootstrapScreen() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
    )
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
