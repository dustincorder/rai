package com.dustincorder.rai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dustincorder.rai.presentation.RayaViewModel
import com.dustincorder.rai.presentation.RayaViewModelFactory
import com.dustincorder.rai.presentation.SettingsViewModel
import com.dustincorder.rai.presentation.SettingsViewModelFactory
import com.dustincorder.rai.ui.RayaScreen
import com.dustincorder.rai.ui.SettingsScreen
import com.dustincorder.rai.ui.theme.RayaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val application = application as RayaApplication
            val rayaViewModel: RayaViewModel = viewModel(factory = RayaViewModelFactory(application))
            val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(application))
            val uiState by rayaViewModel.uiState.collectAsStateWithLifecycle()
            var showSettings by rememberSaveable { mutableStateOf(false) }
            var hasMicrophonePermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED,
                )
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                hasMicrophonePermission = granted
                if (granted) rayaViewModel.startVoiceFlow()
                else rayaViewModel.showError("Разрешение на микрофон не предоставлено.")
            }

            BackHandler(enabled = showSettings) { showSettings = false }
            RayaTheme {
                if (showSettings) {
                    SettingsScreen(settingsViewModel, onBack = { showSettings = false })
                } else {
                    RayaScreen(
                        state = uiState,
                        onTalkClick = {
                            if (uiState.isBusy) rayaViewModel.cancelListening()
                            else if (hasMicrophonePermission) rayaViewModel.startVoiceFlow()
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onSettingsClick = { showSettings = true },
                    )
                }
            }
        }
    }
}
