package com.avoqado.pos.auth.data

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsService
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.avoqado.pos.auth.data.model.GoogleConfigResponse
import com.avoqado.pos.auth.data.model.PaseDeGoogle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton

internal const val TEXTO_APARATO = "Entrar con Google no está disponible en este aparato. Entra con tu correo y contraseña."
internal const val TEXTO_SERVIDOR_SIN_GOOGLE = "Entrar con Google no está disponible por ahora. Entra con tu correo y contraseña."
internal const val TEXTO_SIN_CHROME =
    "Entrar con Google necesita Chrome actualizado en este aparato. Entra con tu correo y contraseña."
internal const val TEXTO_REINTENTA = "No pudimos confirmar tu cuenta de Google. Vuelve a intentarlo."
internal const val TEXTO_CAJA_BLOQUEADA =
    "Entrar con Google no está disponible con la caja bloqueada. Entra con tu correo y contraseña."

private const val CHROME = "com.android.chrome"
/** El esquema de regreso: el SERVIDOR redirige a `avoqado-pos://google?state=…` y la Auth Tab lo intercepta (sin intent-filter). */
private const val ESQUEMA = "avoqado-pos"
/** La llave del lanzador: la misma en cada `MainActivity`, para que AndroidX entregue un resultado pendiente tras recrearla. */
private const val LLAVE = "avoqado.entrar-con-google"

/**
 * Entrar con Google en una pestaña de incógnito de Chrome (Auth Tab efímera), sin agregar la cuenta a la tableta
 * (hz:6bfcd3a1:D1). Google vuelve al SERVIDOR, que guarda el `code` bajo el `state`; el POS manda `state` + verificador
 * PKCE y el SERVIDOR canjea. Nunca cae a una pestaña normal ni a otro navegador sin incógnito.
 * WINDOWS tiene su reemplazo en `desktop/pos` con la misma forma pública.
 */
@Singleton
class EntrarConGoogle @Inject constructor() {
    val abreNavegador: Boolean = false

    // Todo en el hilo principal: `registrar` y el callback los llama AndroidX ahí, y `pedirPase` corre en viewModelScope.
    private var lanzador: ActivityResultLauncher<Intent>? = null
    /** El intento en curso (uno a la vez). Vive aquí y no en la Activity: sobrevive a que Android recree la pantalla. */
    private var enCurso: Intento? = null

    private class Intento(val state: String, val verificador: String) {
        val pase = CompletableDeferred<PaseDeGoogle>()
    }

    /**
     * Lo llama `MainActivity.onCreate` (antes de STARTED) en cada creación (Codex r4 #3). Con la MISMA llave, si Android
     * recreó la pantalla con la pestaña abierta, AndroidX entrega aquí el resultado pendiente y el intento en curso lo
     * recibe. Si el proceso murió, no hay intento en memoria y el resultado se descarta.
     */
    fun registrar(activity: ComponentActivity) {
        val propio = activity.activityResultRegistry.register(LLAVE, activity, AuthTabIntent.AuthenticateUserResultContract()) {
            val intento = enCurso ?: return@register
            intento.pase.complete(paseDeLaAuthTab(it.resultCode, it.resultUri, intento.state, intento.verificador))
        }
        lanzador = propio
        // El lanzador retiene a ESTA pantalla: al destruirse se suelta (si sigue siendo el suyo). El intento en curso no se
        // toca: la pantalla nueva registra con la misma llave y recibe el resultado pendiente.
        activity.lifecycle.addObserver(LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_DESTROY && lanzador === propio) lanzador = null
        })
    }

    suspend fun pedirPase(activity: FragmentActivity, config: GoogleConfigResponse): PaseDeGoogle {
        val clientId = config.webClientId
        val vuelta = config.redirectUri
        if (!config.android || clientId.isNullOrBlank() || vuelta.isNullOrBlank()) {
            return PaseDeGoogle.Fallo(TEXTO_SERVIDOR_SIN_GOOGLE, "config sin android/webClientId/redirectUri")
        }
        // Nunca `stopLockTask()`: con el bloqueo de administrador la pestaña no se puede abrir; el fijado normal sí se intenta.
        val bloqueo = (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState
        if (cajaBloqueada(bloqueo)) return PaseDeGoogle.Fallo(TEXTO_CAJA_BLOQUEADA, "lockTaskModeState=$bloqueo")
        val paquete = elegirNavegador(
            navegadores(activity),
            soportaAuthTab = { CustomTabsClient.isAuthTabSupported(activity, it) },
            soportaEfimera = { CustomTabsClient.isEphemeralBrowsingSupported(activity, it) },
        ) ?: return PaseDeGoogle.Fallo(TEXTO_SIN_CHROME, "ningún navegador con Auth Tab e incógnito")
        val lanzador = lanzador ?: return PaseDeGoogle.Fallo(TEXTO_APARATO, "sin lanzador registrado")

        val intento = Intento(nuevoState(), nuevoVerificador())
        enCurso?.pase?.complete(PaseDeGoogle.Cancelado)   // uno a la vez: el anterior no se queda esperando
        enCurso = intento
        return try {
            val pestana = AuthTabIntent.Builder().setEphemeralBrowsingEnabled(true).build()
            pestana.intent.setPackage(paquete)
            val url = urlDeGoogle(clientId, vuelta, intento.state, retoPkce(intento.verificador))
            pestana.launch(lanzador, Uri.parse(url), ESQUEMA)
            intento.pase.await()
        } catch (e: ActivityNotFoundException) {
            PaseDeGoogle.Fallo(TEXTO_SIN_CHROME, "ActivityNotFoundException: ${e.message.orEmpty()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PaseDeGoogle.Fallo(TEXTO_APARATO, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        } finally {
            if (enCurso === intento) enCurso = null
        }
    }

    /** La pestaña tapa la pantalla: aquí no hay «Cancelar»; cerrarla llega como [PaseDeGoogle.Cancelado]. */
    fun cancelar() = Unit
}

/** El navegador por defecto (si da pestañas) y todos los que dicen darlas (`<queries>` del manifiesto). */
@Suppress("DEPRECATION") // queryIntentServices(Intent, Int): la de ResolveInfoFlags no existe antes de la API 33
private fun navegadores(contexto: Context): List<String> =
    listOfNotNull(CustomTabsClient.getPackageName(contexto, null)) +
        contexto.packageManager.queryIntentServices(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION), 0)
            .mapNotNull { it.serviceInfo?.packageName }

