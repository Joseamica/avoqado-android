package com.avoqado.pos.auth.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.auth.data.BiometricAuthManager
import com.avoqado.pos.auth.data.model.LoginResult
import com.avoqado.pos.core.data.local.SecureStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.avoqado.pos.core.data.network.ServerErrorText

enum class SignInStep { EMAIL, PASSWORD }

data class SignInUiState(
    val step: SignInStep = SignInStep.EMAIL,
    val email: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val canUseBiometric: Boolean = false,
    val biometricEmail: String? = null,
) {
    val isEmailValid: Boolean
        // Real format check — "a@" used to advance to the password step and
        // only fail as a server 401 after the user also typed a password.
        get() = android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val biometricAuthManager: BiometricAuthManager,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val _uiState = MutableStateFlow(estadoInicial())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    /** Lo que ve quien abre la app: paso del correo, sin contraseña ni ruedita, y la huella si hay con qué. */
    private fun estadoInicial(): SignInUiState {
        val hasBiometric = secureStorage.isBiometricLoginEnabled &&
            secureStorage.biometricRefreshToken != null
        return SignInUiState(
            canUseBiometric = hasBiometric,
            biometricEmail = if (hasBiometric) secureStorage.biometricUserEmail else null,
        )
    }

    /**
     * 🔴 Tras entrar, la pantalla vuelve a su estado inicial. Este ViewModel NO muere al entrar: `LandingScreen` no
     * vive en un NavHost, así que `hiltViewModel()` lo cuelga de la Activity (en Windows, de la raíz de la ventana)
     * y al cerrar sesión se pinta la MISMA instancia. Antes se quedaba con `isLoading = true` y la contraseña
     * anterior: el botón salía con la ruedita, deshabilitado, y sólo cerrar la app lo destrababa (D3, 2.25.8, 20-oct).
     */
    private fun alEntrar(onSuccess: () -> Unit) {
        onSuccess()
        _uiState.value = estadoInicial()
    }

    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, errorMessage = null) }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, errorMessage = null) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun goToPassword() {
        _uiState.update { it.copy(step = SignInStep.PASSWORD, errorMessage = null) }
    }

    fun goBackToEmail() {
        _uiState.update {
            it.copy(step = SignInStep.EMAIL, password = "", errorMessage = null, isPasswordVisible = false)
        }
    }

    fun loginWithPassword(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (!state.isEmailValid || state.password.length < 4) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            when (val result = authRepository.loginWithEmail(state.email, state.password)) {
                is LoginResult.Success -> {
                    Log.d("🔐", "✅ Login success, navigating...")
                    alEntrar(onSuccess)
                }
                is LoginResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = ServerErrorText.humanize(result.message)) }
                }
            }
        }
    }

    fun loginWithBiometric(activity: FragmentActivity, onSuccess: () -> Unit) {
        val refreshToken = secureStorage.biometricRefreshToken ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                val didAuthenticate = biometricAuthManager.authenticate(activity)
                if (!didAuthenticate) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = biometricAuthManager.error.value,
                        )
                    }
                    return@launch
                }

                authRepository.refreshTokensForBiometric(refreshToken)

                val biometricVenues = secureStorage.biometricVenuesList
                val restoredVenue = biometricVenues.firstOrNull { it.id == secureStorage.biometricVenueId }
                    ?: biometricVenues.firstOrNull()

                if (!secureStorage.restoreBiometricSession(restoredVenue)) {
                    throw IllegalStateException("Missing biometric session data")
                }

                Log.d("🔐", "✅ Biometric login success")
                alEntrar(onSuccess)
            } catch (e: Exception) {
                Log.e("🔐", "❌ Biometric login failed: ${e.message}")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Sesión expirada. Inicia sesión con tu contraseña.",
                        canUseBiometric = false,
                    )
                }
                // Clear expired biometric credentials
                secureStorage.clearBiometricCredentials()
            }
        }
    }
}
