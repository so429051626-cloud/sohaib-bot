package com.madix.beam.security

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the interface must be hidden behind the lock screen. The app locks on a cold
 * start and after it has spent more than [TIMEOUT_MILLIS] in the background.
 */
class AppLock {
    private val state = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = state.asStateFlow()

    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) state.value = false
        }

    private var backgroundSince = 0L

    fun lockIfEnabled() {
        if (enabled) state.value = true
    }

    fun onBackground() {
        backgroundSince = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        val since = backgroundSince
        backgroundSince = 0L
        if (enabled && since != 0L && SystemClock.elapsedRealtime() - since > TIMEOUT_MILLIS) {
            state.value = true
        }
    }

    fun unlock() {
        state.value = false
    }

    companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}

/** Biometric or device-credential confirmation through the system prompt. */
object BiometricGate {
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** True when the device has a screen lock or enrolled biometrics that can protect the app. */
    fun isAvailable(context: Context): Boolean {
        val status = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)
        if (status == BiometricManager.BIOMETRIC_SUCCESS) return true
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceSecure == true
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
