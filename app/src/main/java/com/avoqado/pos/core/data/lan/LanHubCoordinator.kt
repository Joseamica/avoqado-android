package com.avoqado.pos.core.data.lan

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "LanHubCoordinator"

/**
 * Hub LAN, capa 2 — el COORDINADOR: descubrir → elegir → arbitrar o pedir.
 *
 * Espejo EXACTO en avoqado-ios: Services/LAN/LanHubCoordinator.swift.
 *
 * ── La regla que gobierna todo: DEGRADAR, NUNCA BLOQUEAR ───────────────────
 * Si no hay hub (árbitro caído, WiFi del local muerto, feature apagado), este
 * coordinador devuelve [LeaseOutcome.NoHub] y el POS sigue trabajando como
 * isla — exactamente como antes de que existiera el hub. El conflicto se
 * detecta al reconectar y cae en cuarentena. El hub sirve para PREVENIR
 * conflictos, no para autorizar ventas: nunca puede impedir que un mesero
 * cobre.
 */
class LanHubCoordinator(
    private val discovery: LanDiscoveryPort,
    private val server: LeaseServer = LeaseServer(),
    private val client: LeaseClient = LeaseClient(),
    private val deviceId: String,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isArbiter = MutableStateFlow(false)
    val isArbiter: StateFlow<Boolean> = _isArbiter.asStateFlow()

    private val _hubAvailable = MutableStateFlow(false)
    /** ¿Hay con quién coordinarse? Si es false, el POS trabaja como isla. */
    val hubAvailable: StateFlow<Boolean> = _hubAvailable.asStateFlow()

    /** Leases que ESTE dispositivo sostiene ahora: tableId → lease. */
    private val heldLeases = mutableMapOf<String, TableLease>()

    private val _myTables = MutableStateFlow<Set<String>>(emptySet())
    val myTables: StateFlow<Set<String>> = _myTables.asStateFlow()

    fun start(isWired: Boolean, bootedAtMillis: Long) {
        val port = server.start()
        if (port <= 0) {
            Log.w(TAG, "Sin socket propio: no puedo ser árbitro, pero sí cliente")
        }
        discovery.start(myPort = port, isWired = isWired, bootedAtMillis = bootedAtMillis)

        scope.launch {
            discovery.peers.collect { peers ->
                val amArbiter = ArbiterElection.isArbiter(deviceId, peers)
                _isArbiter.value = amArbiter
                _hubAvailable.value = peers.isNotEmpty()
                if (amArbiter && !server.isRunning) server.start()
            }
        }
        scope.launch { renewLoop() }
    }

    fun stop() {
        discovery.stop()
        server.stop()
        heldLeases.clear()
        _myTables.value = emptySet()
        _hubAvailable.value = false
        _isArbiter.value = false
    }

    /** El árbitro vigente según lo descubierto, o null si estoy solo. */
    private fun currentArbiter(): LanPeer? = ArbiterElection.pick(discovery.peers.value)

    /**
     * Pide la mesa antes de abrirla. La respuesta manda la UI:
     * - [LeaseOutcome.Granted] → adelante.
     * - [LeaseOutcome.Taken] → la tiene otro mesero, con su nombre.
     * - [LeaseOutcome.NoHub] → no hay con quién coordinar: MODO ISLA, se abre
     *   igual (el server arbitra al reconectar).
     */
    suspend fun acquire(tableId: String, staffId: String, staffName: String): LeaseOutcome {
        val arbiter = currentArbiter() ?: return LeaseOutcome.NoHub

        // Yo soy el árbitro: resolver en local, sin dar la vuelta por la red.
        if (arbiter.deviceId == deviceId) {
            val response = server.respondTo(
                LeaseProtocol.encode(
                    LeaseRequest(
                        op = LeaseProtocol.OP_ACQUIRE, tableId = tableId,
                        deviceId = deviceId, staffId = staffId, staffName = staffName,
                    ),
                ),
            )
            return interpret(tableId, response)
        }

        val response = client.acquire(arbiter, tableId, deviceId, staffId, staffName)
            ?: return LeaseOutcome.NoHub // el árbitro no contesta → isla
        return interpret(tableId, response)
    }

    /** Suelta la mesa al cerrar la cuenta o salir del panel. */
    suspend fun release(tableId: String) {
        val lease = heldLeases.remove(tableId) ?: return
        _myTables.value = heldLeases.keys.toSet()
        val arbiter = currentArbiter() ?: return
        if (arbiter.deviceId == deviceId) {
            server.respondTo(
                LeaseProtocol.encode(
                    LeaseRequest(op = LeaseProtocol.OP_RELEASE, tableId = tableId, deviceId = deviceId, epoch = lease.epoch),
                ),
            )
        } else {
            client.release(arbiter, tableId, deviceId, lease.epoch)
        }
    }

    private fun interpret(tableId: String, response: LeaseResponse): LeaseOutcome = when (response.status) {
        LeaseProtocol.STATUS_GRANTED -> {
            val lease = response.lease?.toDomain()
            if (lease == null) {
                LeaseOutcome.NoHub
            } else {
                heldLeases[tableId] = lease
                _myTables.value = heldLeases.keys.toSet()
                LeaseOutcome.Granted(lease)
            }
        }
        LeaseProtocol.STATUS_DENIED -> LeaseOutcome.Taken(response.holder?.holderName ?: "otro mesero")
        // VERSION_MISMATCH y demás errores del árbitro NO pueden bloquear una
        // venta: se degrada a isla, que es seguro (el server arbitra después).
        else -> LeaseOutcome.NoHub
    }

    /**
     * Renueva en segundo plano las mesas que sostengo. Si el árbitro contesta
     * que ya no soy dueño (me quedé sin señal y caducó), suelto la mesa en la
     * UI en vez de seguir creyéndome dueño — el caso del dispositivo zombi.
     */
    private suspend fun renewLoop() {
        while (scope.isActive) {
            delay(LeaseRegistry.RENEW_INTERVAL_MILLIS)
            if (heldLeases.isEmpty()) continue
            val arbiter = currentArbiter() ?: continue

            for ((tableId, lease) in heldLeases.toMap()) {
                val response = if (arbiter.deviceId == deviceId) {
                    server.respondTo(
                        LeaseProtocol.encode(
                            LeaseRequest(op = LeaseProtocol.OP_RENEW, tableId = tableId, deviceId = deviceId, epoch = lease.epoch),
                        ),
                    )
                } else {
                    client.renew(arbiter, tableId, deviceId, lease.epoch) ?: continue // sin respuesta: NO soltar aún
                }

                when (response.status) {
                    LeaseProtocol.STATUS_GRANTED -> response.lease?.toDomain()?.let { heldLeases[tableId] = it }
                    LeaseProtocol.STATUS_DENIED, LeaseProtocol.STATUS_STALE -> {
                        Log.w(TAG, "🧟 Perdí la mesa $tableId (época vieja) — la suelto en la UI")
                        heldLeases.remove(tableId)
                        _myTables.value = heldLeases.keys.toSet()
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** Qué hacer en la UI después de pedir una mesa. */
sealed class LeaseOutcome {
    data class Granted(val lease: TableLease) : LeaseOutcome()

    /** Otro mesero la tiene. [holderName] se muestra tal cual. */
    data class Taken(val holderName: String) : LeaseOutcome()

    /**
     * No hay hub con quien coordinar (solo en la red, árbitro caído, feature
     * apagado). Se sigue como isla: NUNCA se bloquea al mesero.
     */
    data object NoHub : LeaseOutcome()
}
