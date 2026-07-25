package com.avoqado.pos.core.data.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LanHubService"

/**
 * Hub LAN — punto de entrada de la app: arma el coordinador y lo enciende.
 *
 * Existe para que el resto del POS no tenga que saber nada de mDNS ni de
 * sockets: pide una mesa y recibe un [LeaseOutcome].
 *
 * ── Por qué reusa el deviceId del outbox ──────────────────────────────────
 * El outbox ya tiene un identificador ESTABLE por instalación (el que
 * particiona los folios offline "47-001"). Inventar otro aquí abriría la puerta
 * a que el mismo POS se viera a sí mismo como dos peers distintos tras un
 * reinicio, y la elección de árbitro dejaría de ser estable.
 */
@Singleton
class LanHubService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
    private val syncOutbox: SyncOutbox,
) {
    private var coordinator: LanHubCoordinator? = null

    private val _enabled = MutableStateFlow(false)
    /** ¿El hub está corriendo? Falso = el POS trabaja como isla. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Mesas que ESTE dispositivo sostiene (para pintarlas distinto). */
    val myTables: StateFlow<Set<String>>? get() = coordinator?.myTables

    val isArbiter: StateFlow<Boolean>? get() = coordinator?.isArbiter

    /**
     * Enciende el hub. Idempotente: llamarlo dos veces no abre dos servidores.
     *
     * Requiere venue: sin él no se puede filtrar a los peers de OTRO negocio
     * (plazas y food courts comparten WiFi) y arbitrar mesas ajenas sería
     * catastrófico y silencioso.
     */
    fun start() {
        if (coordinator != null) return
        val venueId = secureStorage.venueId ?: run {
            Log.d(TAG, "Sin venue todavía — el hub no arranca")
            return
        }
        val deviceId = syncOutbox.deviceId

        val discovery = LanDiscovery(context = context, deviceId = deviceId, venueId = venueId)
        val hub = LanHubCoordinator(discovery = discovery, deviceId = deviceId)
        coordinator = hub
        hub.start(isWired = isWiredConnection(), bootedAtMillis = bootedAtMillis())
        _enabled.value = true
        Log.i(TAG, "🛰️ Hub LAN encendido | venue=$venueId device=${deviceId.take(6)}")
    }

    fun stop() {
        coordinator?.stop()
        coordinator = null
        _enabled.value = false
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

    /**
     * Ethernet/dock con cable → mejor candidato a árbitro (no se mueve por el
     * salón ni pierde señal). Es el mismo criterio que recomienda Toast.
     */
    private fun isWiredConnection(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    }.getOrDefault(false)

    /**
     * Momento de arranque del dispositivo. Se deriva del uptime en vez de leer
     * un reloj de pared: los relojes de dos tablets pueden ir desfasados y la
     * elección quedaría a merced de eso. El uptime siempre crece igual.
     */
    private fun bootedAtMillis(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
}
