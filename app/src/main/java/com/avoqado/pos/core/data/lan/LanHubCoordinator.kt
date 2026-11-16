package com.avoqado.pos.core.data.lan

import android.util.Log
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_CONCEDIDA
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_ESPERAR
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_OCUPADA
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
 *
 * Con un árbitro VIEJO (sin `pm=1`: ANDROID previo o iOS) el mismo reconciliador entra en modo compatible ([vueltaCompat]) y traduce
 * la declaración a los `acquire`/`renew`/`release` de siempre, uno a la vez.
 *
 * ── Hub D6 bandas: además, la pregunta DIRECTA (hz:033d44ca:D6, 16-nov) ─────
 * Cada caja elige al árbitro con SUS peers. Con un WiFi que aísla una banda (la D3 y la SAMSUNG en 5 GHz no se ven; las dos ven a la
 * N86 en 2.4 GHz) la N86 le declaraba a la SAMSUNG y la D3 se preguntaba a sí misma: abrió la mesa del mesero sin aviso. Por eso cada
 * apertura también le pregunta `en_mesa` a cada caja que alcanza ([consultarDirecto]) y cada caja contesta de sí misma ([responder]).
 * Una «ocupada» directa gana; «concedida» o «isla» del árbitro esperan a que la pregunta termine (`MesasDeEstaCaja.esperar`).
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
    private val relojMs: () -> Long = { System.nanoTime() / 1_000_000 },
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

    /** Sólo pruebas (Codex código r1 #3): suena dentro del candado de la época, con la respuesta ya validada y antes de aplicarla. */
    internal var trasValidar: () -> Unit = {}

    /** Desde la 3.5 (D1) el hub NO abre socket ni descubrimiento: se engancha al transporte único y consume sus peers. */
    fun start() {
        discovery.conectarHub(::responder)
        mesas.fijarVenue(venueId)
        mesas.nuevaEpoca()
        mesas.consultaDirecta = true
        enConsulta.clear() // las de un arranque anterior las retoma este (la pregunta vieja se canceló con su trabajo)

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
                        // Lo que conteste el árbitro anterior ya no vale. Un X→Y→X que este colector no alcanza a ver (el StateFlow sólo
                        // le entrega el final) lo cubre `cambiosDeArbitro` en `sigueElegido` (Codex código r2 H2).
                        mesas.nuevaEpoca()
                        if (arbitro == deviceId) {
                            server.asumirArbitraje(peers.filter { it.presencia && it.sirveLeases }.map { it.deviceId }.toSet() + deviceId)
                        } else {
                            server.dejarArbitraje()
                        }
                        despertar()
                    }
                }
            }
            launch {
                val aqui = this // la pregunta directa vive con el coordinador: `stop` la cancela junto con lo demás
                mesas.cambios.collect { consultarNuevas(aqui); despertar() }
            }
            launch {
                // `isActive` arriba de cada vuelta: tras `stop()` una vuelta devuelve 0 y `withTimeoutOrNull(0)` no suspende, así que
                // sin esto el bucle cancelado daba UNA vuelta completa más (y hasta mandaba otro `estado`).
                while (isActive) {
                    val ms = try {
                        reconciliarUnaVez()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Una vuelta que truena (p. ej. `nombre()` leyendo SecureStorage) no mata al coordinador: espera el latido.
                        Log.w(TAG, "⚠️ La vuelta del reconciliador falló; reintento al latido", e)
                        LATIDO_MS
                    }
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
        mesas.consultaDirecta = false // antes de soltar: una apertura nueva ya no espera una pregunta que nadie hará
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

    /** Hub D6 bandas: las cajas que hoy le declaran a ESTE aparato como árbitro. Para la línea del plano: con ellas sí se coordina. */
    fun cajasQueLeDeclaran(): Set<String> = server.cajasQueDeclaran() - deviceId

    // ── Hub D6 bandas: la pregunta directa ────────────────────────────────────

    /** Las aperturas que todavía no se preguntaron, cada una UNA vez, en el trabajo del coordinador. */
    private val enConsulta = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun consultarNuevas(en: CoroutineScope) {
        for (a in mesas.porConsultarDirecto()) {
            if (!enConsulta.add(a.id)) continue
            en.launch {
                try {
                    consultarDirecto(a)
                } finally {
                    enConsulta.remove(a.id)
                }
            }
        }
    }

    /**
     * «¿Estás en esta mesa?» a cada caja que se alcanza (`pm=1`, `hub=1` y de esta sucursal en su anuncio), en paralelo, ≤ 1.5 s cada una.
     * La primera «sí» resuelve en cuanto llega; al final, «nadie» (no cambia una «sí» ya puesta). Sin respuesta, error o app vieja: «no
     * sé» de esa caja (isla con ella), nunca bloquea.
     */
    internal suspend fun consultarDirecto(a: Apertura) {
        val otras = discovery.peers.value
            .filter { it.deviceId != deviceId && it.presencia && it.sirveLeases && it.venueExplicito }
            .distinctBy { it.deviceId }
        if (otras.isNotEmpty()) {
            val pedido = LeaseRequest(op = LeaseProtocol.OP_EN_MESA, tableId = a.tableId, deviceId = deviceId, venueId = venueId)
            coroutineScope {
                otras.forEach { p ->
                    launch {
                        val r = try {
                            client.enMesa(p, pedido)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        val h = r?.holder?.takeIf { r.status == LeaseProtocol.STATUS_OK && it.tableId == a.tableId } ?: return@launch
                        Log.i(TAG, "🤝 ${p.deviceId.take(6)} dice que está en la mesa ${a.tableId}: no se abre aquí")
                        mesas.resolverDirecto(a.id, LeaseOutcome.Taken(h.holderName.ifBlank { "otro mesero" }))
                    }
                }
            }
        }
        mesas.resolverDirecto(a.id, null)
    }

    /**
     * Lo que contesta esta caja por el transporte: `en_mesa` de sí misma; lo demás, el [LeaseServer] de siempre. Corre en el hilo del
     * socket: nada de red ni de suspensión.
     */
    internal fun responder(linea: String): LeaseResponse {
        val pedido = LeaseProtocol.decodeRequest(linea)
        if (pedido?.op != LeaseProtocol.OP_EN_MESA) return server.respondTo(linea)
        if (pedido.version != LeaseProtocol.PROTOCOL_VERSION) {
            return LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = LeaseProtocol.ERROR_VERSION_MISMATCH)
        }
        if (pedido.venueId != venueId) return LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Venue ajeno")
        if (pedido.tableId.isEmpty() || pedido.tableId.length > MAX_MESA) return LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Mesa inválida")
        if (!mesas.ocupa(pedido.tableId, venueId)) return LeaseResponse(status = LeaseProtocol.STATUS_OK)
        val quien = runCatching { nombre() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "otro mesero"
        return LeaseResponse(
            status = LeaseProtocol.STATUS_OK,
            holder = WireLease(tableId = pedido.tableId, holderDeviceId = deviceId, holderStaffId = "", holderName = quien, epoch = 0, expiresAtMillis = 0),
        )
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
        // 0. Lo que valida esta vuelta, ANTES de leer nada más (Codex código r2 H1 y H2): la época y cuántas veces cambió el árbitro.
        val epoca = mesas.epoca()
        val cambios = discovery.cambiosDeArbitro

        // 1. Sin número de vida o sin árbitro: isla. La PRIMERA lectura de `gen` (disco + commit) cae aquí, en el scope de IO.
        val gen = mesas.gen
        val arbitro = ArbiterElection.pick(discovery.peers.value)
        if (gen == null || arbitro == null) {
            // Codex código r2 H1: «sin Hub» se aplica bajo el candado de la época y mirando los peers de AHORA. Si mientras tanto llegó
            // un árbitro (o subió la época), una apertura pedida después no sale en isla: la siguiente vuelta se la pregunta.
            return aplicarSiSigue(epoca, { gen == null || ArbiterElection.pick(discovery.peers.value) == null }) { trasValidar(); enIsla() } ?: 0
        }

        // 2. Lo que se manda.
        val seq = mesas.siguienteSeq()
        val declaracion = mesas.declaracion(venueId)
        val soyYo = arbitro.deviceId == deviceId

        // 3. Árbitro viejo (sin `pm=1`): el modo compatible traduce la declaración a permisos viejos, con lo capturado.
        if (!soyYo && !arbitro.presencia) return vueltaCompat(arbitro, epoca, cambios, declaracion)

        // 4. Yo soy el árbitro: en local, sin dar la vuelta por la red.
        val pedido = pedido(gen, seq, declaracion)
        val resp = if (soyYo) server.respondTo(LeaseProtocol.encode(pedido)) else client.estado(arbitro, pedido)

        // 5. Primero el contexto local: si cambió la época o el árbitro elegido, nada de lo que volvió vale (ni null, ni stale, ni
        //    error). Es un mensaje nuevo para otro árbitro, no un reintento al mismo. Revisar y aplicar son UNA operación (Codex código
        //    r1 #3): una época nueva de otro hilo espera a que esto termine o lo encuentra ya invalidado.
        return aplicarSiSigue(epoca, { sigueElegido(arbitro, cambios) }) {
            trasValidar()
            aplicarRespuesta(resp, arbitro, declaracion)
        } ?: 0
    }

    /** Pasos 6 a 11 de [reconciliarUnaVez], dentro del candado de la época: nada de red ni de suspensión aquí. */
    private fun aplicarRespuesta(resp: LeaseResponse?, arbitro: LanPeer, declaracion: MesasDeEstaCaja.Declaracion): Long {
        // 6 y 8. Sin respuesta, u `ok` de otro árbitro: lo conocido no cambia. Nunca 0: un rechazo instantáneo no hace un bucle sin pausa.
        if (resp == null || (resp.status == LeaseProtocol.STATUS_OK && resp.arbitro != arbitro.deviceId)) {
            return if (mesas.hayPendientes(venueId)) REINTENTO_MS else LATIDO_MS
        }

        // 7. `stale` o `error` (y cualquier otra cosa que no sea `ok`): ante la duda, isla, pero sólo para las aperturas que viajaron en
        //    ESTA declaración (Codex código r2, misma familia que H1): una apertura nacida después no la vio el árbitro y se le pregunta.
        if (resp.status != LeaseProtocol.STATUS_OK) {
            Log.w(TAG, "🏝️ El árbitro ${arbitro.deviceId.take(6)} contestó ${resp.status} (${resp.message}) — aperturas en isla")
            declaracion.aperturas.forEach { mesas.resolver(it.id, LeaseOutcome.NoHub) }
            return if (mesas.hayPendientes(venueId)) REINTENTO_MS else LATIDO_MS
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

        // 11. El árbitro que calienta dice cuándo volver a preguntar; un 0 o un negativo de otro aparato no hace un bucle sin pausa.
        val esperar = resultados.filter { it.resultado == APERTURA_ESPERAR }.mapNotNull { it.esperarMs }.minOrNull()
        return minOf(LATIDO_MS, esperar ?: LATIDO_MS).coerceAtLeast(1)
    }

    /**
     * Revisar y aplicar como UNA operación frente a los dos que pueden invalidarla (Codex código r1 #3 y r3): la época nueva
     * ([MesasDeEstaCaja.nuevaEpoca]) y una elección publicada por el transporte ([LanDiscoveryPort.sinCambiarDeArbitro]). Orden de
     * candados: transporte → época; nada que tome el de la época pide después el del transporte.
     */
    private fun <T> aplicarSiSigue(epoca: Long, sigue: () -> Boolean, aplicar: () -> T): T? =
        discovery.sinCambiarDeArbitro { mesas.aplicarSiVigente(epoca, sigue, aplicar) }

    /**
     * ¿[arbitro] sigue siendo el elegido, sin otra elección entre medias? La identidad sola no basta: tras X → Y → X vuelve a ser X
     * (Codex código r2 H2); [cambios] es `cambiosDeArbitro` capturado con la época. La época la revisa quien llama.
     */
    private fun sigueElegido(arbitro: LanPeer, cambios: Long) =
        discovery.cambiosDeArbitro == cambios && ArbiterElection.pick(discovery.peers.value)?.deviceId == arbitro.deviceId

    // ── Modo compatible (spec §3.5): con un árbitro VIEJO (sin `pm=1`) la declaración se traduce a permisos viejos ──────────────
    /**
     * Su único estado: mesa → época de cada permiso concedido, válido sólo con [arbitroDeRetenidas] en [epocaDeRetenidas] (también el
     * árbitro: una vuelta puede ver al nuevo antes de que el colector de peers suba la época). Lo toca sólo el reconciliador.
     */
    private val retenidas = HashMap<String, Long>()
    private var epocaDeRetenidas = -1L
    private var arbitroDeRetenidas: String? = null
    private var ultimaRenovacionMs = 0L

    /**
     * Una vuelta con un árbitro viejo: `release` de lo que ya no se desea, `renew` cada 10 s y `acquire` de lo deseado sin permiso, en
     * ese orden y un mensaje a la vez. Después de CADA espera de red se revisa que la época y el árbitro sigan siendo los capturados
     * (Codex plan r1 #2); si no, la vuelta se abandona sin aplicar esa respuesta (0). Un `denied` sólo vale en ESTA vuelta (r1 #3).
     */
    private suspend fun vueltaCompat(arbitro: LanPeer, epoca: Long, cambios: Long, d: MesasDeEstaCaja.Declaracion): Long {
        if (epoca != epocaDeRetenidas || arbitro.deviceId != arbitroDeRetenidas) {
            retenidas.clear() // lo concedido por el árbitro anterior vence solo a los 30 s
            epocaDeRetenidas = epoca
            arbitroDeRetenidas = arbitro.deviceId
        }
        fun sigue() = mesas.epoca() == epoca && sigueElegido(arbitro, cambios)
        val deseadas = d.mesas + d.aperturas.map { it.tableId }
        val ocupadas = HashMap<String, String>() // mesa → quién la tiene, sólo en esta vuelta

        // Lo que ya no se desea sale de `retenidas` sin importar la respuesta: sin ella, el permiso vence a los 30 s.
        for ((mesa, ep) in retenidas.filterKeys { it !in deseadas }) {
            client.release(arbitro, mesa, deviceId, ep)
            if (!sigue()) return 0
            retenidas.remove(mesa)
        }
        val ahora = relojMs()
        if (retenidas.isEmpty()) {
            ultimaRenovacionMs = ahora // nada que renovar: el reloj de renovación empieza con el primer permiso
        } else if (ahora - ultimaRenovacionMs >= LATIDO_MS) {
            for ((mesa, ep) in retenidas.toList()) {
                val r = client.renew(arbitro, mesa, deviceId, ep)
                if (!sigue()) return 0
                when (r?.status) {
                    LeaseProtocol.STATUS_GRANTED -> r.lease?.let { retenidas[mesa] = it.epoch }
                    LeaseProtocol.STATUS_STALE -> retenidas.remove(mesa) // el árbitro la olvidó (se reinició): el acquire de abajo, ya
                    LeaseProtocol.STATUS_DENIED -> { retenidas.remove(mesa); ocupadas[mesa] = r.holder?.holderName ?: "otro mesero" }
                }
            }
            ultimaRenovacionMs = ahora
        }
        for (mesa in deseadas - retenidas.keys - ocupadas.keys) {
            val a = d.aperturas.firstOrNull { it.tableId == mesa } // una mesa en la que sólo estoy va sin staffId
            val r = client.acquire(arbitro, mesa, deviceId, a?.staffId.orEmpty(), a?.staffName ?: nombre())
            if (!sigue()) return 0
            when (r?.status) {
                LeaseProtocol.STATUS_GRANTED -> r.lease?.let { retenidas[mesa] = it.epoch }
                LeaseProtocol.STATUS_DENIED -> ocupadas[mesa] = r.holder?.holderName ?: "otro mesero"
            } // null o `error` (p. ej. un árbitro nuevo que me ve «en presencia»): pendiente; a los 15 s, NoHub
        }

        // Revisar y aplicar, UNA operación (Codex código r1 #3): sin esto, una época nueva podía caer entre el último `sigue()` y aquí.
        return aplicarSiSigue(epoca, { sigueElegido(arbitro, cambios) }) {
            trasValidar()
            d.aperturas.forEach { a ->
                if (a.tableId in retenidas) mesas.resolver(a.id, LeaseOutcome.Granted)
                ocupadas[a.tableId]?.let { mesas.resolver(a.id, LeaseOutcome.Taken(it)) }
            }
            _myTables.value = d.mesas + d.aperturas.filter { it.tableId in retenidas }.map { it.tableId }
            if (mesas.hayPendientes(venueId)) REINTENTO_MS
            else (ultimaRenovacionMs + LATIDO_MS - relojMs()).coerceIn(1, LATIDO_MS) // la siguiente renovación, a tiempo
        } ?: 0
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

        /** El mismo tope de un id que `Presencias` (MAX_ID). */
        const val MAX_MESA = 64
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
