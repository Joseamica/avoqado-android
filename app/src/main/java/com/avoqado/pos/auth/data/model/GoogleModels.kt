package com.avoqado.pos.auth.data.model

import kotlinx.serialization.Serializable

/** Respuesta de `GET /mobile/auth/google/config` (hz:6bfcd3a1:D1). */
@Serializable
data class GoogleConfigResponse(
    val success: Boolean = false,
    val androidAudience: String? = null,
    val nonce: String? = null,
    val desktopClientId: String? = null,
    val android: Boolean = false,
    val windows: Boolean = false,
)

// Dos clases de cuerpo: el `Json` compartido manda nulls explícitos (NetworkModule), y el servidor
// no acepta `code` junto con `idToken`.
@Serializable
data class GoogleTokenRequest(val idToken: String, val rememberMe: Boolean = true)

@Serializable
data class GoogleCodeRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    val rememberMe: Boolean = true,
)

/** Lo que devuelve el aparato al pedir el pase de Google. */
sealed interface PaseDeGoogle {
    data class Token(val idToken: String) : PaseDeGoogle
    data class Codigo(val code: String, val codeVerifier: String, val redirectUri: String) : PaseDeGoogle
    data object Cancelado : PaseDeGoogle
    data class Fallo(val texto: String, val causa: String) : PaseDeGoogle
}
