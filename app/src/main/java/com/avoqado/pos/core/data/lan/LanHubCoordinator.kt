package com.avoqado.pos.core.data.lan

import android.util.Log
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_CONCEDIDA
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_ESPERAR
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_OCUPADA
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "LanHubCoordinator"

/**
 * Hub LAN, capa 2 — el COORDINADOR: descubrir → elegir → arbitrar y declarar.
 *
 * Espejo en avoqado-ios: Services/LAN/LanHubCoordinator.swift (iOS va después: lo que le falta va a `docs/proyectos/ios-por-hacer.md`).
 *
 * ── La regla que gobierna todo: DEGRADAR, NUNCA BLOQUEAR ───────────────────
 * Si no hay hub (árbitro caído, WiFi del local muerto, feature apagado), la apertura da [LeaseOutcome.NoHub] y el POS sigue
 * trabajando como isla — exactamente como antes de que existiera el hub. El hub sirve para PREVENIR conflictos, no para autorizar
 * ventas: nunca puede impedir que un mesero cobre.
 *
 * ── Hub D3: la caja DECLARA dónde está (spec §3.4) ──────────────────────────
 * Ya no se piden ni se renuevan permisos por mesa. UN reconciliador serial le manda al árbitro la declaración más nueva de esta
 * caja ([MesasDeEstaCaja]: su mesa actual y sus aperturas) cuando cambia, cada 10 s y cuando cambia el árbitro; nunca hay dos
 * mensajes de esta caja en vuelo. Cada respuesta se valida contra el árbitro y la época capturados al mandarla, y las aperturas se
 * resuelven por id. Las esperas viven en [MesasDeEstaCaja] (del proceso): un coordinador apagado no resuelve nada.
 */
