package com.dustincorder.rai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dustincorder.rai.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dustincorder.rai.BuildConfig
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SttEngine
import com.dustincorder.rai.data.llm.DiscoveredModel
import com.dustincorder.rai.data.llm.ModelListState
import com.dustincorder.rai.data.llm.chatModels
import com.dustincorder.rai.domain.TtsEngine
import com.dustincorder.rai.speech.TtsModelCatalog
import com.dustincorder.rai.presentation.ApiKeyStatus
import com.dustincorder.rai.presentation.ConnectionStatus
import com.dustincorder.rai.presentation.SettingsViewModel
import com.dustincorder.rai.ui.designsystem.RayaShapes
import com.dustincorder.rai.ui.designsystem.RayaSpacing

private const val CUSTOM_MODEL_OPTION = "__custom_model__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onAppearancePreview: (com.dustincorder.rai.data.settings.AppearanceMode?) -> Unit = {},
    onBack: () -> Unit,
) {
    val stored by viewModel.settings.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val apiKeyStatus by viewModel.apiKeyStatus.collectAsStateWithLifecycle()
    val modelListState by viewModel.modelListState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(stored) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    BackHandler {
        onAppearancePreview(null)
        onBack()
    }
    val providerLabels = mapOf(
        LlmProviderPreset.OpenAI to stringResource(R.string.provider_openai),
        LlmProviderPreset.Groq to stringResource(R.string.provider_groq),
        LlmProviderPreset.Anthropic to stringResource(R.string.provider_anthropic),
        LlmProviderPreset.Gemini to stringResource(R.string.provider_gemini),
        LlmProviderPreset.Custom to stringResource(R.string.provider_custom),
    )
    val protocolLabels = mapOf(
        LlmProtocol.OpenAiCompatible to stringResource(R.string.protocol_openai_compatible),
        LlmProtocol.AnthropicCompatible to stringResource(R.string.protocol_anthropic_compatible),
        LlmProtocol.Gemini to stringResource(R.string.protocol_gemini),
    )

    LaunchedEffect(stored) { draft = stored }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onAppearancePreview(null); onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.settings_subtitle), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsPanel {
                Text(stringResource(R.string.settings_raya), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_personality_default), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_raya_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.settings_ai), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.provider), style = MaterialTheme.typography.labelMedium)
                    RayaExposedSelector(
                        label = stringResource(R.string.provider),
                        value = providerLabels[draft.provider].orEmpty(),
                        options = LlmProviderPreset.entries,
                        optionLabel = { providerLabels[it].orEmpty() },
                    ) { provider ->
                        draft = draft.copy(provider = provider, modelId = provider.defaultModel, useCustomModel = false, customModelId = "")
                        apiKey = ""
                        viewModel.refreshApiKeyStatus(provider)
                        viewModel.refreshModelList(draft, apiKey)
                    }
                    if (draft.provider == LlmProviderPreset.Custom) {
                        Text(stringResource(R.string.protocol), style = MaterialTheme.typography.labelMedium)
                        RayaExposedSelector(
                            label = stringResource(R.string.protocol),
                            value = protocolLabels[draft.customProtocol].orEmpty(),
                            options = LlmProtocol.entries.filter { it != LlmProtocol.Gemini },
                            optionLabel = { protocolLabels[it].orEmpty() },
                        ) {
                            draft = draft.copy(customProtocol = it)
                        }
                        OutlinedTextField(
                            value = draft.customBaseUrl,
                            onValueChange = { draft = draft.copy(customBaseUrl = it) },
                            label = { Text(stringResource(R.string.base_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Switch(
                                checked = draft.customAllowInsecureHttp,
                                onCheckedChange = { draft = draft.copy(customAllowInsecureHttp = it) },
                            )
                            Text(
                                stringResource(R.string.unsafe_http),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.unsafe_http_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val discovered = when (val value = modelListState) {
                        is ModelListState.Loaded -> value.models.chatModels()
                        is ModelListState.Cached -> value.models.chatModels()
                        is ModelListState.Failed -> value.cached.chatModels()
                        ModelListState.Loading -> emptyList()
                    }
                    RayaExposedSelector(
                        label = stringResource(R.string.model_id),
                        value = if (draft.useCustomModel) stringResource(R.string.custom_model) else discovered.firstOrNull { it.id == draft.modelId }?.displayName ?: draft.modelId,
                        options = discovered.map { it.id } + CUSTOM_MODEL_OPTION,
                        optionLabel = { id -> if (id == CUSTOM_MODEL_OPTION) stringResource(R.string.custom_model) else discovered.firstOrNull { it.id == id }?.displayName ?: id },
                    ) { id -> draft = if (id == CUSTOM_MODEL_OPTION) draft.copy(useCustomModel = true) else draft.copy(modelId = id, useCustomModel = false) }
                    if (draft.useCustomModel) {
                        OutlinedTextField(
                            value = draft.customModelId,
                            onValueChange = { draft = draft.copy(customModelId = it) },
                            label = { Text(stringResource(R.string.custom_model_id)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(onClick = { viewModel.refreshModelList(draft, apiKey) }) {
                        Text(stringResource(R.string.refresh_models))
                    }
                    val keyPlaceholder = when (apiKeyStatus) {
                        ApiKeyStatus.Configured -> stringResource(R.string.api_key_replace)
                        else -> stringResource(R.string.api_key_enter)
                    }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.api_key)) },
                        placeholder = { Text(keyPlaceholder) },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(
                                        if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = stringResource(if (showKey) R.string.hide_key else R.string.show_key),
                                    )
                                }
                                IconButton(onClick = {
                                    apiKey = ""
                                    viewModel.deleteKey(draft.provider)
                                }) {
                                 Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_key))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when (apiKeyStatus) {
                        ApiKeyStatus.Configured -> Text(
                            stringResource(R.string.api_key_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ApiKeyStatus.Missing -> Text(
                            stringResource(R.string.api_key_not_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ApiKeyStatus.Unreadable -> Text(
                            stringResource(R.string.api_key_unreadable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        ApiKeyStatus.Unknown -> Unit
                    }
                }
            }

            SettingsPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.voice_section), style = MaterialTheme.typography.titleMedium)
                     val systemEngineLabel = stringResource(R.string.system_engine)
                     val whisperEngineLabel = stringResource(R.string.whisper_engine)
                    Text(stringResource(R.string.stt_engine), style = MaterialTheme.typography.labelMedium)
                    ChipGrid(
                        SttEngine.entries,
                        draft.sttEngine,
                         { if (it == SttEngine.GroqWhisper) whisperEngineLabel else systemEngineLabel },
                    ) { draft = draft.copy(sttEngine = it) }
                    Text(stringResource(R.string.stt_model), style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = draft.sttModelId,
                        onValueChange = { draft = draft.copy(sttModelId = it) },
                        label = { Text(stringResource(R.string.stt_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.stt_model_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.tts_engine), style = MaterialTheme.typography.labelMedium)
                    val localNeuralLabel = stringResource(R.string.local_neural_engine)
                    ChipGrid(
                        TtsEngine.entries.filter { it != TtsEngine.LocalNeural || TtsModelCatalog.localNeuralAvailable },
                        draft.ttsEngine,
                        { if (it == TtsEngine.LocalNeural) localNeuralLabel else systemEngineLabel },
                    ) { draft = draft.copy(ttsEngine = it) }
                    if (!TtsModelCatalog.localNeuralAvailable) {
                        Text(
                            stringResource(R.string.local_neural_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SettingsPanel {
                Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_appearance_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val rayaAppearance = stringResource(R.string.appearance_raya)
                val dynamicAppearance = stringResource(R.string.appearance_dynamic)
                ChipGrid(
                    listOf(com.dustincorder.rai.data.settings.AppearanceMode.Raya, com.dustincorder.rai.data.settings.AppearanceMode.Dynamic),
                    draft.appearanceMode,
                    { if (it == com.dustincorder.rai.data.settings.AppearanceMode.Raya) rayaAppearance else dynamicAppearance },
                ) { draft = draft.copy(appearanceMode = it); onAppearancePreview(it) }
            }

            SettingsPanel {
                Text(stringResource(R.string.settings_integrations), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_integrations_empty), style = MaterialTheme.typography.bodyMedium)
            }

            SettingsPanel {
                Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SettingsPanel {
                Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.assistant_name), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_build, BuildConfig.GIT_SHA), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val status = connectionStatus
            when (status) {
                is ConnectionStatus.None -> Unit
                is ConnectionStatus.Checking -> Text(stringResource(R.string.checking_connection))
                is ConnectionStatus.Message -> Text(
                    status.message,
                    color = if (status.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { viewModel.save(draft, apiKey) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.save)) }
                Button(
                    onClick = { viewModel.testConnection(draft, apiKey) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.test_connection)) }
            }
            if (BuildConfig.DEBUG) {
                Text(
                    stringResource(R.string.settings_build_debug, BuildConfig.GIT_SHA),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RayaShapes.Surface,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(RayaSpacing.Section), verticalArrangement = Arrangement.spacedBy(RayaSpacing.Compact), content = content)
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
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> ChipGrid(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    values.chunked(2).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    label = { Text(label(value)) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
