package com.dustincorder.rai.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophonePermissionPolicyTest {
    @Test
    fun `first denial with rationale remains retryable`() {
        assertEquals(
            MicrophonePermissionAction.RequestPermission,
            microphonePermissionAction(shouldShowRequestPermissionRationale = true, requestWasPreviouslyDenied = true),
        )
    }

    @Test
    fun `permanent denial points to app settings`() {
        assertEquals(
            MicrophonePermissionAction.ShowAppSettings,
            microphonePermissionAction(shouldShowRequestPermissionRationale = false, requestWasPreviouslyDenied = true),
        )
    }

    @Test
    fun `never requested permission can still be requested`() {
        assertEquals(
            MicrophonePermissionAction.RequestPermission,
            microphonePermissionAction(shouldShowRequestPermissionRationale = false, requestWasPreviouslyDenied = false),
        )
    }

    @Test
    fun `permission result without rationale immediately points to settings`() {
        assertEquals(
            MicrophonePermissionAction.ShowAppSettings,
            microphonePermissionResultAction(shouldShowRequestPermissionRationale = false),
        )
    }
}
