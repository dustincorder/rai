package com.dustincorder.rai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProtocol
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.domain.ConversationLanguage
import com.dustincorder.rai.presentation.ConnectionStatus
import com.dustincorder.rai.presentation.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val stored by viewModel.settings.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(stored) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    LaunchedEffect(stored) { draft = stored }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
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
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("LLM", style = MaterialTheme.typography.titleMedium)
                    Text("Provider", style = MaterialTheme.typography.labelMedium)
                    ChipGrid(LlmProviderPreset.entries, draft.provider, { it.name }) { provider ->
                        draft = draft.copy(provider = provider, modelId = provider.defaultModel)
                        apiKey = ""
                    }
                    if (draft.provider == LlmProviderPreset.Custom) {
                        Text("Protocol", style = MaterialTheme.typography.labelMedium)
                        ChipGrid(
                            LlmProtocol.entries,
                            draft.customProtocol,
                            { if (it == LlmProtocol.OpenAiCompatible) "OpenAI-compatible" else "Anthropic-compatible" },
                        ) {
                            draft = draft.copy(customProtocol = it)
                        }
                        OutlinedTextField(
                            value = draft.customBaseUrl,
                            onValueChange = { draft = draft.copy(customBaseUrl = it) },
                            label = { Text("Base URL (HTTPS)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = draft.modelId,
                        onValueChange = { draft = draft.copy(modelId = it) },
                        label = { Text("Model ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API key") },
                        placeholder = { Text("Оставьте пустым, чтобы сохранить текущий") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(
                                        if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (showKey) "Скрыть ключ" else "Показать ключ",
                                    )
                                }
                                IconButton(onClick = {
                                    apiKey = ""
                                    viewModel.deleteKey(draft.provider)
                                }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Удалить ключ")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Язык разговора", style = MaterialTheme.typography.titleMedium)
                    LanguageOptions(draft.conversationLanguage) {
                        draft = draft.copy(conversationLanguage = it)
                    }
                }
            }

            val status = connectionStatus
            when (status) {
                is ConnectionStatus.None -> Unit
                is ConnectionStatus.Checking -> Text("Проверка подключения…")
                is ConnectionStatus.Message -> Text(
                    status.message,
                    color = if (status.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { viewModel.save(draft, apiKey) },
                    modifier = Modifier.weight(1f),
                ) { Text("Сохранить") }
                Button(
                    onClick = { viewModel.testConnection(draft, apiKey) },
                    modifier = Modifier.weight(1f),
                ) { Text("Проверить") }
            }
            Spacer(Modifier.height(12.dp))
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

@Composable
private fun LanguageOptions(selected: ConversationLanguage, onSelected: (ConversationLanguage) -> Unit) {
    val options = listOf(
        "Автоматически" to ConversationLanguage.Auto,
        "Язык устройства" to ConversationLanguage.System,
        "Русский" to ConversationLanguage.Explicit("ru-RU"),
        "Українська" to ConversationLanguage.Explicit("uk-UA"),
        "English" to ConversationLanguage.Explicit("en-US"),
    )
    options.forEach { (label, language) ->
        FilterChip(
            selected = selected == language,
            onClick = { onSelected(language) },
            label = { Text(label) },
        )
    }
}
