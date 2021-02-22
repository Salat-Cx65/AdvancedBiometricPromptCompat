package dev.skomlach.biometric.compat.engine.internal.fingerprint

import android.os.IBinder
import androidx.annotation.RestrictTo
import androidx.core.os.CancellationSignal
import com.fingerprints.service.FingerprintManager
import com.fingerprints.service.FingerprintManager.IdentifyCallback
import dev.skomlach.biometric.compat.engine.AuthenticationFailureReason
import dev.skomlach.biometric.compat.engine.BiometricInitListener
import dev.skomlach.biometric.compat.engine.BiometricMethod
import dev.skomlach.biometric.compat.engine.internal.AbstractBiometricModule
import dev.skomlach.biometric.compat.engine.internal.core.interfaces.AuthenticationListener
import dev.skomlach.biometric.compat.engine.internal.core.interfaces.RestartPredicate
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.d
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.e

@RestrictTo(RestrictTo.Scope.LIBRARY)
class FlymeFingerprintModule(listener: BiometricInitListener?) :
    AbstractBiometricModule(BiometricMethod.FINGERPRINT_FLYME) {
    private var mFingerprintServiceFingerprintManager: FingerprintManager? = null

    init {
        try {
            val servicemanager = Class.forName("android.os.ServiceManager")
            val getService = servicemanager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "fingerprints_service") as IBinder?
            binder?.let{
                mFingerprintServiceFingerprintManager = FingerprintManager.open()
                isManagerAccessible =
                    mFingerprintServiceFingerprintManager != null && mFingerprintServiceFingerprintManager?.isSurpport == true
            }
        } catch (ignore: Throwable) {
        } finally {
            cancelFingerprintServiceFingerprintRequest()
        }
        listener?.initFinished(biometricMethod, this@FlymeFingerprintModule)
    }
    override var isManagerAccessible = false
    override val isHardwarePresent: Boolean
        get() {
            if (isManagerAccessible) {
                try {
                    mFingerprintServiceFingerprintManager = FingerprintManager.open()
                    return mFingerprintServiceFingerprintManager
                        ?.isFingerEnable == true
                } catch (e: Throwable) {
                    e(e, name)
                } finally {
                    cancelFingerprintServiceFingerprintRequest()
                }
            }
            return false
        }

    override fun hasEnrolled(): Boolean {
        if (isManagerAccessible) {
            try {
                mFingerprintServiceFingerprintManager = FingerprintManager.open()
                val fingerprintIds = mFingerprintServiceFingerprintManager?.ids
                return  fingerprintIds?.isNotEmpty() == true
            } catch (e: Throwable) {
                e(e, name)
            } finally {
                cancelFingerprintServiceFingerprintRequest()
            }
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
        if (isManagerAccessible) {
            try {
                cancelFingerprintServiceFingerprintRequest()
                mFingerprintServiceFingerprintManager = FingerprintManager.open()
                mFingerprintServiceFingerprintManager
                    ?.startIdentify(object : IdentifyCallback {
                        override fun onIdentified(i: Int, b: Boolean) {
                            listener?.onSuccess(tag())
                            cancelFingerprintServiceFingerprintRequest()
                        }

                        override fun onNoMatch() {
                            fail(AuthenticationFailureReason.AUTHENTICATION_FAILED)
                        }

                        private fun fail(reason: AuthenticationFailureReason) {
                            var failureReason: AuthenticationFailureReason? = reason
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
                                cancelFingerprintServiceFingerprintRequest()
                            }
                        }
                    }, mFingerprintServiceFingerprintManager?.ids)
                cancellationSignal?.setOnCancelListener { cancelFingerprintServiceFingerprintRequest() }
            } catch (e: Throwable) {
                e(e, "$name: authenticate failed unexpectedly")
            }
        }
        listener?.onFailure(AuthenticationFailureReason.UNKNOWN, tag())
        return
    }

    private fun cancelFingerprintServiceFingerprintRequest() {
        try {

                mFingerprintServiceFingerprintManager?.abort()
                mFingerprintServiceFingerprintManager?.release()
                mFingerprintServiceFingerprintManager = null

        } catch (e: Throwable) {
            e(e, name)
            // There's no way to query if there's an active identify request,
            // so just try to cancel and ignore any exceptions.
        }
    }

}