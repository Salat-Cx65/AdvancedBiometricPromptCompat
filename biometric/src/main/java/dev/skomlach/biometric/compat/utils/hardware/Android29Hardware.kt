/*
 *  Copyright (c) 2021 Sergey Komlach aka Salat-Cx65; Original project: https://github.com/Salat-Cx65/AdvancedBiometricPromptCompat
 *  All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package dev.skomlach.biometric.compat.utils.hardware

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import androidx.annotation.RestrictTo
import androidx.biometric.BiometricManager
import dev.skomlach.biometric.compat.BiometricAuthRequest
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.e
import dev.skomlach.common.contextprovider.AndroidContext.appContext
import dev.skomlach.common.misc.Utils.isAtLeastR

@TargetApi(Build.VERSION_CODES.Q)
@RestrictTo(RestrictTo.Scope.LIBRARY)
class Android29Hardware(authRequest: BiometricAuthRequest) : Android28Hardware(authRequest) {
    @SuppressLint("WrongConstant")
    private fun canAuthenticate(): Int {
        var code = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        try {
            val biometricManager = appContext.getSystemService(
                android.hardware.biometrics.BiometricManager::class.java
            )
            if (biometricManager != null) {
                code = if (isAtLeastR) {
                    biometricManager.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
                } else {
                    biometricManager.canAuthenticate()
                }
            }
        } catch (e: Throwable) {
            e(e)
        }
        e("Android29Hardware.canAuthenticate - $code")
        return code
    }

    override val isAnyHardwareAvailable: Boolean
        get() {
            val canAuthenticate = canAuthenticate()
            return if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                true
            } else {
                canAuthenticate != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE && canAuthenticate != BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
            }
        }
    override val isAnyBiometricEnrolled: Boolean
        get() {
            val canAuthenticate = canAuthenticate()
            return if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                true
            } else {
                canAuthenticate != BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED && canAuthenticate != BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
            }
        }
}