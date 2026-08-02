package dev.zipshare.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.zipshare.log.AppLog

object Biometrics {

    const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    fun available(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    AppLog.logAuth("unlock success")
                    onSuccess()
                }

                /** A rejected fingerprint/face; the prompt stays up, so no callback to the UI. */
                override fun onAuthenticationFailed() {
                    AppLog.logAuth("unlock attempt rejected")
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    AppLog.logAuth("unlock error: $message")
                    onError(message.toString())
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock ZipShare")
                .setSubtitle("Your server tokens are protected")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }
}
