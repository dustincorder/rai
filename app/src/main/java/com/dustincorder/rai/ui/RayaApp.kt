package com.dustincorder.rai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dustincorder.rai.R
import com.dustincorder.rai.RayaApplication
import com.dustincorder.rai.data.llm.ModelListState
import com.dustincorder.rai.data.llm.chatModels
import com.dustincorder.rai.data.settings.AppearanceMode
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.OnboardingPolicy
import com.dustincorder.rai.data.settings.InitialDestination
import com.dustincorder.rai.data.settings.effectiveAppearance
import com.dustincorder.rai.data.settings.previousOnboardingStep
import com.dustincorder.rai.data.settings.resolveInitialDestination
import com.dustincorder.rai.data.settings.SttEngine
import com.dustincorder.rai.domain.TtsEngine
import com.dustincorder.rai.presentation.ApiKeyStatus
import com.dustincorder.rai.presentation.RayaViewModel
import com.dustincorder.rai.presentation.SettingsViewModel
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
    application: RayaApplication,
    rayaViewModel: RayaViewModel,
    settingsViewModel: SettingsViewModel,
    onVoiceChatClick: () -> Unit,
) {
    val navController = rememberNavController()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val modelListState by settingsViewModel.modelListState.collectAsStateWithLifecycle()
    val apiKeyStatus by settingsViewModel.apiKeyStatus.collectAsStateWithLifecycle()
    val apiKeyStatusProvider by settingsViewModel.apiKeyStatusProvider.collectAsStateWithLifecycle()
    val uiState by rayaViewModel.uiState.collectAsStateWithLifecycle()
    var onboardingAppearance by remember { mutableStateOf<AppearanceMode?>(null) }

    LaunchedEffect(Unit) {
        val destination = when (resolveInitialDestination(application.onboardingStore.completed) {
            runCatching { application.settingsRepository.hasPersistedSettings() }.getOrDefault(false) ||
                LlmProviderPreset.entries.any { runCatching { application.apiKeyStore.isConfigured(it) }.getOrDefault(false) }
        }) {
            InitialDestination.Onboarding -> RayaRoute.Onboarding
            InitialDestination.Main -> RayaRoute.Main
        }
        navController.navigate(destination) {
            popUpTo(RayaRoute.Bootstrap) { inclusive = true }
            launchSingleTop = true
        }
    }

    RayaTheme(appearanceMode = effectiveAppearance(settings.appearanceMode, onboardingAppearance)) {
        NavHost(navController, startDestination = RayaRoute.Bootstrap) {
            composable(RayaRoute.Bootstrap) { BootstrapScreen() }
            composable(RayaRoute.Onboarding) {
                OnboardingScreen(
                    stored = settings,
                    apiKeyStatus = apiKeyStatus,
                    apiKeyStatusProvider = apiKeyStatusProvider,
                    modelListState = modelListState,
                    onProviderChanged = settingsViewModel::refreshApiKeyStatus,
                    onRefreshModels = { draft, key -> settingsViewModel.refreshModelList(draft, key) },
                    onAppearanceChanged = { onboardingAppearance = it },
                    onSave = settingsViewModel::saveForOnboarding,
                    onComplete = {
                        application.onboardingStore.markCompleted()
                        onboardingAppearance = null
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
                SettingsScreen(settingsViewModel) { navController.popBackStack() }
            }
        }
    }
}

@Composable
private fun BootstrapScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScreen(
    stored: AppSettings,
    apiKeyStatus: ApiKeyStatus,
    apiKeyStatusProvider: LlmProviderPreset?,
    modelListState: ModelListState,
    onProviderChanged: (LlmProviderPreset) -> Unit,
    onRefreshModels: (AppSettings, String) -> Unit,
    onAppearanceChanged: (AppearanceMode) -> Unit,
    onSave: suspend (AppSettings, String) -> Result<Unit>,
    onComplete: suspend () -> Unit,
) {
    val totalSteps = ONBOARDING_STEP_COUNT
    var step by remember { mutableIntStateOf(0) }
    var draft by remember(stored) { mutableStateOf(stored) }
    var apiKey by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val saveFailure = stringResource(R.string.onboarding_save_failed)
    val titles = listOf(
        R.string.onboarding_welcome_title,
        R.string.onboarding_appearance_title,
        R.string.onboarding_ai_title,
        R.string.onboarding_voice_title,
        R.string.onboarding_personality_title,
        R.string.onboarding_done_title,
    )
    BackHandler(enabled = step > 0 && !saving) { previousOnboardingStep(step)?.let { step = it } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titles[step])) },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(RayaSpacing.Compact),
                    horizontalArrangement = Arrangement.spacedBy(RayaSpacing.Compact),
                ) {
                    if (step > 0) {
                        TextButton(onClick = { previousOnboardingStep(step)?.let { step = it } }, enabled = !saving) {
                            Text(stringResource(R.string.onboarding_back))
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Button(
                        onClick = {
                            if (step == totalSteps - 1) {
                                scope.launch {
                                    saving = true
                                    saveError = false
                                    if (onSave(draft, apiKey).isSuccess) onComplete() else saveError = true
                                    saving = false
                                }
                            } else {
                                step++
                            }
                        },
                        enabled = !saving && (step != 2 || canContinueAiSetup(draft, apiKey, apiKeyStatusProvider, apiKeyStatus)),
                        modifier = Modifier.weight(1f),
                        shape = com.dustincorder.rai.ui.designsystem.RayaShapes.Control,
                    ) {
                        Text(stringResource(if (step == totalSteps - 1) R.string.onboarding_finish else R.string.onboarding_continue))
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.onboarding_step_format, step + 1, totalSteps),
                modifier = Modifier.padding(horizontal = RayaSpacing.Screen, vertical = RayaSpacing.Compact),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(RayaSpacing.Screen)) {
                RayaSurface(modifier = Modifier.fillMaxWidth()) {
                    RayaSection(stringResource(titles[step])) {
                        when (step) {
                            0 -> Text(stringResource(R.string.onboarding_welcome_body))
                            1 -> ChoiceRow(
                                listOf(AppearanceMode.Raya, AppearanceMode.Dynamic),
                                draft.appearanceMode,
                                { if (it == AppearanceMode.Raya) stringResource(R.string.appearance_raya) else stringResource(R.string.appearance_dynamic) },
                            ) { draft = draft.copy(appearanceMode = it); onAppearanceChanged(it) }
                            2 -> AiSetup(
                                draft, apiKey, apiKeyStatus, apiKeyStatusProvider,
                                modelListState = modelListState,
                                onRefreshModels = { onRefreshModels(draft, apiKey) },
                                onProviderChanged = {
                                    val next = draft.copy(provider = it, modelId = it.defaultModel, useCustomModel = false, customModelId = "")
                                    draft = next
                                    apiKey = ""
                                    onProviderChanged(it)
                                    onRefreshModels(next, "")
                                },
                                onDraft = { draft = it },
                                onKey = { apiKey = it },
                            )
                            3 -> Text(stringResource(R.string.onboarding_system_tts))
                            4 -> Text(stringResource(R.string.onboarding_personality_body))
                            5 -> Text(stringResource(R.string.onboarding_done_body))
                        }
                    }
                }
                if (saveError) Text(saveFailure, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSetup(
    draft: AppSettings,
    apiKey: String,
    apiKeyStatus: ApiKeyStatus,
    apiKeyStatusProvider: LlmProviderPreset?,
    modelListState: ModelListState,
    onRefreshModels: () -> Unit,
    onProviderChanged: (LlmProviderPreset) -> Unit,
    onDraft: (AppSettings) -> Unit,
    onKey: (String) -> Unit,
) {
    var showDraftKey by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RayaExposedSelector(
            label = stringResource(R.string.provider),
            value = draft.provider.name,
            options = LlmProviderPreset.entries.toList(),
            optionLabel = { it.name },
            onSelect = onProviderChanged,
        )
        if (draft.provider.requiresApiKey) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onKey,
                label = { Text(stringResource(R.string.onboarding_api_key)) },
                visualTransformation = if (showDraftKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { showDraftKey = !showDraftKey }) {
                        Icon(
                            if (showDraftKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(if (showDraftKey) R.string.hide_key else R.string.show_key),
                        )
                    }
                },
                supportingText = { if (apiKeyStatusProvider == draft.provider && apiKeyStatus == ApiKeyStatus.Configured) Text(stringResource(R.string.api_key_configured)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (draft.provider == LlmProviderPreset.Custom) {
            RayaExposedSelector(
                label = stringResource(R.string.protocol),
                value = draft.customProtocol.name,
                options = LlmProtocol.entries.toList(),
                optionLabel = { it.name },
                onSelect = { onDraft(draft.copy(customProtocol = it)) },
            )
            OutlinedTextField(
                value = draft.customBaseUrl,
                onValueChange = { onDraft(draft.copy(customBaseUrl = it)) },
                label = { Text(stringResource(R.string.base_url)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.customBaseUrl.trim().startsWith("http://")) {
                androidx.compose.material3.Switch(
                    checked = draft.customAllowInsecureHttp,
                    onCheckedChange = { onDraft(draft.copy(customAllowInsecureHttp = it)) },
                )
                Text(stringResource(R.string.unsafe_http_warning), style = MaterialTheme.typography.bodySmall)
            }
        }
        ModelStep(draft, modelListState, onDraft, onRefreshModels)
    }
}

@Composable
private fun ModelStep(draft: AppSettings, state: ModelListState, onDraft: (AppSettings) -> Unit, onOpen: () -> Unit) {
    val models = when (state) {
        is ModelListState.Loaded -> state.models.chatModels()
        is ModelListState.Cached -> state.models.chatModels()
        is ModelListState.Failed -> state.cached.chatModels()
        ModelListState.Loading -> emptyList()
    }
    RayaExposedSelector(
        label = stringResource(R.string.model_id),
        value = models.firstOrNull { it.id == draft.modelId }?.displayName ?: draft.modelId,
        options = models.map { it.id } + "__custom__",
        optionLabel = { if (it == "__custom__") stringResource(R.string.custom_model) else it },
        onSelect = { if (it == "__custom__") onDraft(draft.copy(useCustomModel = true)) else onDraft(draft.copy(modelId = it, useCustomModel = false)) },
        onOpen = onOpen,
    )
    when (state) {
        ModelListState.Loading -> Text(stringResource(R.string.model_discovery_loading))
        is ModelListState.Failed -> Text(stringResource(R.string.model_discovery_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Unit
    }
    if (draft.useCustomModel) {
        OutlinedTextField(
            value = draft.customModelId,
            onValueChange = { onDraft(draft.copy(customModelId = it)) },
            label = { Text(stringResource(R.string.custom_model_id)) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> RayaExposedSelector(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onOpen: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = {
        expanded = !expanded
        if (expanded) onOpen()
    }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
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
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}
