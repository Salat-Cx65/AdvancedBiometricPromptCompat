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

package dev.skomlach.biometric.compat.engine.internal.face.huawei

import android.content.*
import android.os.*
import androidx.annotation.RestrictTo
import com.huawei.facerecognition.FaceManager
import dev.skomlach.biometric.compat.BiometricCryptoObject
import dev.skomlach.biometric.compat.engine.AuthenticationFailureReason
import dev.skomlach.biometric.compat.engine.AuthenticationHelpReason
import dev.skomlach.biometric.compat.engine.BiometricCodes
import dev.skomlach.biometric.compat.engine.BiometricInitListener
import dev.skomlach.biometric.compat.engine.BiometricMethod
import dev.skomlach.biometric.compat.engine.internal.AbstractBiometricModule
import dev.skomlach.biometric.compat.engine.core.Core
import dev.skomlach.biometric.compat.engine.core.interfaces.AuthenticationListener
import dev.skomlach.biometric.compat.engine.core.interfaces.RestartPredicate
import dev.skomlach.biometric.compat.engine.internal.face.huawei.impl.HuaweiFaceManager
import dev.skomlach.biometric.compat.engine.internal.face.huawei.impl.HuaweiFaceManagerFactory
import dev.skomlach.biometric.compat.utils.BiometricErrorLockoutPermanentFix
import dev.skomlach.biometric.compat.utils.CodeToString.getErrorCode
import dev.skomlach.biometric.compat.utils.CodeToString.getHelpCode
import dev.skomlach.biometric.compat.utils.device.VendorCheck
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.d
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.e
import dev.skomlach.common.misc.ExecutorHelper
import java.lang.reflect.InvocationTargetException