class LanHubCoordinator(
    private val discovery: LanDiscoveryPort,
    private val mesas: MesasDeEstaCaja,
    private val deviceId: String,
    private val venueId: String,
    /** El nombre de quien opera esta caja: es el «quién» que ve otra caja cuando la mesa está ocupada. */
    private val nombre: () -> String,
    private val client: LeaseClient = LeaseClient(),
    server: LeaseServer? = null,
    relojMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    /** Contesta cuando me eligen árbitro; con el mismo reloj monotónico que la caja. */
    private val server = server ?: LeaseServer(deviceId = deviceId, monoMillis = relojMs)

    private val _isArbiter = MutableStateFlow(false)
    val isArbiter: StateFlow<Boolean> = _isArbiter.asStateFlow()

    private val _hubAvailable = MutableStateFlow(false)
    /** ¿Hay con quién coordinarse? Si es false, el POS trabaja como isla. */
    val hubAvailable: StateFlow<Boolean> = _hubAvailable.asStateFlow()

    private val _myTables = MutableStateFlow<Set<String>>(emptySet())
    /** Las mesas de esta caja según la última respuesta válida: las que declara y las de sus aperturas concedidas. */
    val myTables: StateFlow<Set<String>> = _myTables.asStateFlow()

    /** Conflado: muchos avisos mientras el reconciliador trabaja valen por UNA vuelta más, con la declaración más nueva. */
    private val despertador = Channel<Unit>(Channel.CONFLATED)

    /** Peers, cambios y el reconciliador: `stop` los cancela juntos. */
    private var trabajos: Job? = null

    /** Desde la 3.5 (D1) el hub NO abre socket ni descubrimiento: se engancha al transporte único y consume sus peers. */
    fun start() {
        discovery.conectarHub(server::respondTo)
        mesas.fijarVenue(venueId)
        mesas.nuevaEpoca()

        trabajos?.cancel()
        trabajos = scope.launch {
            launch {
                var elegido: String? = null
                discovery.peers.collect { peers ->
                    _isArbiter.value = ArbiterElection.isArbiter(deviceId, peers)
                    // Revisión M7: una pantalla sólo-cocina (`hub=0`) no es con quién coordinar mesas.
                    _hubAvailable.value = peers.any { it.sirveLeases }
                    val arbitro = ArbiterElection.pick(peers)?.deviceId
                    if (arbitro != elegido) {
                        elegido = arbitro
                        mesas.nuevaEpoca() // lo que conteste el árbitro anterior ya no vale (también X→Y→X)
                        if (arbitro == deviceId) {
                            server.asumirArbitraje(peers.filter { it.presencia && it.sirveLeases }.map { it.deviceId }.toSet() + deviceId)
                        } else {
                            server.dejarArbitraje()
                        }
                        despertar()
                    }
                }
            }
            launch { mesas.cambios.collect { despertar() } }
            launch {
                while (true) {
                    val ms = reconciliarUnaVez()
                    withTimeoutOrNull(ms) { despertador.receive() }
                }
            }
        }
    }

    fun stop() {
        val arbitro = ArbiterElection.pick(discovery.peers.value) // el último conocido, antes de desengancharse
        trabajos?.cancel() // no `scope.cancel()`: `sincronizarVenue` hace stop() → start() y el scope tiene que seguir vivo
        trabajos = null
        discovery.desconectarHub()
        mesas.nuevaEpoca() // una respuesta que le llegue tarde a este coordinador ya no resuelve nada
        _myTables.value = emptySet()
        _hubAvailable.value = false
        _isArbiter.value = false
        // Codex plan r2 R2-2: `stop()` sólo pasa al terminar el contexto (cerrar sesión, sesión inválida, otra sucursal), así que un
        // ticket vivo es de un intento que su `vigente` va a tirar. Su espera da NoHub (isla), que es seguro.
        mesas.soltarAperturas(venueId)

        // Despedida VERDADERA (Codex plan r1 #1): lo que esta caja declara AHORA, nunca una vacía inventada. Al cerrar sesión la mesa
        // ya se borró y declara «ninguna» (la libera enseguida); en un rearme declara la de siempre. Si cruza con el coordinador nuevo,
        // gana el `seq` mayor y las dos dicen la verdad de su momento.
        if (arbitro != null && arbitro.deviceId != deviceId && arbitro.presencia) {
            val seq = mesas.siguienteSeq()
            val declaracion = mesas.declaracion(venueId)
            scope.launch {
                val gen = mesas.gen ?: return@launch // la vida se lee de disco en IO, nunca en Main
                client.estado(arbitro, pedido(gen, seq, declaracion), LeaseClient.PLAZO_DESPEDIDA_MS)
            }
        }
    }

    /** Que el reconciliador dé una vuelta ya (la declaración pudo cambiar). */
    fun despertar() {
        despertador.trySend(Unit)
    }

    /**
     * Una vuelta del reconciliador: manda la declaración más nueva y aplica la respuesta si sigue valiendo. Devuelve los ms hasta la
     * siguiente vuelta. El orden importa (Codex plan r1 #7): el contexto local se revisa ANTES de mirar qué volvió.
     */
    internal suspend fun reconciliarUnaVez(): Long {
        // 1. Sin número de vida o sin árbitro: isla. La PRIMERA lectura de `gen` (disco + commit) cae aquí, en el scope de IO.
        val gen = mesas.gen
        val arbitro = ArbiterElection.pick(discovery.peers.value)
        if (gen == null || arbitro == null) return enIsla()

        // 2. Lo que valida la respuesta, capturado al mandar.
        val epoca = mesas.epoca()
        val seq = mesas.siguienteSeq()
        val declaracion = mesas.declaracion(venueId)
        val soyYo = arbitro.deviceId == deviceId

        // 3. Árbitro viejo (sin `pm=1`): el modo compatible es la Task 6 y recibe lo capturado. Mientras, se trata como «sin árbitro».
        if (!soyYo && !arbitro.presencia) return enIsla()

        // 4. Yo soy el árbitro: en local, sin dar la vuelta por la red.
        val pedido = pedido(gen, seq, declaracion)
        val resp = if (soyYo) server.respondTo(LeaseProtocol.encode(pedido)) else client.estado(arbitro, pedido)

        // 5. Primero el contexto local: si cambió la época o el árbitro elegido, nada de lo que volvió vale (ni null, ni stale, ni
        //    error). Es un mensaje nuevo para otro árbitro, no un reintento al mismo.
        if (mesas.epoca() != epoca || ArbiterElection.pick(discovery.peers.value)?.deviceId != arbitro.deviceId) return 0

        // 6 y 8. Sin respuesta, u `ok` de otro árbitro: lo conocido no cambia. Nunca 0: un rechazo instantáneo no hace un bucle sin pausa.
        if (resp == null || (resp.status == LeaseProtocol.STATUS_OK && resp.arbitro != arbitro.deviceId)) {
            return if (mesas.hayPendientes(venueId)) REINTENTO_MS else LATIDO_MS
        }

        // 7. `stale` o `error` (y cualquier otra cosa que no sea `ok`): ante la duda, isla.
        if (resp.status != LeaseProtocol.STATUS_OK) {
            Log.w(TAG, "🏝️ El árbitro ${arbitro.deviceId.take(6)} contestó ${resp.status} (${resp.message}) — aperturas en isla")
            return enIsla()
        }

        // 9. Cada veredicto, por id (`MesasDeEstaCaja` ignora los ids que ya no viven y se queda con la PRIMERA resolución).
        val resultados = resp.aperturas.orEmpty()
        resultados.forEach { r ->
            when (r.resultado) {
                APERTURA_CONCEDIDA -> mesas.resolver(r.id, LeaseOutcome.Granted)
                APERTURA_OCUPADA -> mesas.resolver(r.id, LeaseOutcome.Taken(r.quien ?: "otro mesero"))
                APERTURA_ESPERAR -> mesas.marcarEsperando(r.id)
            }
        }

        // 10.
        val concedidas = resultados.filter { it.resultado == APERTURA_CONCEDIDA }.mapTo(HashSet()) { it.id }
        _myTables.value = declaracion.mesas + declaracion.aperturas.filter { it.id in concedidas }.map { it.tableId }

        // 11. El árbitro que calienta dice cuándo volver a preguntar.
        val esperar = resultados.filter { it.resultado == APERTURA_ESPERAR }.mapNotNull { it.esperarMs }.minOrNull()
        return minOf(LATIDO_MS, esperar ?: LATIDO_MS)
    }

    private fun enIsla(): Long {
        mesas.resolverPendientes(venueId, LeaseOutcome.NoHub)
        return LATIDO_MS
    }

    private fun pedido(gen: Long, seq: Long, d: MesasDeEstaCaja.Declaracion) = LeaseRequest(
        op = LeaseProtocol.OP_ESTADO,
        deviceId = deviceId,
        staffName = nombre(),
        gen = gen,
        seq = seq,
        mesas = d.mesas.toList(),
        aperturas = d.aperturas.map { WireApertura(it.id, it.tableId) },
    )

    private companion object {
        /** Cada cuánto se declara aunque nada cambie: la presencia vence a los 30 s. */
        const val LATIDO_MS = LeaseRegistry.RENEW_INTERVAL_MILLIS

        /** Sin respuesta y con una apertura esperando: se reintenta pronto, nunca en 0. */
        const val REINTENTO_MS = 1_000L
    }
}

/** Qué hacer en la UI después de pedir una mesa. */
sealed class LeaseOutcome {
    data object Granted : LeaseOutcome()

    /** Otro mesero la tiene. [holderName] se muestra tal cual. */
    data class Taken(val holderName: String) : LeaseOutcome()

    /**
     * No hay hub con quien coordinar (solo en la red, árbitro caído, feature
     * apagado). Se sigue como isla: NUNCA se bloquea al mesero.
     */
    data object NoHub : LeaseOutcome()
}
