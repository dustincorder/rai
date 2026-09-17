package com.dustincorder.rai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
            var permissionAttempts by rememberSaveable { mutableIntStateOf(0) }
            var showMicrophoneSettings by rememberSaveable { mutableStateOf(false) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        hasMicrophonePermission = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                hasMicrophonePermission = granted
                if (granted) rayaViewModel.startVoiceSession()
                else if (permissionAttempts >= 2 && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    showMicrophoneSettings = true
                } else {
                    rayaViewModel.showError(getString(R.string.microphone_permission_denied))
                }
            }
            RayaApp(
                application,
                rayaViewModel,
                settingsViewModel,
                onVoiceChatClick = {
                    if (hasMicrophonePermission) rayaViewModel.startVoiceSession()
                    else {
                        permissionAttempts++
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
            if (showMicrophoneSettings) {
                AlertDialog(
                    onDismissRequest = { showMicrophoneSettings = false },
                    title = { Text(getString(R.string.microphone_settings_title)) },
                    text = { Text(getString(R.string.microphone_settings_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showMicrophoneSettings = false
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            })
                        }) { Text(getString(R.string.open_app_settings)) }
                    },
                    dismissButton = { TextButton(onClick = { showMicrophoneSettings = false }) { Text(getString(R.string.cancel)) } },
                )
            }
        }
    }
}
