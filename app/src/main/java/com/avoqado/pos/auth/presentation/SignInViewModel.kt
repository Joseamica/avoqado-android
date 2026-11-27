package com.avoqado.pos.auth.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.auth.data.BiometricAuthManager
import com.avoqado.pos.auth.data.ConfigDeGoogle
import com.avoqado.pos.auth.data.EntrarConGoogle
import com.avoqado.pos.auth.data.model.PaseDeGoogle
import com.avoqado.pos.core.util.ConnectivityMonitor
import kotlinx.coroutines.Job
import com.avoqado.pos.auth.data.model.LoginResult
import com.avoqado.pos.core.data.local.SecureStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import com.avoqado.pos.core.data.network.ServerErrorText

enum class SignInStep { EMAIL, PASSWORD }

data class SignInUiState(
    val step: SignInStep = SignInStep.EMAIL,
    val email: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    /** WINDOWS: el navegador está abierto y se espera a Google (hz:6bfcd3a1:D1). */
    val esperandoNavegador: Boolean = false,
    /** El pase ya va al SERVIDOR: cortarlo aquí no deshace la sesión que él emita; ni «Cancelar» ni Atrás lo cortan. */
    val enviandoGoogle: Boolean = false,
    val errorMessage: String? = null,
    val canUseBiometric: Boolean = false,
    val biometricEmail: String? = null,
    /** «¿Olvidaste tu contraseña?»: el pedido va en camino. */
    val pidiendoEnlace: Boolean = false,
    /** El aviso tras pedir el enlace (mismo texto que iOS); null = cerrado. */
    val avisoContrasena: String? = null,
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
    private val entrarConGoogle: EntrarConGoogle,
    private val connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

    /** El intento de Google vivo (si hay): «Cancelar» y Atrás lo cortan. */
    private var intentoDeGoogle: Job? = null


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
        // El «Siguiente» del teclado no mira el botón: con Google en curso no se cambia de pantalla (Codex r2 #5).
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(step = SignInStep.PASSWORD, errorMessage = null) }
    }

    fun goBackToEmail() {
        _uiState.update {
            it.copy(step = SignInStep.EMAIL, password = "", errorMessage = null, isPasswordVisible = false)
        }
    }

    /**
     * «¿Olvidaste tu contraseña?» (1-nov-2026: en ANDROID no hacía nada). Como iOS: pide el enlace al correo ya escrito
     * y avisa con el mismo texto, exista o no la cuenta. Sin red no promete un correo: lo dice.
     */
    fun pedirEnlaceDeContrasena() {
        val state = _uiState.value
        if (state.pidiendoEnlace || state.isLoading || !state.isEmailValid) return
        val correo = state.email.trim()
        viewModelScope.launch {
            _uiState.update { it.copy(pidiendoEnlace = true, errorMessage = null) }
            val salio = authRepository.pedirRestablecerContrasena(correo)
            _uiState.update {
                it.copy(
                    pidiendoEnlace = false,
                    avisoContrasena = if (salio) {
                        "Si existe una cuenta con $correo, recibirás un enlace para restablecer tu contraseña."
                    } else {
                        null
                    },
                    errorMessage = if (salio) null else SIN_RED_PARA_EL_ENLACE,
                )
            }
        }
    }

    fun cerrarAvisoContrasena() {
        _uiState.update { it.copy(avisoContrasena = null) }
    }

    fun loginWithPassword(onSuccess: () -> Unit) {
        val state = _uiState.value
        // El «Done» del teclado no mira si ya hay un login en camino: dos seguidos con distinto correo cruzaban sesiones (D4).
        if (state.isLoading || !state.isEmailValid || state.password.length < 4) return

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
        // Un segundo toque mientras el primero espera la red mandaría la misma llave dos veces (D4, Codex código r4 #1).
        if (_uiState.value.isLoading) return
        val refreshToken = secureStorage.biometricRefreshToken ?: return
        // D4: si mientras se pone la huella y viaja la renovación entra o sale alguien, esta entrada no se instala.
        val sello = secureStorage.generacion

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

                val biometricVenues = secureStorage.biometricVenuesList
                val restoredVenue = biometricVenues.firstOrNull { it.id == secureStorage.biometricVenueId }
                    ?: biometricVenues.firstOrNull()

                if (!authRepository.entrarConHuella(refreshToken, restoredVenue, sello)) {
                    // No es que la huella haya vencido: entró otra persona. Sus credenciales se quedan.
                    _uiState.update { it.copy(isLoading = false, errorMessage = AuthRepository.OTRA_PERSONA_ENTRO) }
                    return@launch
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
                // Clear expired biometric credentials (sólo si siguen siendo las que mandó ESTE intento)
                secureStorage.clearBiometricCredentialsSiSigue(refreshToken)
            }
        }
    }

    /**
     * «Continuar con Google» (hz:6bfcd3a1:D1). Comparte `isLoading` con contraseña y huella: un solo intento vivo.
     * El sello se toma AL TOCAR (D4): si mientras Google espera entra o sale alguien, esta entrada no se instala.
     */
    fun loginWithGoogle(activity: FragmentActivity, onSuccess: () -> Unit) {
        if (_uiState.value.isLoading) return
        val sello = secureStorage.generacion
        _uiState.update {
            it.copy(
                isLoading = true,
                esperandoNavegador = entrarConGoogle.abreNavegador,
                enviandoGoogle = false,
                errorMessage = null,
            )
        }

        intentoDeGoogle = viewModelScope.launch {
            val config = when (val c = authRepository.configDeGoogle()) {
                is ConfigDeGoogle.Lista -> c.config
                ConfigDeGoogle.SinRed -> return@launch falloDeGoogle(AuthRepository.SIN_CONEXION_PARA_GOOGLE)
                is ConfigDeGoogle.NoDisponible -> return@launch falloDeGoogle(c.texto ?: TEXTO_SERVIDOR_SIN_GOOGLE)
            }
            when (val pase = entrarConGoogle.pedirPase(activity, config)) {
                PaseDeGoogle.Cancelado -> falloDeGoogle(null)
                is PaseDeGoogle.Fallo -> {
                    Log.w("🔐", "Google: ${pase.causa}")
                    falloDeGoogle(if (sinInternet()) AuthRepository.SIN_CONEXION_PARA_GOOGLE else pase.texto)
                }
                else -> {
                    // Desde aquí el intento ya no se cancela y «Cancelar» desaparece (Codex r2 #4).
                    _uiState.update { it.copy(esperandoNavegador = false, enviandoGoogle = true) }
                    when (val r = authRepository.loginWithGoogle(pase, sello)) {
                        is LoginResult.Success -> alEntrar(onSuccess)
                        is LoginResult.Error -> falloDeGoogle(ServerErrorText.humanize(r.message))
                    }
                }
            }
        }
    }

    /**
     * ¿El fallo de Google fue por falta de internet? Con Wi‑Fi pero sin internet el monitor todavía dice «en línea»
     * (Codex r2 #7): ANDROID le pregunta al SERVIDOR. WINDOWS no: su texto de los 3 min ya dice la verdad.
     */
    private suspend fun sinInternet(): Boolean =
        !connectivityMonitor.isOnline() ||
            (!entrarConGoogle.abreNavegador && sondaAlServidor() == ConfigDeGoogle.SinRed)

    /** A lo más [SONDA_MS]: un SERVIDOR que acepta y no contesta no detiene la salida a contraseña (Codex r3 #3). */
    private suspend fun sondaAlServidor(): ConfigDeGoogle =
        withTimeoutOrNull(SONDA_MS) { authRepository.configDeGoogle() } ?: ConfigDeGoogle.SinRed

    private fun falloDeGoogle(texto: String?) {
        _uiState.update { it.copy(isLoading = false, esperandoNavegador = false, enviandoGoogle = false, errorMessage = texto) }
    }

    /**
     * El botón Atrás de la entrada. Con el pase ya enviado no hace nada: salir de la pantalla dejaría que el login, al
     * terminar, metiera a la persona desde la portada (Codex r3 #1). Esperando el navegador, cancela como siempre.
     */
    fun atras(onBack: () -> Unit) {
        if (_uiState.value.enviandoGoogle) return
        cancelarGoogle()
        if (_uiState.value.step == SignInStep.PASSWORD) goBackToEmail() else onBack()
    }

    /** «Cancelar» y Atrás: el intento de Google se descarta y no se instala nada. */
    fun cancelarGoogle() {
        // Atrás se pulsa también con contraseña o huella en camino: sólo se corta un intento de Google vivo.
        val job = intentoDeGoogle?.takeIf { it.isActive && !_uiState.value.enviandoGoogle } ?: return
        entrarConGoogle.cancelar()
        job.cancel()
        intentoDeGoogle = null
        falloDeGoogle(null)
    }

    companion object {
        private const val SONDA_MS = 5_000L

        private const val TEXTO_SERVIDOR_SIN_GOOGLE =
            "Entrar con Google no está disponible por ahora. Entra con tu correo y contraseña."

        const val SIN_RED_PARA_EL_ENLACE = "Sin conexión: conéctate a internet para recibir el enlace."
    }
}
