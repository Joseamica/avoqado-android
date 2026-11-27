package com.avoqado.pos.auth.data

import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.fragment.app.FragmentActivity
import com.avoqado.pos.auth.data.model.GoogleConfigResponse
import com.avoqado.pos.auth.data.model.PaseDeGoogle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import javax.inject.Inject
import javax.inject.Singleton

internal const val TEXTO_APARATO = "Entrar con Google no está disponible en este aparato. Entra con tu correo y contraseña."
internal const val TEXTO_SERVIDOR_SIN_GOOGLE = "Entrar con Google no está disponible por ahora. Entra con tu correo y contraseña."

/**
 * Pide a Google el pase para entrar al POS con Credential Manager (hz:6bfcd3a1:D1).
 * WINDOWS tiene su reemplazo en `desktop/pos` con la misma forma pública (navegador + 127.0.0.1 con PKCE).
 */
@Singleton
class EntrarConGoogle @Inject constructor() {
    val abreNavegador: Boolean = false

    suspend fun pedirPase(activity: FragmentActivity, config: GoogleConfigResponse): PaseDeGoogle {
        val audiencia = config.androidAudience
        val nonce = config.nonce
        if (!config.android || audiencia.isNullOrBlank() || nonce.isNullOrBlank()) {
            return PaseDeGoogle.Fallo(TEXTO_SERVIDOR_SIN_GOOGLE, "config sin android/androidAudience/nonce")
        }
        return try {
            val opcion = GetSignInWithGoogleOption.Builder(audiencia).setNonce(nonce).build()
            val respuesta = CredentialManager.create(activity)
                .getCredential(activity, GetCredentialRequest(listOf(opcion)))
            val credencial = respuesta.credential
            if (credencial is CustomCredential &&
                credencial.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                PaseDeGoogle.Token(GoogleIdTokenCredential.createFrom(credencial.data).idToken)
            } else {
                PaseDeGoogle.Fallo(TEXTO_APARATO, "credencial inesperada: ${credencial.javaClass.simpleName}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            traducirFallo(e)
        }
    }

    fun cancelar() = Unit
}

internal fun traducirFallo(e: Throwable): PaseDeGoogle =
    if (e is GetCredentialCancellationException) {
        PaseDeGoogle.Cancelado
    } else {
        PaseDeGoogle.Fallo(TEXTO_APARATO, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
    }
