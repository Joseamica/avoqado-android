package com.avoqado.pos.auth.data.model

import kotlinx.serialization.Serializable

/**
 * Respuesta de `GET /mobile/auth/google/config` (hz:6bfcd3a1:D1). Es de las dos plataformas: ANDROID usa `android` +
 * `webClientId` + `redirectUri`; WINDOWS, `windows` + `desktopClientId`. Lo apagado llega en `false`/`null`.
 */
@Serializable
data class GoogleConfigResponse(
    val success: Boolean = false,
    val android: Boolean = false,
    val windows: Boolean = false,
    val webClientId: String? = null,
    val redirectUri: String? = null,
    val desktopClientId: String? = null,
)

// Dos clases de cuerpo: el `Json` compartido manda nulls explícitos (NetworkModule), y el servidor es estricto: la
// variante de ANDROID no acepta otras llaves ni nulls.
@Serializable
data class GoogleStateRequest(val state: String, val codeVerifier: String, val rememberMe: Boolean = true)

@Serializable
data class GoogleCodeRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    val rememberMe: Boolean = true,
)

/** Lo que devuelve el aparato al pedir el pase de Google. */
sealed interface PaseDeGoogle {
    /** ANDROID: el SERVIDOR ya tiene el `code` guardado con este `state`; se canjea con el verificador. */
    data class Vuelta(val state: String, val codeVerifier: String) : PaseDeGoogle
    data class Codigo(val code: String, val codeVerifier: String, val redirectUri: String) : PaseDeGoogle
    data object Cancelado : PaseDeGoogle
    data class Fallo(val texto: String, val causa: String) : PaseDeGoogle
}
