package com.avoqado.pos.auth.data

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

enum class BiometricType(val displayName: String) {
    NONE("No disponible"),
    FINGERPRINT("Huella digital"),
    FACE("Reconocimiento facial"),
}

@Singleton
class BiometricAuthManager @Inject constructor() {

    private val _isAuthenticating = MutableStateFlow(false)
    val isAuthenticating: StateFlow<Boolean> = _isAuthenticating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun isBiometricAvailable(activity: FragmentActivity): Boolean {
        val biometricManager = BiometricManager.from(activity)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun getBiometricType(activity: FragmentActivity): BiometricType {
        val biometricManager = BiometricManager.from(activity)
        val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            // Android doesn't easily distinguish fingerprint vs face; default to fingerprint
            if (activity.packageManager.hasSystemFeature("android.hardware.fingerprint")) {
                BiometricType.FINGERPRINT
            } else {
                BiometricType.FACE
            }
        } else {
            BiometricType.NONE
        }
    }

    suspend fun authenticate(activity: FragmentActivity): Boolean {
        _isAuthenticating.value = true
        _error.value = null

        return try {
            val result = suspendCancellableCoroutine { continuation ->
                val executor = ContextCompat.getMainExecutor(activity)
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        Log.d("🔐", "✅ Biometric authentication succeeded")
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        Log.e("🔐", "❌ Biometric error: $errorCode - $errString")
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED,
                            -> {
                                // User cancelled — no error message
                            }
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                            -> {
                                _error.value = "Biometría bloqueada. Usa tu código del dispositivo."
                            }
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.ERROR_HW_UNAVAILABLE,
                            -> {
                                _error.value = "Biometría no disponible en este dispositivo."
                            }
                            BiometricPrompt.ERROR_NO_BIOMETRICS -> {
                                _error.value = "No hay datos biométricos registrados."
                            }
                            else -> {
                                _error.value = "Autenticación fallida."
                            }
                        }
                        if (continuation.isActive) continuation.resume(false)
                    }

                    override fun onAuthenticationFailed() {
                        Log.e("🔐", "❌ Biometric authentication failed")
                        // Don't resume here — user can retry
                    }
                }

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Desbloquear Avoqado")
                    .setSubtitle("Usa tu biometría para iniciar sesión")
                    .setNegativeButtonText("Cancelar")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build()

                val biometricPrompt = BiometricPrompt(activity, executor, callback)
                biometricPrompt.authenticate(promptInfo)

                continuation.invokeOnCancellation {
                    biometricPrompt.cancelAuthentication()
                }
            }
            result
        } finally {
            _isAuthenticating.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }
}
