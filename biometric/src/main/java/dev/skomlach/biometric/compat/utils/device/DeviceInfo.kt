package dev.skomlach.biometric.compat.utils.device

data class DeviceInfo(
    val existsInDatabase: Boolean = false,
    val hasIris: Boolean = false,
    val hasFace: Boolean = false,
    val hasFingerprint: Boolean = false,
    val hasUnderDisplayFingerprint: Boolean = false
)