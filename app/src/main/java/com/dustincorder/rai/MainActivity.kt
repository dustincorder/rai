package com.dustincorder.rai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dustincorder.rai.presentation.RayaViewModel
import com.dustincorder.rai.presentation.RayaViewModelFactory
import com.dustincorder.rai.presentation.SettingsViewModel
import com.dustincorder.rai.presentation.SettingsViewModelFactory
import com.dustincorder.rai.ui.RayaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val application = application as RayaApplication
            val rayaViewModel: RayaViewModel = viewModel(factory = RayaViewModelFactory(application))
            val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(application))
            var hasMicrophonePermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED,
                )
            }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                hasMicrophonePermission = granted
                if (granted) rayaViewModel.startVoiceSession()
                else rayaViewModel.showError(getString(R.string.microphone_permission_denied))
            }
            RayaApp(
                application,
                rayaViewModel,
                settingsViewModel,
                onVoiceChatClick = {
                    if (hasMicrophonePermission) rayaViewModel.startVoiceSession()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
        }
    }
}
