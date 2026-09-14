package com.dustincorder.rai.presentation

import android.util.Log
import com.dustincorder.rai.BuildConfig
import com.dustincorder.rai.domain.RayaRoutingDiagnostics

class AndroidRayaRoutingDiagnostics : RayaRoutingDiagnostics {
    override fun record(addressed: Boolean, queryBlank: Boolean, localResponse: Boolean) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            Log.d("Raya-Routing", "addressed=$addressed queryBlank=$queryBlank localResponse=$localResponse")
        }
    }
}