package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.lan.ClienteDeComandas
import com.avoqado.pos.core.data.lan.TransporteLan
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.kds.domain.*
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.routing.KitchenDeliveryPolicy
import com.avoqado.pos.printing.routing.PrintConfigRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Separate from financial replay: durable peer receipts and selected release paper share the originating intent. */
@Singleton
class PreparationDeliveryService @Inject constructor(
    private val database: AvoqadoDatabase, private val storage: SecureStorage,
    private val transport: TransporteLan, private val printer: ReintentoDeComanda,
    private val configRepository: PrintConfigRepository,
    private val client: ClienteDeComandas,
    private val roles: com.avoqado.pos.core.domain.RoleManager,
    /**
     * D16 de Flautas: qué estaciones tienen pantalla de cocina HOY según el servidor (D6) y si la caja está conectada (la misma
     * bandera con la que la cola decide si manda). Hilt y el inyector de escritorio SIEMPRE las inyectan; sin ellas (pruebas
     * viejas) no se espera el «sí» del servidor ni se calla nada: el lado seguro.
     */
    private val pantallas: EstadoDePantallasDeCocina? = null,
    private val conexion: com.avoqado.pos.core.util.ConnectivityMonitor? = null,
    /** D16: su ACK/REJECT despierta a la liberación que lo espera (opción B′: sin temporizador propio). */
    private val outbox: com.avoqado.pos.core.data.sync.SyncOutbox? = null,
) {
    private val json = PreparationPeerProtocol.json
    private val cache get() = database.cachedPayloadDao()
    private val persistence = PreparationPeerPersistence(database)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal fun useScopeForTest(testScope: CoroutineScope) { check(clock == null); scope = testScope }
    private val mutex = Mutex()
    private var clock: Job? = null
    @Volatile private var venue: String? = null
    private var cursor = ""
    private var receiverVenue: String? = null
    private var receiverUrgencyVersion = 0
    @Volatile private var wakeRequested = false
    /**
     * D16: las liberaciones que esperan el «sí» del servidor (id del intent → [nanoTime] de cuando esta app la vio esperar).
     * Cada pasada las revisa PRIMERO, por su llave (Codex r1 #2): el cursor de 50 no las deja atrás. Las despierta el ACK o el
     * REJECT de su intent; sin él, el reloj de 30 s las decide al vencer el tope. Sin temporizador propio (Codex r2 #3 y #4).
     */
    private val enEspera = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Reloj monotónico del tope; las pruebas lo adelantan. */
    internal var nanoTime: () -> Long = System::nanoTime
    /** Para las pruebas: ¿la tarea está inscrita esperando el «sí»? */
    internal fun estaEnEspera(intentId: String) = enEspera.containsKey(intentId)
    private data class Display(val venueId: String?, val keys: Set<String>)
    @Volatile private var display = Display(null, emptySet())
    fun setDisplayedKeys(venueId: String?, keys: Set<String>) { display = Display(venueId, keys.toSet()) }
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val changes = _changes.asSharedFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    /**
     * D16 (prueba del conjunto, 20-nov): cada aviso de una tarea o de un avance recibido sale UNA vez por sesión del servicio
     * (se olvida en `stop()`: al volver a abrir la app sale otra vez una vez) y, si nadie lo cambió, se quita solo a los
     * [VIGENCIA_DEL_AVISO_MS]. Antes cada pasada lo volvía a publicar y la hoja de OTRA mesa lo enseñaba minutos después.
     */
    private val avisados = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var publicado: Pair<String, Long>? = null
    private fun avisar(llave: String, texto: String) {
        if (!avisados.add("$llave|$texto")) return
        _notice.value = texto
        publicado = texto to nanoTime()
    }

    @Synchronized fun start(venueId: String) {
        if (storage.venueId != venueId || storage.userId.isNullOrBlank()) return
        if (venue != venueId) { stop(); venue = venueId; cursor = "" }
        if (clock?.isActive == true) return
        clock = scope.launch {
            // D16: hijo del reloj, así `stop()` lo apaga con él (nada de la sucursal anterior despierta a la nueva). Se suscribe
            // ANTES de la primera pasada (UNDISPATCHED): un ACK de esa pasada no se pierde.
            outbox?.let { cola -> launch(start = CoroutineStart.UNDISPATCHED) { cola.acks.collect { ack ->
                if ((ack.status == SyncIntentEntity.STATUS_ACKED || ack.status == SyncIntentEntity.STATUS_REJECTED) &&
                    enEspera.containsKey(ack.id) && current(venueId)) wake(venueId)
            } } }
            while (isActive) {
                try { pass(venueId) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { _notice.value = "Hay avances de cocina guardados que necesitan revisión." }
                delay(30_000)
            }
        }
    }
    @Synchronized fun stop() {
        clock?.cancel(); clock = null; venue = null; cursor = ""; receiverVenue = null; enEspera.clear(); avisados.clear(); publicado = null
        transport.desactivarPreparacion(); _notice.value = null
        display = Display(null, emptySet())
    }
    fun wake(venueId: String) { start(venueId); wakeRequested = true; scope.launch { pass(venueId) } }
    private fun current(v: String) = venue == v && storage.venueId == v && !storage.userId.isNullOrBlank()

    suspend fun paperPage(v: String, after: String? = null): PreparationPaperPage {
        check(current(v)) { "Cambió la sucursal" }
        val prefix = "preparation:$v:delivery:"
        val total = cache.preparationPaperCount(v, prefix)
        val records = cache.preparationPaperPage(v, prefix, after.orEmpty())
        val visible = records.take(20)
        val issues = visible.map { record ->
            val job = json.decodeFromString<PreparationDeliveryJob>(record.json)
            PreparationPaperIssue(job.intentId, job.orderNumber ?: job.paper?.orderNumber.orEmpty(), job.paperState,
                job.message ?: if (job.paperState == "PRINTING") "Imprimiendo la liberación" else "Liberación guardada para enviar a cocina",
                job.paperState in setOf("FAILED", "UNCERTAIN", "REVIEW") && job.paper != null && job.phase != "REJECTED" &&
                    !(job.paperState == "REVIEW" && job.urgencyNeedsScreen == true))
        }
        return PreparationPaperPage(issues, total, records.size > 20, visible.lastOrNull()?.cacheKey)
    }
    suspend fun resolvePaper(v: String, intentId: String, reprint: Boolean) {
        val actor = storage.userId ?: error("Inicia sesión antes de continuar")
        check(current(v) && PreparationAction.RELEASE.permissions.all { roles.hasVenuePermission(it) }) { "Tu rol no permite resolver la liberación" }
        mutex.lock()
        try {
            check(current(v) && storage.userId == actor && PreparationAction.RELEASE.permissions.all { roles.hasVenuePermission(it) }) { "Cambió la sesión o tu acceso a la liberación" }
            val row = cache.get("preparation:$v:delivery:$intentId") ?: error("Este envío ya cambió. Actualiza la lista.")
            val job = json.decodeFromString<PreparationDeliveryJob>(row.json)
            check(job.venueId == v && job.intentId == intentId && job.deviceId == transport.deviceId)
            check(job.paperState in setOf("FAILED", "UNCERTAIN", "REVIEW")) { "Espera a que termine el envío antes de resolverlo" }
            val origin = database.syncIntentDao().preparationStatuses(v, listOf(intentId)).singleOrNull()
                ?: error("No se pudo confirmar la acción original. Lo guardado se conserva.")
            check(origin.type == "KDS_ITEM_PROGRESS" && origin.staffId == job.staffId) { "El envío no corresponde a la acción original" }
            check(!reprint || job.paper != null && !(job.paperState == "REVIEW" && job.urgencyNeedsScreen == true)) { "Este aviso no necesita otro ticket. Avisa a cocina y confirma." }
            check(!reprint || origin.status != SyncIntentEntity.STATUS_REJECTED && job.phase != "REJECTED") { "Esta acción no fue aceptada. Avisa a cocina." }
            check(current(v) && storage.userId == actor && PreparationAction.RELEASE.permissions.all { roles.hasVenuePermission(it) }) { "Cambió la sesión o tu acceso a la liberación" }
            val resolved = if (reprint) job.copy(paperState = "QUEUED", manualPaper = true, resolvedByStaffId = actor, message = null)
                else job.copy(paperState = "CONFIRMED", resolvedByStaffId = actor, message = null)
            save(row, resolved)
        } finally { mutex.unlock() }
        if (current(v)) wake(v)
    }

    suspend fun makeJob(venueId: String, actor: String, intentId: String, selected: List<PreparationLine>,
        action: PreparationAction, quantity: Int, from: PreparationState?, reason: String?,
        frozen: com.avoqado.pos.printing.routing.PrintConfig? = null): CachedPayloadEntity {
        val config = frozen ?: configRepository.savedConfig(venueId)
        val job = preparationDeliveryJob(venueId, actor, transport.deviceId, intentId, selected, action, quantity, from, reason, config)
        return CachedPayloadEntity("preparation:$venueId:delivery:$intentId", venueId,
            json.encodeToString(PreparationDeliveryJob.serializer(), job), System.currentTimeMillis())
    }

    private suspend fun attach(v: String): Boolean {
        val cap = cache.get("preparation_capabilities:$v")?.takeIf { it.venueId == v }
            ?.let { runCatching { json.decodeFromString<PreparationCapabilities>(it.json) }.getOrNull() }
        if (!current(v) || cap?.version != 1 || !cap.enabled) {
            // D16 (Codex r2 #4): una pasada de una sucursal que ya no es la actual sólo apaga SU receptor, nunca el de la nueva.
            if (receiverVenue != null && (receiverVenue == v || current(v))) { transport.desactivarPreparacion(); receiverVenue = null }
            return false
        }
        if (receiverVenue != v || receiverUrgencyVersion != cap.urgencyVersion) {
            transport.activarPreparacion(receiver = { command ->
                if (!current(v) || command.venueId != v || command.version == 2 && cap.urgencyVersion != 1) false else try {
                    val accepted = persistence.receive(command)
                    if (accepted && command.phase == "REJECTED") {
                        avisar("recibo:${command.deliveryId}", "Una acción de otro aparato no fue aceptada. Revisa el producto con cocina antes de continuar.")
                    }
                    if (accepted) _changes.tryEmit(v)
                    accepted && current(v)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // D16 (conjunto, punto 3): la causa no se veía en ningún lado; queda en el log del aparato.
                    android.util.Log.w("PreparacionPorWifi", "No se aplicó ${command.action} ${command.phase} ${command.deliveryId} " +
                        "de ${command.deviceId}: ${e.message}")
                    avisar("recibo:${command.deliveryId}:${command.phase}", "Un avance de otro aparato no coincide. Actualiza los productos de cocina.")
                    false
                }
            }, display = { command ->
                val shown = display
                if (shown.venueId == command.venueId && command.items.all { it.stableKey in shown.keys })
                    command.items.mapNotNull { it.stationId }.toSet() else emptySet()
            }, urgencyVersion = cap.urgencyVersion)
            receiverVenue = v
            receiverUrgencyVersion = cap.urgencyVersion
        }
        return true
    }

    suspend fun pass(v: String) {
        if (!mutex.tryLock()) return
        try {
            publicado?.let { (texto, desde) ->
                if (_notice.value == texto && (nanoTime() - desde) / 1_000_000 >= VIGENCIA_DEL_AVISO_MS) { _notice.value = null; publicado = null }
            }
            wakeRequested = false
            if (!attach(v)) return
            val prefix = "preparation:$v:delivery:"
            // D16: primero las que esperan el «sí» del servidor, por su llave (una que ya no existe se olvida).
            val waiting = enEspera.keys.toList().mapNotNull { id ->
                cache.get(prefix + id)?.takeIf { it.venueId == v } ?: run { enEspera.remove(id); null }
            }
            val rows = cache.preparationDeliveries(v, prefix, cursor)
            val statuses = database.syncIntentDao().preparationStatuses(v, (waiting + rows).map {
                it.cacheKey.removePrefix(prefix)
            }.distinct()).associateBy { it.id }
            val deadline = System.nanoTime() + 30_000_000_000L
            var processed = 0
            suspend fun one(row: CachedPayloadEntity) {
                try {
                    val job = json.decodeFromString<PreparationDeliveryJob>(row.json)
                    check(job.venueId == v && row.cacheKey == "preparation:$v:delivery:${job.intentId}" && job.deviceId == transport.deviceId)
                    val origin = statuses[job.intentId]
                    // D16: una liberación todavía en cola cuyo intent ya no existe. Sin evidencia de ACK (Codex r3 #1: alguien tocó
                    // «Marcar resuelta» sobre su rechazo) se cancela SIN papel y se avisa; nunca espera ni imprime un «LIBERAR» que
                    // el servidor pudo rechazar. Con evidencia de ACK (una pasada lo vio y luego se limpió el intent), sigue su camino.
                    val orphan = origin == null && job.paperState == "QUEUED" && job.paper != null
                    if (orphan && job.phase != "ACKED" && !job.manualPaper) { cancelWithoutIntent(row, job); return }
                    // Codex r4 #4: ya cancelada así; cada pasada vuelve a decir su aviso (sobrevive a un cierre de la app), como las
                    // tareas sin intent de siempre.
                    if (origin == null && job.paperState == "CANCELLED" && job.message == AVISO_LIBERACION_SIN_CONFIRMAR) {
                        avisar(job.intentId, AVISO_LIBERACION_SIN_CONFIRMAR); return
                    }
                    check(orphan || origin != null && origin.type == "KDS_ITEM_PROGRESS" && origin.staffId == job.staffId) {
                        "No se pudo confirmar la acción original. Lo guardado se conserva."
                    }
                    deliver(row, job, origin)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { avisar(row.cacheKey, e.message ?: "Hay un avance de cocina que necesita revisión.") }
            }
            for (row in waiting) { if (!current(v)) return; one(row) }
            val seen = waiting.map { it.cacheKey }.toSet()
            for (row in rows) {
                if (!current(v)) return
                if (System.nanoTime() >= deadline) break
                cursor = row.cacheKey; processed++
                if (row.cacheKey !in seen) one(row)
            }
            if (processed == rows.size && rows.size < 50) cursor = ""
        } finally {
            mutex.unlock()
            if (wakeRequested) venue?.let { next -> scope.launch { pass(next) } }
        }
    }

    private suspend fun save(row: CachedPayloadEntity, job: PreparationDeliveryJob) {
        val encoded = json.encodeToString(PreparationDeliveryJob.serializer(), job)
        if (encoded == row.json) return
        cache.upsert(row.copy(json = encoded))
        _changes.tryEmit(job.venueId)
    }
    private suspend fun deliver(row: CachedPayloadEntity, original: PreparationDeliveryJob, origin: SyncIntentEntity?) {
        var waitingNow = false
        var job = original.copy(phase = when (origin?.status) {
            SyncIntentEntity.STATUS_ACKED -> "ACKED"
            SyncIntentEntity.STATUS_REJECTED -> "REJECTED"
            // D16: un intent ya confirmado y después limpiado conserva su evidencia de ACK.
            else -> if (origin == null && original.phase == "ACKED") "ACKED" else "ACTION"
        })
        if (job.paperState == "PRINTING") job = job.copy(paperState = "UNCERTAIN",
            message = "La app se cerró durante la impresión. Revisa el ticket antes de repetirlo.")
        if (job.phase == "REJECTED") job = job.copy(
            paperState = if (job.paperState == "QUEUED") "CANCELLED" else job.paperState,
            // Conjunto, punto 1: el texto del servidor ya trae su punto final («…Revisa su estado.»): sin «..».
            message = "La preparación no fue aceptada: ${origin?.message?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: "revisa la cola"}. Avisa a cocina.")
        save(row, job)
        val peers = transport.peers.value.filter { it.preparationVersion == 1 }.sortedBy { it.deviceId }
        val sends = job.commands.flatMap { command -> peers.filter { command.version == 1 || it.urgencyVersion == 1 }.map { command to it } }
        // A pass has a bounded socket budget. Every successful receipt is saved immediately; repeats are idempotent.
        withTimeoutOrNull(8_000) {
            val start = if (sends.isEmpty()) 0 else job.sendCursor.mod(sends.size)
            for (offset in sends.indices) {
                val index = (start + offset).mod(sends.size)
                val (command, peer) = sends[index]
                if (!current(job.venueId)) return@withTimeoutOrNull
                val key = "${peer.deviceId}|${command.deliveryId}"
                job = job.copy(sendCursor = (index + 1).mod(sends.size))
                if (job.receipts[key] == job.phase &&
                    (job.urgencyNeedsScreen != true || command.deliveryId in job.screenReceipts)) continue
                val wire = command.copy(phase = job.phase)
                val ack = client.enviar(peer, json.encodeToString(PreparationPeerProgress.serializer(), wire))
                if (PreparationPeerProtocol.acknowledged(ack, wire)) {
                    job = job.copy(receipts = job.receipts + (key to job.phase),
                        screenReceipts = if (PreparationPeerProtocol.displayed(ack, wire)) job.screenReceipts + command.deliveryId else job.screenReceipts)
                }
                save(row, job)
            }
        }
        if (!current(job.venueId)) return
        if (job.phase != "REJECTED" && job.paperState == "QUEUED" && job.paper != null) {
            // D16 (Codex r3 #3): se inscribe ANTES de releer el estado. Un ACK/REJECT que cae después de la relectura encuentra
            // la tarea inscrita y la despierta; uno que cae antes se ve en la relectura. Si al final no espera, se borra.
            if (origin != null) enEspera.putIfAbsent(job.intentId, nanoTime())
            val latestOrigin = database.syncIntentDao().preparationStatuses(job.venueId, listOf(job.intentId)).singleOrNull()
            // Rechazada a media pasada: la siguiente pasada (ya, no en 30 s) la cancela con su rechazo.
            if (latestOrigin?.status == SyncIntentEntity.STATUS_REJECTED) { wakeRequested = true; return }
            // Codex r4 #1: el intent desapareció a media pasada («Marcar resuelta» sobre su rechazo): igual que la huérfana.
            if (latestOrigin == null && job.phase != "ACKED" && !job.manualPaper) { cancelWithoutIntent(row, job); return }
            val work = job.paper!!
            val acknowledged = job.commands.groupBy { it.items.first().stationId }.filterValues { commands ->
                commands.all { it.deliveryId in job.screenReceipts }
            }.keys.filterNotNull().toSet()
            val decision = KitchenDeliveryPolicy.decidir(work.planes, work.config, acknowledged)
            // D16 de Flautas: lo único que iría a papel es el respaldo de estaciones que el SERVIDOR confirma como pantalla.
            // «Reimprimir» es un pedido explícito de papel: ni espera ni calla.
            // La urgencia con productos activos sigue pidiendo el acuse de la pantalla por WiFi (Codex r1 #4): ni espera ni calla.
            val soloPantallas = !job.manualPaper && job.urgencyNeedsScreen != true && pantallasDelServidor(decision.aImprimir, decision.respaldo)
            val acked = latestOrigin?.status == SyncIntentEntity.STATUS_ACKED || latestOrigin == null && job.phase == "ACKED"
            if (decision.aImprimir.isEmpty()) { job = job.copy(paperState = "DONE"); save(row, job) }
            // D15 r5 #4 (SERVIDOR): dos cajas liberaron lo mismo casi a la vez; el servidor contestó éxito a las dos y marcó con
            // `yaAplicada` lo que ya estaba hecho. Si TODO venía hecho, otra caja ya liberó e imprimió: sin papel y sin aviso.
            // Una parte (mixto) o un servidor viejo sin el campo: como hoy. «Reimprimir» lo pidió una persona: sale igual.
            else if (acked && !job.manualPaper && todoYaAplicado(latestOrigin?.resultJson)) { job = job.copy(paperState = "DONE", message = null); save(row, job) }
            // D16: el papel se decide cuando el servidor ya tiene la liberación (su pantalla la toma de ahí), no a milisegundos
            // del toque. Mientras tanto la tarea sigue QUEUED, sin papel ni aviso. Sin conexión o pasado el tope, como hoy.
            else if (soloPantallas && !acked && origin != null && esperaElSi(job)) waitingNow = true
            else {
                val photos = cache.preparationSnapshots(job.venueId,
                    job.commands.flatMap { it.items }.map { "preparation:${job.venueId}:${it.stableKey}" }).associateBy { it.cacheKey }
                val safe = job.commands.isNotEmpty() && job.commands.all { command -> command.items.all { item ->
                    val local = photos["preparation:${job.venueId}:${item.stableKey}"]?.let {
                        runCatching { json.decodeFromString<PreparationLocalLine>(it.json).projected() }.getOrNull()
                    }
                    local != null && local.preparationRevision == item.expectedRevision + 1 &&
                        local.preparation == item.before.move(command.action, command.quantity, command.from, command.reason, command.intentId)
                } } && System.currentTimeMillis() - job.createdAtMillis in 0..120_000
                if (!safe && !job.manualPaper) { job = job.copy(paperState = "REVIEW", message = "Revisa la liberación antes de imprimir: el producto cambió o el envío quedó pendiente."); save(row, job) }
                else {
                    val pending = work.copy(planes = decision.aImprimir,
                        config = KitchenDeliveryPolicy.conRespaldo(work.config, decision.respaldo))
                    job = job.copy(paper = pending, paperState = "PRINTING"); save(row, job)
                    if (!current(job.venueId)) { save(row, job.copy(paperState = "QUEUED")); return }
                    val result = printer.reintentar(pending, maxIntentos = 1)
                    job = when (result) {
                        EstadoDeComanda.Salio -> job.copy(paperState = "DONE", message = null)
                        // D16 (la regla de D6): el servidor ya la tiene, nada tronó y lo único sin papel son estaciones de
                        // respaldo que el servidor confirma como pantalla ⇒ no hay nada que avisar ni que revisar.
                        is EstadoDeComanda.NoSalio -> if (soloPantallas && acked && result.trabajo == null &&
                            pantallasDelServidor(result.saltados, decision.respaldo)) job.copy(paperState = "DONE", message = null)
                            else job.copy(paper = result.trabajo ?: pending, paperState = "FAILED",
                            message = result.causa ?: "No salió el ticket de liberación. Avisa a cocina.")
                        else -> job.copy(paperState = "UNCERTAIN", message = "Revisa si salió el ticket de liberación.")
                    }
                    save(row, job)
                }
            }
        }
        if (job.urgencyNeedsScreen == true && job.phase != "REJECTED" && job.paperState in setOf("NONE", "DONE", "REVIEW")) {
            val displayed = job.commands.isNotEmpty() && job.commands.all { it.deliveryId in job.screenReceipts }
            job = if (displayed) job.copy(paperState = if (job.paper == null) "NONE" else "DONE", message = null)
                else job.copy(paperState = "REVIEW", message = "Urgencia guardada. Ninguna pantalla compatible confirmó todos los productos; avisa a cocina.")
        }
        val pendingReceipts = job.receipts.values.any { it != job.phase } ||
            sends.any { (command, peer) -> job.receipts["${peer.deviceId}|${command.deliveryId}"] != job.phase }
        job = job.copy(done = job.phase != "ACTION" && !pendingReceipts && job.paperState in setOf("NONE", "DONE", "CANCELLED", "CONFIRMED"))
        save(row, job)
        if (job.message != null) avisar(job.intentId, job.message!!)
        else if (_notice.value == original.message) _notice.value = null
        if (!waitingNow) enEspera.remove(job.intentId)
    }

    /**
     * D15 r5 #4: ¿cada renglón del ACK (`resultJson.items`) trae `yaAplicada: true`? Sin renglones, sin el campo (servidor viejo)
     * o con un JSON que no se entiende: `false` (se imprime como hoy).
     */
    private fun todoYaAplicado(resultJson: String?): Boolean {
        val items = runCatching {
            json.parseToJsonElement(resultJson ?: return false).jsonObject["items"]?.jsonArray
        }.getOrNull() ?: return false
        return items.isNotEmpty() && items.all { (it as? JsonObject)?.get("yaAplicada")?.jsonPrimitive?.booleanOrNull == true }
    }

    /**
     * D16 (Codex r3 #1 y r4): una liberación automática en cola cuyo intent desapareció sin evidencia de ACK (alguien tocó
     * «Marcar resuelta» sobre su rechazo): `CANCELLED`, SIN papel, con el aviso fijo. «Reimprimir» no entra: lo pidió una
     * persona sobre un intent que no estaba rechazado. No queda `done`: cada pasada vuelve a decir el aviso.
     */
    private suspend fun cancelWithoutIntent(row: CachedPayloadEntity, job: PreparationDeliveryJob) {
        save(row, job.copy(paperState = "CANCELLED", message = AVISO_LIBERACION_SIN_CONFIRMAR))
        enEspera.remove(job.intentId)
        avisar(job.intentId, AVISO_LIBERACION_SIN_CONFIRMAR)
    }

    /**
     * D16: TODOS los [planes] son de estaciones de [respaldo] que el SERVIDOR, en su último sondeo, confirma como pantalla de
     * cocina (D6, `EstadoDePantallasDeCocina`). Por id: un plan sin estación nunca. Sin respuesta del servidor, `false`.
     */
    private fun pantallasDelServidor(planes: List<com.avoqado.pos.printing.routing.TicketPlan>, respaldo: List<String>): Boolean {
        val estado = pantallas ?: return false
        return planes.isNotEmpty() && planes.all { plan -> plan.stationId?.let { it in respaldo && estado.esPantallaDelServidor(it) } == true }
    }

    /**
     * D16: se espera el «sí» sólo con la caja conectada y dentro del tope, contado desde el toque con el reloj de pared (sobrevive
     * a reiniciar la app) Y desde que esta app la vio esperar con el monotónico (un reloj que se atrasa no la alarga).
     */
    private fun esperaElSi(job: PreparationDeliveryJob): Boolean {
        if (conexion?.isFullyConnected != true) return false
        val since = enEspera.getOrPut(job.intentId) { nanoTime() }
        return System.currentTimeMillis() - job.createdAtMillis in 0 until TOPE_DE_LA_ESPERA_MS &&
            (nanoTime() - since) / 1_000_000 < TOPE_DE_LA_ESPERA_MS
    }

    companion object {
        /** D16: el presupuesto de la ronda EN LÍNEA (spec 2026-09-27 §5, H2), el mismo que la ruta del dinero. */
        const val TOPE_DE_LA_ESPERA_MS = com.avoqado.pos.tables.presentation.TableOrderViewModel.PRESUPUESTO_RONDA_MS
        /** D16 (Codex r3 #1): la liberación en cola cuyo intent desapareció sin haberse confirmado. Texto fijo. */
        const val AVISO_LIBERACION_SIN_CONFIRMAR = "No se pudo confirmar la liberación: avisa a cocina"
        /** Conjunto, punto 2: cuánto se queda un aviso publicado antes de quitarse solo (si nadie lo cambió). */
        const val VIGENCIA_DEL_AVISO_MS = 60_000L
    }
}
