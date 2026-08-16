package com.avoqado.pos.core.data.network

import android.util.Log
import com.avoqado.pos.core.domain.PermissionLabels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquesta el PIN de autorización de gerente.
 *
 * 🔴 `awaitToken` BLOQUEA el hilo que lo llama — que es el hilo de red del
 * interceptor, nunca el de UI. Es a propósito: si el token llegara "por fuera",
 * el ViewModel que hizo la llamada ya habría pintado un error y el éxito del
 * reintento sería invisible. Mismo patrón que `TokenRefreshAuthenticator` con
 * el 401.
 *
 * El `Mutex` garantiza UN teclado a la vez: dos acciones bloqueadas al mismo
 * tiempo hacen fila en vez de apilar dos diálogos.
 */
@Singleton
open class ManagerOverrideCoordinator @Inject constructor(
    private val repository: PermissionOverrideRepository,
) {
    data class Prompt(val permission: String, val actionLabel: String)

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    companion object {
        /**
         * Tope de la espera del teclado. Generoso a propósito —el encargado
         * puede venir del otro lado del local— pero finito: el hilo de red que
         * espera no puede quedarse tomado para siempre.
         */
        const val PROMPT_TIMEOUT_MS = 120_000L
    }

    private val queue = Mutex()

    @Volatile
    private var pending: CompletableDeferred<String?>? = null

    /**
     * Devuelve el token, o null si el usuario canceló / caducó / no se pudo.
     *
     * 🔴 La espera ESTÁ ACOTADA. Sin tope, un teclado que nadie contesta —la
     * tablet quedó en el mostrador, o la sesión expiró y el host que lo pinta se
     * desmontó sin cancelar— dejaba el hilo de red bloqueado para siempre CON el
     * Mutex tomado: a partir de ahí todo 403 overridable de la app se encolaba
     * detrás de un candado que ya no abría, y el PIN dejaba de funcionar hasta
     * matar la app. Al vencer se devuelve null, que el interceptor ya trata como
     * "canceló": la acción falla con su mensaje de siempre.
     */
    open fun awaitToken(permission: String): String? = runBlocking {
        queue.withLock {
            val deferred = CompletableDeferred<String?>()
            pending = deferred
            _prompt.value = Prompt(permission, PermissionLabels.of(permission))
            try {
                withTimeoutOrNull(PROMPT_TIMEOUT_MS) { deferred.await() }
            } finally {
                _prompt.value = null
                pending = null
            }
        }
    }

    /** La UI llama esto al teclear el código. Sólo `Granted` cierra el diálogo. */
    open suspend fun submitPin(venueId: String, pin: String): OverrideResult {
        // 🔴 Se captura AQUÍ la espera que se va a resolver, antes de la red.
        //
        // Antes se leía `pending` al volver: si durante la llamada alguien
        // cerraba el teclado, el finally liberaba el Mutex, el siguiente de la
        // fila instalaba SU espera, y el token de esta acción terminaba
        // completando la de OTRA —con un permiso distinto, que el server
        // rechaza—. La segunda acción fallaba sin que nadie pudiera autorizarla
        // y su teclado se cerraba solo.
        //
        // Capturado, si esta espera ya se resolvió (cancelación, timeout),
        // `complete` no hace nada y el token simplemente se descarta.
        val awaiting = pending
        val permission = _prompt.value?.permission
            ?: return OverrideResult.Failed("La acción ya no está esperando autorización.")
        if (venueId.isBlank()) {
            return OverrideResult.Failed("No hay una sucursal activa. Vuelve a entrar.")
        }
        val result = repository.requestToken(venueId, pin, permission)
        if (result is OverrideResult.Granted) {
            Log.d("🔐 Override", "Autorizado por ${result.authorizedByName} para $permission")
            awaiting?.complete(result.token)
        }
        return result
    }

    /** El usuario cerró el teclado: la acción falla como fallaba antes. */
    open fun cancel() {
        pending?.complete(null)
    }
}
