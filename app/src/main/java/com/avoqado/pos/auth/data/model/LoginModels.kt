package com.avoqado.pos.auth.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val rememberMe: Boolean = true,
)

@Serializable
data class LoginResponse(
    val success: Boolean,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val user: UserData? = null,
    val message: String? = null,
)

@Serializable
data class UserData(
    val id: String,
    val email: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val venues: List<VenueData> = emptyList(),
)

@Serializable
data class VenueData(
    val id: String,
    val name: String,
    val slug: String? = null,
    val logo: String? = null,
    val role: String? = null,
    val timezone: String? = null,
    val permissions: List<String> = emptyList(),
    // Para agrupar venues por organización (picker de traslados CEDIS). Opcional:
    // un server viejo que no lo mande deja null y el picker degrada a "todos".
    val organizationId: String? = null,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
)

sealed class LoginResult {
    data class Success(
        val userId: String,
        val email: String,
        val firstName: String?,
        val lastName: String?,
        val venues: List<VenueData>,
    ) : LoginResult()

    data class Error(val message: String) : LoginResult()
}

// AuthError is defined in AuthError.kt