/** Chrome primero si da Auth Tab e incógnito; si no, el primer candidato que dé las dos. Ninguno ⇒ null (Codex r4 #2). */
internal fun elegirNavegador(
    candidatos: List<String>,
    soportaAuthTab: (String) -> Boolean,
    soportaEfimera: (String) -> Boolean,
): String? = (listOf(CHROME) + candidatos).distinct().firstOrNull { soportaAuthTab(it) && soportaEfimera(it) }

/** Sólo el bloqueo de administrador (LOCKED) frena; el fijado normal del POS (PINNED) se intenta (Codex r4 #4). */
internal fun cajaBloqueada(estadoDeBloqueo: Int): Boolean = estadoDeBloqueo == ActivityManager.LOCK_TASK_MODE_LOCKED

/** El resultado de la Auth Tab: cerrarla es [PaseDeGoogle.Cancelado]; OK lee la vuelta; lo demás es un fallo del aparato. */
internal fun paseDeLaAuthTab(resultado: Int, uri: Uri?, stateEsperado: String, verificador: String): PaseDeGoogle =
    when (resultado) {
        AuthTabIntent.RESULT_OK -> leerVueltaDeAuthTab(uri, stateEsperado, verificador)
        AuthTabIntent.RESULT_CANCELED -> PaseDeGoogle.Cancelado
        else -> PaseDeGoogle.Fallo(TEXTO_APARATO, "Auth Tab: resultado $resultado")
    }

/**
 * `avoqado-pos://google?state=…[&error=…]`. Sólo cuenta con NUESTRO `state` (uno ajeno o ausente es [TEXTO_REINTENTA]).
 * `access_denied` es que la persona dijo que no ([PaseDeGoogle.Cancelado]); cualquier otro `error` es reintentar.
 */
internal fun leerVueltaDeAuthTab(uri: Uri?, stateEsperado: String, verificador: String): PaseDeGoogle {
    // Una uri opaca (`avoqado-pos:x`) truena en getQueryParameter, y esto corre en el callback del registro.
    if (uri == null || !uri.isHierarchical || uri.getQueryParameter("state") != stateEsperado) {
        return PaseDeGoogle.Fallo(TEXTO_REINTENTA, "vuelta opaca, con state ajeno o sin state")
    }
    return when (val error = uri.getQueryParameter("error")) {
        null -> PaseDeGoogle.Vuelta(stateEsperado, verificador)
        "access_denied" -> PaseDeGoogle.Cancelado
        else -> PaseDeGoogle.Fallo(TEXTO_REINTENTA, "vuelta con error=$error")
    }
}
