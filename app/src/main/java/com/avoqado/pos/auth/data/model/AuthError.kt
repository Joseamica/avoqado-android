package com.avoqado.pos.auth.data.model

sealed class AuthError : Exception() {
    data object InvalidCredentials : AuthError() {
        private fun readResolve(): Any = InvalidCredentials
        override val message: String get() = "Correo electrónico o contraseña incorrectos."
    }

    data object EmailNotVerified : AuthError() {
        private fun readResolve(): Any = EmailNotVerified
        override val message: String get() = "Por favor verifica tu correo electrónico."
    }

    data object AccountLocked : AuthError() {
        private fun readResolve(): Any = AccountLocked
        override val message: String get() = "Cuenta bloqueada temporalmente."
    }

    data object NoVenueAccess : AuthError() {
        private fun readResolve(): Any = NoVenueAccess
        override val message: String get() = "No tienes acceso a ningún establecimiento."
    }

    data object SessionExpired : AuthError() {
        private fun readResolve(): Any = SessionExpired
        override val message: String get() = "Sesión expirada. Inicia sesión de nuevo."
    }

    data class NetworkError(override val message: String = "Error de conexión.") : AuthError()
    data class ServerError(override val message: String = "Error del servidor.") : AuthError()
}
