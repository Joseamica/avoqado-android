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
    private data class Display(val venueId: String?, val keys: Set<String>)
    @Volatile private var display = Display(null, emptySet())
    fun setDisplayedKeys(venueId: String?, keys: Set<String>) { display = Display(venueId, keys.toSet()) }
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val changes = _changes.asSharedFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    @Synchronized fun start(venueId: String) {
        if (storage.venueId != venueId || storage.userId.isNullOrBlank()) return
        if (venue != venueId) { stop(); venue = venueId; cursor = "" }
        if (clock?.isActive == true) return
        clock = scope.launch {
            while (isActive) {
                try { pass(venueId) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { _notice.value = "Hay avances de cocina guardados que necesitan revisión." }
                delay(30_000)
            }
        }
    }
    @Synchronized fun stop() {
        clock?.cancel(); clock = null; venue = null; cursor = ""; receiverVenue = null
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
            if (receiverVenue != null) { transport.desactivarPreparacion(); receiverVenue = null }
            return false
        }
        if (receiverVenue != v || receiverUrgencyVersion != cap.urgencyVersion) {
            transport.activarPreparacion(receiver = { command ->
                if (!current(v) || command.venueId != v || command.version == 2 && cap.urgencyVersion != 1) false else try {
                    val accepted = persistence.receive(command)
                    if (accepted && command.phase == "REJECTED") {
                        _notice.value = "Una acción de otro aparato no fue aceptada. Revisa el producto con cocina antes de continuar."
                    }
                    if (accepted) _changes.tryEmit(v)
                    accepted && current(v)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    _notice.value = "Un avance de otro aparato no coincide. Actualiza los productos de cocina."
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
            wakeRequested = false
            if (!attach(v)) return
            val rows = cache.preparationDeliveries(v, "preparation:$v:delivery:", cursor)
            val statuses = database.syncIntentDao().preparationStatuses(v, rows.map {
                it.cacheKey.removePrefix("preparation:$v:delivery:")
            }).associateBy { it.id }
            val deadline = System.nanoTime() + 30_000_000_000L
            var processed = 0
            for (row in rows) {
                if (!current(v)) return
                if (System.nanoTime() >= deadline) break
                cursor = row.cacheKey; processed++
                try {
                    val job = json.decodeFromString<PreparationDeliveryJob>(row.json)
                    check(job.venueId == v && row.cacheKey == "preparation:$v:delivery:${job.intentId}" && job.deviceId == transport.deviceId)
                    val origin = statuses[job.intentId]
                    check(origin != null && origin.type == "KDS_ITEM_PROGRESS" && origin.staffId == job.staffId) {
                        "No se pudo confirmar la acción original. Lo guardado se conserva."
                    }
                    deliver(row, job, origin)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { _notice.value = e.message ?: "Hay un avance de cocina que necesita revisión." }
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
    private suspend fun deliver(row: CachedPayloadEntity, original: PreparationDeliveryJob, origin: SyncIntentEntity) {
        var job = original.copy(phase = when (origin.status) {
            SyncIntentEntity.STATUS_ACKED -> "ACKED"
            SyncIntentEntity.STATUS_REJECTED -> "REJECTED"
            else -> "ACTION"
        })
        if (job.paperState == "PRINTING") job = job.copy(paperState = "UNCERTAIN",
            message = "La app se cerró durante la impresión. Revisa el ticket antes de repetirlo.")
        if (job.phase == "REJECTED") job = job.copy(
            paperState = if (job.paperState == "QUEUED") "CANCELLED" else job.paperState,
            message = "La preparación no fue aceptada: ${origin.message ?: "revisa la cola"}. Avisa a cocina.")
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
            val latestOrigin = database.syncIntentDao().preparationStatuses(job.venueId, listOf(job.intentId)).singleOrNull()
            if (latestOrigin?.status == SyncIntentEntity.STATUS_REJECTED) return
            val work = job.paper!!
            val acknowledged = job.commands.groupBy { it.items.first().stationId }.filterValues { commands ->
                commands.all { it.deliveryId in job.screenReceipts }
            }.keys.filterNotNull().toSet()
            val decision = KitchenDeliveryPolicy.decidir(work.planes, work.config, acknowledged)
            if (decision.aImprimir.isEmpty()) { job = job.copy(paperState = "DONE"); save(row, job) }
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
                        is EstadoDeComanda.NoSalio -> job.copy(paper = result.trabajo ?: pending, paperState = "FAILED",
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
        if (job.message != null) _notice.value = job.message
        else if (_notice.value == original.message) _notice.value = null
    }
}
