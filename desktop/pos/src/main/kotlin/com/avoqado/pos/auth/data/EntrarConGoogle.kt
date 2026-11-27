package com.avoqado.pos.auth.data

import androidx.fragment.app.FragmentActivity
import com.avoqado.pos.auth.data.model.GoogleConfigResponse
import com.avoqado.pos.auth.data.model.PaseDeGoogle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reemplazo de escritorio (Credential Manager no existe en Windows). Provisional: el navegador + 127.0.0.1 con PKCE
 * llega en la Task 10 (hz:6bfcd3a1:D1). Misma forma pública que la de Android.
 */
@Singleton
class EntrarConGoogle @Inject constructor() {
    val abreNavegador: Boolean = true

    suspend fun pedirPase(activity: FragmentActivity, config: GoogleConfigResponse): PaseDeGoogle =
        PaseDeGoogle.Fallo(
            "Entrar con Google no está disponible en este aparato. Entra con tu correo y contraseña.",
            "pendiente Task 10",
        )

    fun cancelar() = Unit
}

internal fun traducirFallo(e: Throwable): PaseDeGoogle =
    PaseDeGoogle.Fallo(
        "Entrar con Google no está disponible en este aparato. Entra con tu correo y contraseña.",
        "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
    )
