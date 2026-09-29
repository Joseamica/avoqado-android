package com.avoqado.pos.core.data.lan

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LanHubService"

/**
 * Hub LAN — punto de entrada de la app: arma el coordinador y lo ENGANCHA al transporte único (3.5, D1).
 *
 * Existe para que el resto del POS no tenga que saber nada de mDNS ni de sockets: pide una mesa y recibe un
 * [LeaseOutcome]. El `deviceId`, el cableado y el `boot` los pone el transporte (uno por aparato, regla §2.4 del hub).
 *
 * Preexistente y arreglado aquí (D12): `start()` era idempotente por instancia y NUNCA se reiniciaba al cambiar de
 * venue (el TXT y el filtro se quedaban con el viejo), y `stop()` no tenía llamadores. Ahora se recuerda el venue, se
 * desengancha al cambiar ([sincronizarVenue], desde `AppState`) y se apaga al cerrar sesión.
 */
@Singleton
class LanHubService @Inject constructor(
    private val secureStorage: SecureStorage,
    private val transporte: TransporteLan,
) {
    private var coordinator: LanHubCoordinator? = null
    private var venueActivo: String? = null

    private val _enabled = MutableStateFlow(false)
    /** ¿El hub está corriendo? Falso = el POS trabaja como isla. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Mesas que ESTE dispositivo sostiene (para pintarlas distinto). */
    val myTables: StateFlow<Set<String>>? get() = coordinator?.myTables

    val isArbiter: StateFlow<Boolean>? get() = coordinator?.isArbiter

    /**
     * Enciende el hub. Idempotente POR VENUE: llamarlo dos veces no engancha dos coordinadores; con otro venue rearma.
     * Requiere venue: sin él no se puede filtrar a los peers de OTRO negocio.
     */
    fun start() {
        val venueId = secureStorage.venueId ?: run {
            Log.d(TAG, "Sin venue todavía — el hub no arranca")
            return
        }
        if (coordinator != null && venueActivo == venueId) return
        stop()
        transporte.iniciar(venueId)
        val hub = LanHubCoordinator(discovery = transporte, deviceId = transporte.deviceId)
        coordinator = hub
        venueActivo = venueId
        hub.start()
        _enabled.value = true
        Log.i(TAG, "🛰️ Hub LAN enganchado | venue=$venueId device=${transporte.deviceId.take(6)}")
    }

    fun stop() {
        coordinator?.stop()
        coordinator = null
        venueActivo = null
        _enabled.value = false
    }

    /** Cambió la sucursal (`AppState.startOfflineOutbox`): el hub del venue viejo se desengancha; `TablesViewModel` lo rearma al abrir el plano. */
    fun sincronizarVenue(venueId: String) {
        if (coordinator != null && venueActivo != venueId) stop()
    }

    /**
     * Pide la mesa antes de abrirla. Si el hub está apagado devuelve
     * [LeaseOutcome.NoHub] — modo isla, NUNCA bloquea al mesero.
     */
    suspend fun acquire(tableId: String, staffId: String, staffName: String): LeaseOutcome =
        coordinator?.acquire(tableId, staffId, staffName) ?: LeaseOutcome.NoHub

    suspend fun release(tableId: String) {
        coordinator?.release(tableId)
    }
}
