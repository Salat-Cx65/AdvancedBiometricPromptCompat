package dev.skomlach.biometric.compat.engine.internal.iris.android

import android.annotation.SuppressLint
import android.hardware.iris.IrisManager
import android.os.Build
import androidx.annotation.RestrictTo
import androidx.core.os.CancellationSignal
import dev.skomlach.biometric.compat.engine.AuthenticationFailureReason
import dev.skomlach.biometric.compat.engine.AuthenticationHelpReason.Companion.getByCode
import dev.skomlach.biometric.compat.engine.BiometricCodes
import dev.skomlach.biometric.compat.engine.BiometricInitListener
import dev.skomlach.biometric.compat.engine.BiometricMethod
import dev.skomlach.biometric.compat.engine.internal.AbstractBiometricModule
import dev.skomlach.biometric.compat.engine.internal.core.Core
import dev.skomlach.biometric.compat.engine.internal.core.interfaces.AuthenticationListener
import dev.skomlach.biometric.compat.engine.internal.core.interfaces.RestartPredicate
import dev.skomlach.biometric.compat.utils.BiometricErrorLockoutPermanentFix
import dev.skomlach.biometric.compat.utils.CodeToString.getErrorCode
import dev.skomlach.biometric.compat.utils.CodeToString.getHelpCode
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.d
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.e
import dev.skomlach.common.misc.ExecutorHelper
import me.weishu.reflection.Reflection

@RestrictTo(RestrictTo.Scope.LIBRARY)
class AndroidIrisUnlockModule @SuppressLint("WrongConstant") constructor(listener: BiometricInitListener?) :
    AbstractBiometricModule(BiometricMethod.IRIS_ANDROIDAPI) {
    private var manager: IrisManager? = null

    init {
        Reflection.unseal(context, listOf("android.hardware.iris"))
        manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                context.getSystemService(IrisManager::class.java)
            } catch (ignore: Throwable) {
                null
            }
        } else {
            try {
                context.getSystemService("iris") as IrisManager
            } catch (ignore: Throwable) {
                null
            }
        }
        listener?.initFinished(biometricMethod, this@AndroidIrisUnlockModule)
    }
    override val isManagerAccessible: Boolean
        get() = manager != null
    override val isHardwarePresent: Boolean
        get() {

                try {
                    return manager?.isHardwareDetected == true
                } catch (e: Throwable) {
                    e(e, name)
                }

            return false
        }

    override fun hasEnrolled(): Boolean {

            try {
                return manager?.isHardwareDetected== true && manager?.hasEnrolledIrises()== true
            } catch (e: Throwable) {
                e(e, name)
            }

        return false
    }

    @Throws(SecurityException::class)
    override fun authenticate(
        cancellationSignal: CancellationSignal?,
        listener: AuthenticationListener?,
        restartPredicate: RestartPredicate?
    ) {
        d("$name.authenticate - $biometricMethod")
        manager?.let{
            try {
                val callback: IrisManager.AuthenticationCallback =
                    AuthCallback(restartPredicate, cancellationSignal, listener)

                // Why getCancellationSignalObject returns an Object is unexplained
                val signalObject =
                    (if (cancellationSignal == null) null else cancellationSignal.cancellationSignalObject as android.os.CancellationSignal?)
                        ?: throw IllegalArgumentException("CancellationSignal cann't be null")

                // Occasionally, an NPE will bubble up out of IrisManager.authenticate
                it.authenticate(
                    null,
                    signalObject,
                    0,
                    callback,
                    ExecutorHelper.INSTANCE.handler
                )
                return
            } catch (e: Throwable) {
                e(e, "$name: authenticate failed unexpectedly")
            }
        }
        listener?.onFailure(AuthenticationFailureReason.UNKNOWN, tag())
        return
    }

    internal inner class AuthCallback(
        private val restartPredicate: RestartPredicate?,
        private val cancellationSignal: CancellationSignal?,
        private val listener: AuthenticationListener?
    ) : IrisManager.AuthenticationCallback() {
        override fun onAuthenticationError(errMsgId: Int, errString: CharSequence?) {
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
                    Core.cancelAuthentication(this@AndroidIrisUnlockModule)
                    return
                }
                BiometricCodes.BIOMETRIC_ERROR_CANCELED ->                     // Don't send a cancelled message.
                    return
            }
            if (restartPredicate?.invoke(failureReason) == true) {
                listener?.onFailure(failureReason, tag())
                authenticate(cancellationSignal, listener, restartPredicate)
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

        override fun onAuthenticationHelp(helpMsgId: Int, helpString: CharSequence?) {
            d(name + ".onAuthenticationHelp: " + getHelpCode(helpMsgId) + "-" + helpString)
            listener?.onHelp(getByCode(helpMsgId), helpString)
        }

        override fun onAuthenticationSucceeded(result: IrisManager.AuthenticationResult?) {
            d("$name.onAuthenticationSucceeded: $result")
            listener?.onSuccess(tag())
        }

        override fun onAuthenticationFailed() {
            d("$name.onAuthenticationFailed: ")
            listener?.onFailure(AuthenticationFailureReason.AUTHENTICATION_FAILED, tag())
        }
    }

}