@RestrictTo(RestrictTo.Scope.LIBRARY)
class HuaweiFaceUnlockModule(listener: BiometricInitListener?) :
    AbstractBiometricModule(BiometricMethod.FACE_HUAWEI) {
    //EMUI 10.1.0
    private var huaweiFaceManagerLegacy: HuaweiFaceManager? = null
    private var huawei3DFaceManager: FaceManager? = null

    init {
        ExecutorHelper.INSTANCE.handler.post {
            if(VendorCheck.isHuawei) {
                try {
                    huawei3DFaceManager = faceManager
                    d("$name.huawei3DFaceManager - $huawei3DFaceManager")
                } catch (ignore: Throwable) {
                    huawei3DFaceManager = null
                }
                try {
                    huaweiFaceManagerLegacy = HuaweiFaceManagerFactory.getHuaweiFaceManager(context)
                    d("$name.huaweiFaceManagerLegacy - $huaweiFaceManagerLegacy")
                } catch (ignore: Throwable) {
                    huaweiFaceManagerLegacy = null
                }
            }
            listener?.initFinished(biometricMethod, this@HuaweiFaceUnlockModule)
        }
    }

    private val faceManager: FaceManager?
        get() {
            try {
                val t = Class.forName("com.huawei.facerecognition.FaceManagerFactory")
                val method = t.getDeclaredMethod("getFaceManager", Context::class.java)
                return method.invoke(null, context) as FaceManager
            } catch (var3: ClassNotFoundException) {
                d("$name.Throw exception: ClassNotFoundException")
            } catch (var4: NoSuchMethodException) {
                d("$name.Throw exception: NoSuchMethodException")
            } catch (var5: IllegalAccessException) {
                d("$name.Throw exception: IllegalAccessException")
            } catch (var6: InvocationTargetException) {
                d("$name.Throw exception: InvocationTargetException")
            }
            return null
        }
    override val isManagerAccessible: Boolean
        get() = huaweiFaceManagerLegacy != null || huawei3DFaceManager != null
    override val isHardwarePresent: Boolean
        get() {
            try {
                if (huawei3DFaceManager?.isHardwareDetected == true) return true
            } catch (e: Throwable) {
                e(e, name)
            }
            try {
                if (huaweiFaceManagerLegacy?.isHardwareDetected == true) return true
            } catch (e: Throwable) {
                e(e, name)
            }

            return false
        }

    override fun hasEnrolled(): Boolean {
        try {
            if (huawei3DFaceManager?.isHardwareDetected == true && huawei3DFaceManager?.hasEnrolledTemplates() == true) return true
        } catch (e: Throwable) {
            e(e, name)
        }
        try {
            if (huaweiFaceManagerLegacy?.isHardwareDetected == true && huaweiFaceManagerLegacy?.hasEnrolledTemplates() == true) return true
        } catch (e: Throwable) {
            e(e, name)
        }

        return false
    }

    @Throws(SecurityException::class)
    override fun authenticate(
        cancellationSignal: androidx.core.os.CancellationSignal?,
        listener: AuthenticationListener?,
        restartPredicate: RestartPredicate?,
        biometricCryptoObject: BiometricCryptoObject?
    ) {
        d("$name.authenticate - $biometricMethod")
        if (!isHardwarePresent) {
            listener?.onFailure(AuthenticationFailureReason.NO_HARDWARE, tag())
            return
        }
        if (!hasEnrolled()) {
            listener?.onFailure(AuthenticationFailureReason.NO_BIOMETRICS_REGISTERED, tag())
            return
        }
        // Why getCancellationSignalObject returns an Object is unexplained
        val signalObject =
            if (cancellationSignal == null) null else cancellationSignal.cancellationSignalObject as CancellationSignal?
        try {
            requireNotNull(signalObject) { "CancellationSignal cann't be null" }
        } catch (e: Throwable) {
            e(e)
            listener?.onFailure(AuthenticationFailureReason.UNKNOWN, tag())
            return
        }
        if (huawei3DFaceManager?.isHardwareDetected == true && huawei3DFaceManager?.hasEnrolledTemplates() == true) {
            huawei3DFaceManager?.let {
                try {
                    // Occasionally, an NPE will bubble up out of FingerprintManager.authenticate
                    it.authenticate(
                        null,
                        signalObject,
                        0,
                        AuthCallback3DFace(restartPredicate, cancellationSignal, listener, biometricCryptoObject),
                        ExecutorHelper.INSTANCE.handler
                    )
                    return
                } catch (e: Throwable) {
                    e(e, "$name: authenticate failed unexpectedly")
                }
            }
        }
        if (huaweiFaceManagerLegacy?.isHardwareDetected == true && huaweiFaceManagerLegacy?.hasEnrolledTemplates() == true) {
            huaweiFaceManagerLegacy?.let {
                try {
                    signalObject.setOnCancelListener(CancellationSignal.OnCancelListener {
                        it.cancel(
                            0
                        )
                    })
                    // Occasionally, an NPE will bubble up out of FingerprintManager.authenticate
                    it.authenticate(
                        0,
                        0,
                        AuthCallbackLegacy(restartPredicate, cancellationSignal, listener, biometricCryptoObject)
                    )
                    return
                } catch (e: Throwable) {
                    e(e, "$name: authenticate failed unexpectedly")
                }
            }
        }
        listener?.onFailure(AuthenticationFailureReason.UNKNOWN, tag())
    }

    private inner class AuthCallback3DFace(
        private val restartPredicate: RestartPredicate?,
        private val cancellationSignal: androidx.core.os.CancellationSignal?,
        private val listener: AuthenticationListener?,
        private val biometricCryptoObject: BiometricCryptoObject?
    ) : FaceManager.AuthenticationCallback() {
        override fun onAuthenticationError(errMsgId: Int, errString: CharSequence) {
            d(name + ".onAuthenticationError: " + getErrorCode(errMsgId) + "-" + errString)
            var failureReason = AuthenticationFailureReason.UNKNOWN
            when (errMsgId) {
                BiometricCodes.BIOMETRIC_ERROR_NO_BIOMETRICS -> failureReason =
                    AuthenticationFailureReason.NO_BIOMETRICS_REGISTERED
                BiometricCodes.BIOMETRIC_ERROR_HW_NOT_PRESENT -> failureReason =
                    AuthenticationFailureReason.NO_HARDWARE
                BiometricCodes.BIOMETRIC_ERROR_HW_UNAVAILABLE -> failureReason =
                    AuthenticationFailureReason.HARDWARE_UNAVAILABLE
                BiometricCodes.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> {
                    BiometricErrorLockoutPermanentFix.INSTANCE.setBiometricSensorPermanentlyLocked(
                        biometricMethod.biometricType
                    )
                    failureReason = AuthenticationFailureReason.HARDWARE_UNAVAILABLE
                }
                BiometricCodes.BIOMETRIC_ERROR_UNABLE_TO_PROCESS, BiometricCodes.BIOMETRIC_ERROR_NO_SPACE -> failureReason =
                    AuthenticationFailureReason.SENSOR_FAILED
                BiometricCodes.BIOMETRIC_ERROR_TIMEOUT -> failureReason =
                    AuthenticationFailureReason.TIMEOUT
                BiometricCodes.BIOMETRIC_ERROR_LOCKOUT -> {
                    lockout()
                    failureReason = AuthenticationFailureReason.LOCKED_OUT
                }
                BiometricCodes.BIOMETRIC_ERROR_USER_CANCELED -> {
                    Core.cancelAuthentication(this@HuaweiFaceUnlockModule)
                    return
                }
                BiometricCodes.BIOMETRIC_ERROR_CANCELED ->                     // Don't send a cancelled message.
                    return
            }
            if (restartPredicate?.invoke(failureReason) == true) {
                listener?.onFailure(failureReason, tag())
                authenticate(cancellationSignal, listener, restartPredicate, biometricCryptoObject)
            } else {
                when (failureReason) {
                    AuthenticationFailureReason.SENSOR_FAILED, AuthenticationFailureReason.AUTHENTICATION_FAILED -> {
                        lockout()
                        failureReason = AuthenticationFailureReason.LOCKED_OUT
                    }
                }
                listener?.onFailure(failureReason, tag())
            }
        }

        override fun onAuthenticationHelp(helpMsgId: Int, helpString: CharSequence) {
            d(name + ".onAuthenticationHelp: " + getHelpCode(helpMsgId) + "-" + helpString)
            listener?.onHelp(AuthenticationHelpReason.getByCode(helpMsgId), helpString)
        }

        override fun onAuthenticationSucceeded(result: FaceManager.AuthenticationResult) {
            d("$name.onAuthenticationSucceeded: $result")
            listener?.onSuccess(tag(), biometricCryptoObject)
        }

        override fun onAuthenticationFailed() {
            d("$name.onAuthenticationFailed: ")
            listener?.onFailure(AuthenticationFailureReason.AUTHENTICATION_FAILED, tag())
        }
    }

    private inner class AuthCallbackLegacy(
        private val restartPredicate: RestartPredicate?,
        private val cancellationSignal: androidx.core.os.CancellationSignal?,
        private val listener: AuthenticationListener?,
        private val biometricCryptoObject: BiometricCryptoObject?
    ) : HuaweiFaceManager.AuthenticatorCallback() {
        override fun onAuthenticationError(errMsgId: Int) {
            d(name + ".onAuthenticationError: " + getErrorCode(errMsgId))
            var failureReason = AuthenticationFailureReason.UNKNOWN
            when (errMsgId) {
                BiometricCodes.BIOMETRIC_ERROR_NO_BIOMETRICS -> failureReason =
                    AuthenticationFailureReason.NO_BIOMETRICS_REGISTERED
                BiometricCodes.BIOMETRIC_ERROR_HW_NOT_PRESENT -> failureReason =
                    AuthenticationFailureReason.NO_HARDWARE
                BiometricCodes.BIOMETRIC_ERROR_HW_UNAVAILABLE -> failureReason =
                    AuthenticationFailureReason.HARDWARE_UNAVAILABLE
                BiometricCodes.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> {
                    BiometricErrorLockoutPermanentFix.INSTANCE.setBiometricSensorPermanentlyLocked(
                        biometricMethod.biometricType
                    )
                    failureReason = AuthenticationFailureReason.HARDWARE_UNAVAILABLE
                }
                BiometricCodes.BIOMETRIC_ERROR_UNABLE_TO_PROCESS, BiometricCodes.BIOMETRIC_ERROR_NO_SPACE -> failureReason =
                    AuthenticationFailureReason.SENSOR_FAILED
                BiometricCodes.BIOMETRIC_ERROR_TIMEOUT -> failureReason =
                    AuthenticationFailureReason.TIMEOUT
                BiometricCodes.BIOMETRIC_ERROR_LOCKOUT -> {
                    lockout()
                    failureReason = AuthenticationFailureReason.LOCKED_OUT
                }
                BiometricCodes.BIOMETRIC_ERROR_USER_CANCELED -> {
                    Core.cancelAuthentication(this@HuaweiFaceUnlockModule)
                    return
                }
                BiometricCodes.BIOMETRIC_ERROR_CANCELED ->                     // Don't send a cancelled message.
                    return
            }
            if (restartPredicate?.invoke(failureReason) == true) {
                listener?.onFailure(failureReason, tag())
                huaweiFaceManagerLegacy?.cancel(0)
                ExecutorHelper.INSTANCE.handler.postDelayed({
                    authenticate(cancellationSignal, listener, restartPredicate, biometricCryptoObject)
                }, 250)
            } else {
                when (failureReason) {
                    AuthenticationFailureReason.SENSOR_FAILED, AuthenticationFailureReason.AUTHENTICATION_FAILED -> {
                        lockout()
                        failureReason = AuthenticationFailureReason.LOCKED_OUT
                    }
                }
                listener?.onFailure(failureReason, tag())
            }
        }

        override fun onAuthenticationStatus(helpMsgId: Int) {
            d(name + ".onAuthenticationHelp: " + getHelpCode(helpMsgId))
            listener?.onHelp(AuthenticationHelpReason.getByCode(helpMsgId), null)
        }

        override fun onAuthenticationSucceeded() {
            d("$name.onAuthenticationSucceeded: ")
            listener?.onSuccess(tag(), biometricCryptoObject)
        }

        override fun onAuthenticationFailed() {
            d("$name.onAuthenticationFailed: ")
            onAuthenticationError(BiometricCodes.BIOMETRIC_ERROR_UNABLE_TO_PROCESS)
        }
    }
}