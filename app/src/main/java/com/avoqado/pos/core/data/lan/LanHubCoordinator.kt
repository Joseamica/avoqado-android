package com.avoqado.pos.core.data.lan

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** D12: el árbitro que concedió cada llave de [acquireCoordinado] y su última confirmación (reloj LOCAL). Sólo esas llaves. */
    private val coordinados = mutableMapOf<String, ArbitroQueConcedio>()

    private val _myTables = MutableStateFlow<Set<String>>(emptySet())
    val myTables: StateFlow<Set<String>> = _myTables.asStateFlow()

    /** Los colectores de `start`: `stop` los cancela (antes vivían para siempre — una fuga por cada cambio de sucursal, M12). */
    private var trabajos: Job? = null

    /** Desde la 3.5 (D1) el hub NO abre socket ni descubrimiento: se engancha al transporte único y consume sus peers. */
    fun start() {
        discovery.conectarHub(server::respondTo)

        trabajos?.cancel()
        trabajos = scope.launch {
            launch {
                discovery.peers.collect { peers ->
                    _isArbiter.value = ArbiterElection.isArbiter(deviceId, peers)
                    // Revisión M7: una pantalla sólo-cocina (`hub=0`) no es con quién coordinar mesas.
                    _hubAvailable.value = peers.any { it.sirveLeases }
                }
            }
            launch { renewLoop() }
        }
    }

    fun stop() {
        trabajos?.cancel() // no `scope.cancel()`: `sincronizarVenue` hace stop() → start() y el scope tiene que seguir vivo
        trabajos = null
        discovery.desconectarHub()
        heldLeases.clear()
        coordinados.clear()
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

    /** D12: otra caja de MI sucursal (anuncio con `venue` explícito) que contesta leases. Sólo para pintar antes del toque. */
    private fun otrasCajas(): List<LanPeer> =
        discovery.peers.value.filter { it.deviceId != deviceId && it.venueExplicito && it.sirveLeases }

    fun hayOtraCajaDelNegocio(): Boolean = otrasCajas().isNotEmpty()

    /** La primera respuesta `list` VIGENTE de otra caja de la sucursal: la prueba de que alguien más está en la red. */
    private suspend fun listaDeOtraCaja(): LeaseResponse? {
        for (peer in otrasCajas()) {
            client.list(peer)?.takeIf { it.status == LeaseProtocol.STATUS_LEASES }?.let { return it }
        }
        return null
    }

    private fun LeaseResponse.ocupadaPorOtro(key: String): WireLease? =
        leases.orEmpty().firstOrNull { it.tableId == key && it.holderDeviceId != deviceId }

    private fun local(op: String, key: String, epoch: Long = 0, staffId: String = "", staffName: String = ""): LeaseResponse =
        server.respondTo(
            LeaseProtocol.encode(
                LeaseRequest(op = op, tableId = key, deviceId = deviceId, staffId = staffId, staffName = staffName, epoch = epoch),
            ),
        )

    private fun LanPeer.esElMismo(arbitro: ArbitroQueConcedio): Boolean =
        deviceId == arbitro.deviceId && bootedAtMillis == arbitro.bootedAtMillis

    /**
     * D12 (paso 2, Codex r1 #2, r2 #3, r3 H3) — el candado de una cuenta AJENA (`cuenta:<orderId>`). A diferencia de
     * [acquire], sólo se concede con coordinación REAL: otra caja de la sucursal tiene que contestar por la red, aunque
     * yo sea el árbitro. Cualquier duda ⇒ [LeaseOutcome.NoHub] (la cuenta queda en gris en ESTA caja; la que la abrió
     * conserva su salida con [acquire]).
     */
    suspend fun acquireCoordinado(key: String, staffId: String, staffName: String): LeaseOutcome {
        if (otrasCajas().isEmpty()) return LeaseOutcome.NoHub
        val arbitro = currentArbiter() ?: return LeaseOutcome.NoHub
        val outcome = if (arbitro.deviceId != deviceId) {
            if (!arbitro.venueExplicito) return LeaseOutcome.NoHub
            // Su respuesta ES la prueba de vida: un anuncio viejo con el WiFi caído no contesta.
            interpret(key, client.acquire(arbitro, key, deviceId, staffId, staffName) ?: return LeaseOutcome.NoHub)
        } else {
            val lista = listaDeOtraCaja() ?: return LeaseOutcome.NoHub
            lista.ocupadaPorOtro(key)?.let { return LeaseOutcome.Taken(it.holderName) }
            interpret(key, local(LeaseProtocol.OP_ACQUIRE, key, staffId = staffId, staffName = staffName))
        }
        if (outcome !is LeaseOutcome.Granted) return outcome
        val concedio = ArbitroQueConcedio(arbitro.deviceId, arbitro.bootedAtMillis, nowMillis())
        if (currentArbiter()?.esElMismo(concedio) != true) {
            // El árbitro cambió mientras contestaba: lo concedido ya no lo respalda nadie.
            soltar(key, arbitro)
            return LeaseOutcome.NoHub
        }
        coordinados[key] = concedio
        return outcome
    }

    /**
     * D12 (Codex r3 H3): ¿sigue valiendo el candado de [acquireCoordinado]? Se pregunta JUSTO antes de recibir el efectivo.
     * Sólo si la llave sigue mía, el árbitro es el MISMO que la concedió (aparato y arranque), la última confirmación
     * tiene menos de un TTL en MI reloj, y una comprobación EN VIVO pasa. Si no, la llave se suelta en local.
     */
    suspend fun candadoVigente(key: String): Boolean {
        val concedio = coordinados[key] ?: return false
        val lease = heldLeases[key]
        val arbitro = currentArbiter()
        val vigente = lease != null && arbitro != null && arbitro.esElMismo(concedio) &&
            nowMillis() - concedio.confirmadoEnMillis < LeaseRegistry.DEFAULT_TTL_MILLIS &&
            confirmarEnVivo(key, lease, arbitro) &&
            currentArbiter()?.esElMismo(concedio) == true
        if (!vigente) soltarEnLocal(key)
        return vigente
    }

    private suspend fun confirmarEnVivo(key: String, lease: TableLease, arbitro: LanPeer): Boolean {
        val renovada = if (arbitro.deviceId == deviceId) {
            local(LeaseProtocol.OP_RENEW, key, epoch = lease.epoch)
        } else {
            client.renew(arbitro, key, deviceId, lease.epoch) ?: return false
        }
        val nueva = renovada.lease?.toDomain()
        if (renovada.status != LeaseProtocol.STATUS_GRANTED || nueva == null) return false
        if (arbitro.deviceId == deviceId) {
            val lista = listaDeOtraCaja() ?: return false
            if (lista.ocupadaPorOtro(key) != null) return false
        }
        heldLeases[key] = nueva
        coordinados[key]?.let { coordinados[key] = it.copy(confirmadoEnMillis = nowMillis()) }
        return true
    }

    /** Quita la llave de las mías y de MI registro, por si fui yo quien la concedió (sin red: nunca espera a otro aparato). */
    private fun soltarEnLocal(key: String) {
        coordinados.remove(key)
        val lease = heldLeases.remove(key) ?: return
        _myTables.value = heldLeases.keys.toSet()
        local(LeaseProtocol.OP_RELEASE, key, epoch = lease.epoch)
    }

    /** Suelta la mesa al cerrar la cuenta o salir del panel. */
    suspend fun release(tableId: String) = soltar(tableId, currentArbiter())

    /** Suelta la llave con [arbiter] (el que la concedió, si se sabe; si no, el vigente). */
    private suspend fun soltar(tableId: String, arbiter: LanPeer?) {
        coordinados.remove(tableId)
        val lease = heldLeases.remove(tableId) ?: return
        _myTables.value = heldLeases.keys.toSet()
        if (arbiter == null) return
        if (arbiter.deviceId == deviceId) {
            local(LeaseProtocol.OP_RELEASE, tableId, epoch = lease.epoch)
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
                    LeaseProtocol.STATUS_GRANTED -> response.lease?.toDomain()?.let {
                        heldLeases[tableId] = it
                        coordinados[tableId]?.takeIf { c -> arbiter.esElMismo(c) }?.let { c ->
                            coordinados[tableId] = c.copy(confirmadoEnMillis = nowMillis())
                        }
                    }
                    LeaseProtocol.STATUS_DENIED, LeaseProtocol.STATUS_STALE -> {
                        Log.w(TAG, "🧟 Perdí la mesa $tableId (época vieja) — la suelto en la UI")
                        heldLeases.remove(tableId)
                        coordinados.remove(tableId)
                        _myTables.value = heldLeases.keys.toSet()
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** D12: quién concedió una llave de `acquireCoordinado` (un reinicio cambia el `boot`) y cuándo se confirmó en MI reloj. */
private data class ArbitroQueConcedio(val deviceId: String, val bootedAtMillis: Long, val confirmadoEnMillis: Long)

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
