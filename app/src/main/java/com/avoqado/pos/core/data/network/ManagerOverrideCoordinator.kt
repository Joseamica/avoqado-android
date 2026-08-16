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

    private val queue = Mutex()

    @Volatile
    private var pending: CompletableDeferred<String?>? = null

    /** Devuelve el token, o null si el usuario canceló / no se pudo. */
    open fun awaitToken(permission: String): String? = runBlocking {
        queue.withLock {
            val deferred = CompletableDeferred<String?>()
            pending = deferred
            _prompt.value = Prompt(permission, PermissionLabels.of(permission))
            try {
                deferred.await()
            } finally {
                _prompt.value = null
                pending = null
            }
        }
    }

    /** La UI llama esto al teclear el código. Sólo `Granted` cierra el diálogo. */
    open suspend fun submitPin(venueId: String, pin: String): OverrideResult {
        val permission = _prompt.value?.permission
            ?: return OverrideResult.Failed("La acción ya no está esperando autorización.")
        if (venueId.isBlank()) {
            return OverrideResult.Failed("No hay una sucursal activa. Vuelve a entrar.")
        }
        val result = repository.requestToken(venueId, pin, permission)
        if (result is OverrideResult.Granted) {
            Log.d("🔐 Override", "Autorizado por ${result.authorizedByName} para $permission")
            pending?.complete(result.token)
        }
        return result
    }

    /** El usuario cerró el teclado: la acción falla como fallaba antes. */
    open fun cancel() {
        pending?.complete(null)
    }
}
