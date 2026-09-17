package com.dustincorder.rai.presentation

enum class MicrophonePermissionAction {
    RequestPermission,
    ShowAppSettings,
}

fun microphonePermissionAction(
    shouldShowRequestPermissionRationale: Boolean,
    requestWasPreviouslyDenied: Boolean,
): MicrophonePermissionAction = if (
    requestWasPreviouslyDenied && !shouldShowRequestPermissionRationale
) {
    MicrophonePermissionAction.ShowAppSettings
} else {
    MicrophonePermissionAction.RequestPermission
}

fun microphonePermissionResultAction(
    shouldShowRequestPermissionRationale: Boolean,
): MicrophonePermissionAction = if (shouldShowRequestPermissionRationale) {
    MicrophonePermissionAction.RequestPermission
} else {
    MicrophonePermissionAction.ShowAppSettings
}
