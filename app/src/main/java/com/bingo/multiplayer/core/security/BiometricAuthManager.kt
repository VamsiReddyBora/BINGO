package com.bingo.multiplayer.core.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.hardware.fingerprint.FingerprintManagerCompat

object BiometricAuthManager {
    private const val TAG = "BiometricAuthManager"

    fun isBiometricAvailable(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val fingerprintManager = FingerprintManagerCompat.from(context)
                fingerprintManager.isHardwareDetected && fingerprintManager.hasEnrolledFingerprints()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Biometric check failed: ${e.message}")
            false
        }
    }

    fun authenticate(
        activity: Activity,
        title: String = "Admin Authentication",
        subtitle: String = "Fingerprint verification required",
        description: String = "Confirm your identity to access Admin Controls",
        requireBiometric: Boolean = false,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isBiometricAvailable(activity)) {
            if (requireBiometric) {
                onError("Enrol a fingerprint to change automatic cleanup")
                return
            }
            Log.i(TAG, "Biometrics not enrolled or supported on device; allowing access.")
            onSuccess()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cancellationSignal = CancellationSignal()
            val executor = ContextCompat.getMainExecutor(activity)

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString?.toString() ?: "Authentication cancelled")
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Fingerprint not recognized; system dialog stays open for retry
                }
            }

            try {
                val prompt = BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setDescription(description)
                    .setNegativeButton("Cancel", executor) { _, _ ->
                        onError("Authentication cancelled")
                    }
                    .build()

                prompt.authenticate(cancellationSignal, executor, callback)
            } catch (e: Exception) {
                Log.e(TAG, "BiometricPrompt failed: ${e.message}", e)
                onError(e.message ?: "Authentication error")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cancellationSignal = androidx.core.os.CancellationSignal()
            val fingerprintManager = FingerprintManagerCompat.from(activity)

            fingerprintManager.authenticate(
                null,
                0,
                cancellationSignal,
                object : FingerprintManagerCompat.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: FingerprintManagerCompat.AuthenticationResult?) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errMsgId: Int, errString: CharSequence?) {
                        onError(errString?.toString() ?: "Authentication error")
                    }

                    override fun onAuthenticationFailed() {
                        // Retry
                    }
                },
                null
            )
        } else {
            if (requireBiometric) onError("Biometric verification unavailable") else onSuccess()
        }
    }
}

fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
