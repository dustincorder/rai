package com.dustincorder.rai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dustincorder.rai.presentation.RayaViewModel
import com.dustincorder.rai.ui.RayaScreen
import com.dustincorder.rai.ui.theme.RayaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: RayaViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            RayaTheme {
                RayaScreen(
                    state = uiState,
                )
            }
        }
    }
}
