package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.kds.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import androidx.room.withTransaction

/** Shared by kitchen and floor. The server owns the baseline; Room owns unacknowledged local actions. */
@Singleton
class KitchenPreparationRepository @Inject constructor(
    private val storage: SecureStorage, private val client: OkHttpClient,
    private val cache: CachedPayloadDao, private val intents: SyncIntentDao,
    private val outbox: SyncOutbox, private val roles: RoleManager,
    private val draftStore: com.avoqado.pos.tables.data.TableRoundDraftStore? = null,
    private val delivery: PreparationDeliveryService? = null,
    private val database: com.avoqado.pos.core.data.local.database.AvoqadoDatabase? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _capabilities = MutableStateFlow(PreparationCapabilities())
    val capabilities = _capabilities.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private var venue: String? = null
    private var capabilityGeneration = 0L
    private suspend fun <T> atomic(block: suspend () -> T): T =
        if (database == null) block() else database.withTransaction { block() }

    private fun rowKey(venueId: String, line: PreparationLine) = "preparation:$venueId:${line.stableKey}"
    private fun cached(venueId: String, value: PreparationLocalLine) = CachedPayloadEntity(
        rowKey(venueId, value.base), venueId, json.encodeToString(PreparationLocalLine.serializer(), value), System.currentTimeMillis())
    private suspend fun get(path: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val token = storage.accessToken ?: error("Sin sesión")
        client.newCall(Request.Builder().url("${ApiConstants.BASE_URL}/mobile/$path")
            .header("Authorization", "Bearer $token").build()).execute().use { it.code to it.body?.string().orEmpty() }
    }

    /** Restore confirmed capability without putting a network request ahead of local work. */
    suspend fun restoreCapabilities(venueId: String) = mutex.withLock {
        if (storage.venueId != venueId) return@withLock
        if (venue != venueId) {
            venue = venueId
            _capabilities.value = PreparationCapabilities()
            _notice.value = null
            cache.get("preparation_capabilities:$venueId")?.let {
                if (storage.venueId == venueId && it.venueId == venueId) {
                    _capabilities.value = runCatching { json.decodeFromString<PreparationCapabilities>(it.json) }
                        .getOrDefault(PreparationCapabilities())
                }
            }
        }
    }

    suspend fun refreshCapabilities(venueId: String) {
        restoreCapabilities(venueId)
        if (storage.venueId != venueId) return
        val epoch = mutex.withLock { ++capabilityGeneration }
        try {
            val (code, body) = get("venues/$venueId/kds/capabilities")
            mutex.withLock {
                if (storage.venueId != venueId || venue != venueId || epoch != capabilityGeneration) return@withLock
                if (code == 401) {
                    _notice.value = "Tu sesión necesita validarse para sincronizar. Lo guardado se conserva."
                    return@withLock
                }
                if (code == 404 || code == 403) {
                    _capabilities.value = PreparationCapabilities()
                    _notice.value = if (code == 404) "El servidor necesita actualizarse para usar preparación por producto."
                        else "La preparación por producto requiere Pro y acceso a las comandas."
                } else {
                    check(code in 200..299) { "No se pudo verificar preparación" }
                    val capability = json.decodeFromString<PreparationCapabilitiesResponse>(body).data
                    _capabilities.value = if (capability.version == 1) capability else PreparationCapabilities()
                    _notice.value = if (!capability.enabled) "La preparación por producto requiere Pro." else null
                }
                cache.upsert(CachedPayloadEntity("preparation_capabilities:$venueId", venueId,
                    json.encodeToString(PreparationCapabilities.serializer(), _capabilities.value), System.currentTimeMillis()))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutex.withLock {
            if (storage.venueId == venueId && venue == venueId && epoch == capabilityGeneration)
                _notice.value = "Sin conexión. Se usa la capacidad confirmada en este aparato."
        } }
        if (storage.venueId == venueId) delivery?.wake(venueId)
    }

    fun can(action: PreparationAction) = (!action.priority || (_capabilities.value.enabled && _capabilities.value.version == 1 && _capabilities.value.urgencyVersion == 1)) &&
        action.permissions.all { roles.hasVenuePermission(it) }
    fun negotiated(venueId: String) = venue == venueId && _capabilities.value.version == 1 && _capabilities.value.enabled

    private data class RoundProof(val localOrderId: String? = null, val reason: String? = null)
    private fun roundProof(venueId: String, orderId: String, record: SyncIntentEntity?, acceptedInCheck: Boolean = false): RoundProof {
        if (record == null) return RoundProof(reason = if (acceptedInCheck) null else "Ronda guardada. Confirma su envío antes de cambiar la preparación.")
        if (record.venueId != venueId || record.type != SyncIntentTypes.ADD_ITEMS) return RoundProof(reason = "Esta ronda no pertenece a la cuenta. Actualiza antes de continuar.")
        val payload = runCatching { json.parseToJsonElement(record.payloadJson).jsonObject }.getOrNull()
            ?: return RoundProof(reason = "La ronda guardada necesita revisarse. No se borró.")
        val local = runCatching { payload["localOrderId"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val order = runCatching { payload["orderId"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val refs = listOfNotNull(local, order)
        val bound = refs.isNotEmpty() && refs.all { ref ->
            ref.isNotBlank() && (ref == orderId || draftStore?.loadResolved(venueId, ref)?.first == orderId)
        }
        if (!bound) return RoundProof(reason = "Esta ronda no pertenece a la cuenta. Actualiza antes de continuar.")
        if (record.status == SyncIntentEntity.STATUS_REJECTED) return RoundProof(local,
            "La ronda no fue aceptada: " + (record.message ?: "revisa la cola de sincronización") + ". No se puede cambiar su preparación.")
        if (record.status !in setOf(SyncIntentEntity.STATUS_PENDING, SyncIntentEntity.STATUS_HELD, SyncIntentEntity.STATUS_ACKED))
            return RoundProof(local, "La ronda guardada necesita revisarse. No se borró.")
        return RoundProof(local)
    }
    private suspend fun validateDraftOrigins(venueId: String, selected: List<PreparationLine>) {
        check(selected.all { it.unavailableReason == null }) { selected.firstNotNullOfOrNull { it.unavailableReason }.orEmpty() }
        val tracked = selected.filter { it.draftRoundKey != null }
        if (tracked.isEmpty()) return
        val groups = tracked.groupBy { it.draftRoundKey!! }
        val records = intents.preparationStatuses(venueId, groups.keys.toList()).associateBy { it.id }
        withContext(Dispatchers.IO) {
            val orderId = tracked.first().orderId ?: error("Falta la cuenta de la ronda")
            val acceptedIds = draftStore?.load(venueId, orderId)?.cachedCheck?.items?.mapNotNull { it.externalId }?.toSet().orEmpty()
            for ((key, rows) in groups) {
                val proof = roundProof(venueId, orderId, records[key], rows.all { it.externalId != null && it.externalId in acceptedIds })
                check(proof.reason == null) { proof.reason.orEmpty() }
            }
        }
    }

    suspend fun localPage(venueId: String, orderId: String?, cursor: String? = null, history: Boolean = false): PreparationPage? {
        check(storage.venueId == venueId) { "Cambió la sucursal" }
        if (orderId == null || draftStore == null) return null
        val offset = if (cursor == null) 0 else {
            require(cursor.startsWith("draft:")) { "Actualiza las rondas guardadas antes de cargar más." }
            requireNotNull(cursor.removePrefix("draft:").toIntOrNull()?.takeIf { it >= 0 }) { "Actualiza las rondas guardadas antes de cargar más." }
        }
        val (canonical, snapshot) = withContext(Dispatchers.IO) { draftStore.loadResolved(venueId, orderId) }
        val acceptedIds = snapshot.cachedCheck?.items?.mapNotNull { it.externalId }?.toSet().orEmpty()
        val acceptedRounds = mutableSetOf<String>()
        val (visible, total) = withContext(Dispatchers.IO) {
            var count = 0
            val rows = mutableListOf<PreparationLine>()
            for ((key, lines) in snapshot.rounds.entries.sortedWith(compareBy({ snapshot.createdAtLocal[it.key] ?: 0L }, { it.key }))) {
                val stamped = com.avoqado.pos.tables.data.stampTableRoundLines(lines, key)
                if (stamped.isNotEmpty() && stamped.all { it.externalId in acceptedIds }) acceptedRounds.add(key)
                val config = snapshot.preparationRouting[key]
                val seeded = com.avoqado.pos.tables.data.seedTableRoundPreparation(lines, key, canonical,
                    snapshot.cachedCheck?.orderNumber ?: "Pedido local", config ?: com.avoqado.pos.printing.routing.PrintConfig())
                for (row in seeded) {
                    if (count >= offset && rows.size < 50) rows.add(row.copy(draftRoundKey = key,
                        unavailableReason = "Actualiza la cuenta para confirmar su estación de preparación.".takeIf { config == null }))
                    count++
                }
            }
            rows.toList() to count
        }
        require(offset <= total) { "Las rondas guardadas cambiaron. Actualiza antes de cargar más." }
        check(storage.venueId == venueId) { "Cambió la sucursal" }
        val keys = visible.mapNotNull { it.draftRoundKey }.distinct()
        val records = if (keys.isEmpty()) emptyMap() else intents.preparationStatuses(venueId, keys).associateBy { it.id }
        val approved = withContext(Dispatchers.IO) {
            val proofs = keys.associateWith { roundProof(venueId, canonical, records[it], it in acceptedRounds) }
            visible.map { row -> row.copy(localOrderId = proofs[row.draftRoundKey]?.localOrderId,
                unavailableReason = proofs[row.draftRoundKey]?.reason ?: row.unavailableReason) }
        }
        check(storage.venueId == venueId) { "Cambió la sucursal" }
        val rows = merge(venueId, approved, acceptBaseline = false)
        val next = offset + rows.size
        return PreparationPage(1, rows, total, next < total, "draft:$next".takeIf { next < total }, 50)
    }

    private fun pageKey(venueId: String, orderId: String?, cursor: String?, history: Boolean) =
        "preparation_page:$venueId:${orderId.orEmpty()}:${cursor.orEmpty()}" + if (history) ":history" else ""

    private fun validatePage(page: PreparationPage, orderId: String?) {
        require(page.version == 1 && page.limit in 1..50 && page.items.size <= page.limit && page.total >= page.items.size &&
            (!page.hasMore || !page.nextCursor.isNullOrBlank()) && page.items.map { it.stableKey }.distinct().size == page.items.size &&
            page.items.all { it.preparation.validFor(it.quantity) && (orderId == null || it.orderId == orderId) }) {
            "Los productos guardados necesitan actualizarse. No se borraron."
        }
    }

    suspend fun savedPage(venueId: String, orderId: String? = null, cursor: String? = null, history: Boolean = false): PreparationPage? {
        check(storage.venueId == venueId) { "Cambió la sucursal" }
        val saved = if (cursor?.startsWith("saved:") == true) null else cache.get(pageKey(venueId, orderId, cursor, history))
        if (saved == null) {
            if (orderId == null || history) return null
            val prefix = "preparation:$venueId:"
            val after = cursor?.takeIf { it.startsWith("saved:") }?.removePrefix("saved:").orEmpty()
            require(after.isEmpty() || after.startsWith(prefix)) { "Actualiza los productos guardados antes de cargar más." }
            val token = "\"orderId\":" + JsonPrimitive(orderId).toString()
            val total = cache.preparationLineCount(venueId, prefix, token)
            if (total == 0) return null
            val records = cache.preparationLinePage(venueId, prefix, token, after)
            val rows = records.take(50).map { record ->
                val line = json.decodeFromString<PreparationLocalLine>(record.json).base
                check(record.venueId == venueId && record.cacheKey == rowKey(venueId, line) && line.draftRoundKey == null) { "Revisa los productos guardados. No se borraron." }
                line
            }
            val page = PreparationPage(1, rows, total, records.size > 50,
                records.take(50).lastOrNull()?.cacheKey?.let { "saved:$it" }.takeIf { records.size > 50 }, cachedOnly = true)
            validatePage(page, orderId)
            val projected = merge(venueId, rows, acceptBaseline = false)
            check(storage.venueId == venueId) { "Cambió la sucursal" }
            return page.copy(items = projected)
        }
        check(storage.venueId == venueId && saved.venueId == venueId) { "Cambió la sucursal" }
        val page = json.decodeFromString<PreparationPageResponse>(saved.json).data
        validatePage(page, orderId)
        val rows = merge(venueId, page.items, acceptBaseline = false)
        check(storage.venueId == venueId) { "Cambió la sucursal" }
        return page.copy(items = rows)
    }

    /** One page per user gesture; a total and Load more preserve the whole list. */
    suspend fun page(venueId: String, orderId: String? = null, cursor: String? = null, history: Boolean = false): PreparationPage {
        val serverCursor = cursor?.takeUnless { it.startsWith("saved:") }
        val suffix = buildList {
            add("limit=50")
            if (history) add("history=true")
            orderId?.let { add("orderId=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            serverCursor?.let { add("cursor=${java.net.URLEncoder.encode(it, "UTF-8")}") }
        }.joinToString("&")
        val cacheKey = pageKey(venueId, orderId, serverCursor, history)
        try {
            val (code, body) = get("venues/$venueId/kds/preparation?$suffix")
            if (code !in 200..299) throw PreparationRequestException(code, when (code) {
                401 -> "Tu sesión necesita validarse para sincronizar. Lo guardado se conserva."
                403 -> "La preparación por producto requiere Pro y acceso a las comandas."
                else -> "No se pudieron cargar los productos"
            })
            check(storage.venueId == venueId) { "Cambió la sucursal" }
            val page = json.decodeFromString<PreparationPageResponse>(body).data
            validatePage(page, orderId)
            _notice.value = null
            val projected = merge(venueId, page.items)
            cache.upsert(CachedPayloadEntity(cacheKey, venueId, body, System.currentTimeMillis()))
            return page.copy(items = projected)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is PreparationRequestException && e.status < 500) throw e
            if (e !is java.io.IOException && e !is PreparationRequestException) throw e
            check(storage.venueId == venueId) { "Cambió la sucursal" }
            val saved = savedPage(venueId, orderId, cursor, history) ?: throw e
            _notice.value = "Sin conexión. Se muestran los últimos productos guardados y las acciones pendientes."
            return saved
        }
    }

    suspend fun merge(venueId: String, rows: List<PreparationLine>, acceptBaseline: Boolean = true): List<PreparationLine> = mutex.withLock {
        atomic {
        require(rows.size <= 100)
        if (rows.isEmpty()) return@atomic emptyList()
        val stored = cache.preparationSnapshots(venueId, rows.map { rowKey(venueId, it) }).associateBy { it.cacheKey }
        val local = rows.map { row ->
            val previous = stored[rowKey(venueId, row)]?.let { runCatching { json.decodeFromString<PreparationLocalLine>(it.json) }.getOrNull() }
            var baseline = if (previous == null || acceptBaseline && row.preparationRevision >= previous.base.preparationRevision) row else previous.base
            if (!acceptBaseline && baseline.id.startsWith("local:") && row.id.startsWith("local:"))
                baseline = baseline.copy(orderId = row.orderId, localOrderId = row.localOrderId,
                    unavailableReason = row.unavailableReason, draftRoundKey = row.draftRoundKey)
            PreparationLocalLine(baseline, previous?.effects.orEmpty().filter { it.expectedRevision >= baseline.preparationRevision })
        }
        val ids = local.flatMap { it.effects.map(PreparationEffect::intentId) }.distinct()
        val statuses = ids.chunked(200).flatMap { intents.preparationStatuses(venueId, it) }.associateBy { it.id }
        val rejected = statuses.values.filter { it.status == SyncIntentEntity.STATUS_REJECTED }.map { it.id }.toSet()
        if (rejected.isNotEmpty()) _notice.value = "Una acción no fue aceptada: ${statuses[rejected.first()]?.message ?: "otro compañero cambió el producto"}. Revisa su estado antes de repetirla."
        val corrected = local.map { saved -> saved.copy(effects = saved.effects.filterNot { it.intentId in rejected }) }
        cache.upsertAll(corrected.map { cached(venueId, it) })
        corrected.map { it.projected() }
        }
    }

    suspend fun pendingKeys(venueId: String, lines: List<PreparationLine>): Set<String> = lines.chunked(100).flatMap { rows ->
        cache.preparationSnapshots(venueId, rows.map { rowKey(venueId, it) }).mapNotNull { record ->
            runCatching { json.decodeFromString<PreparationLocalLine>(record.json) }.getOrNull()
                ?.takeIf { it.effects.isNotEmpty() }?.base?.stableKey
        }
    }.toSet()

    suspend fun pending(venueId: String, line: PreparationLine): Boolean {
        val saved = cache.get(rowKey(venueId, line)) ?: return false
        return runCatching { json.decodeFromString<PreparationLocalLine>(saved.json).effects.isNotEmpty() }.getOrDefault(false)
    }

    /** Always enqueue, even online: same permission, CAS and replay contract in both modes. */
    suspend fun act(venueId: String, selected: List<PreparationLine>, action: PreparationAction,
        quantity: Int, from: PreparationState? = null, reason: String? = null): List<PreparationLine> {
        val actor = storage.userId?.takeIf { it.isNotBlank() } ?: error("Inicia sesión antes de cambiar la preparación.")
        fun checkOrigin() {
            check(storage.userId == actor && storage.venueId == venueId) { "Cambió la sesión o la sucursal. Revisa el producto antes de continuar." }
            check(negotiated(venueId)) { "Conecta para confirmar que esta sucursal admite preparación por producto." }
            check(!action.priority || _capabilities.value.urgencyVersion == 1) { "Conecta y actualiza para confirmar que esta sucursal admite urgencias." }
            check(can(action)) { "Tu rol no permite ${action.label.lowercase()}" }
        }
        checkOrigin()
        val updated = mutex.withLock {
          atomic {
            checkOrigin()
            require(selected.size in 1..100 && selected.map { it.stableKey }.distinct().size == selected.size)
            val orders = selected.map { it.orderId }.distinct()
            require(orders.size == 1) { "Selecciona productos de una misma cuenta" }
            val localOrderRefs = selected.mapNotNull { it.localOrderId }.distinct()
            require(localOrderRefs.size <= 1) { "Selecciona productos de una misma cuenta" }
            if (!action.priority && action != PreparationAction.START && action != PreparationAction.READY) {
                val groups = selected.groupBy { it.orderItemId ?: it.externalId ?: it.id }
                check(groups.values.all { rows -> rows.size == rows.first().stationCount }) { "Carga todas las estaciones del producto antes de continuar" }
            }
            validateDraftOrigins(venueId, selected)
            checkOrigin()
            val id = UUID.randomUUID().toString()
            val snapshots = selected.map { row ->
                val saved = cache.get(rowKey(venueId, row))?.let { json.decodeFromString<PreparationLocalLine>(it.json) }
                    ?: PreparationLocalLine(row)
                check(saved.effects.size < 20) { "Hay 20 acciones de este producto por sincronizar. Conecta antes de continuar." }
                val projected = saved.projected()
                check(projected.preparationRevision == row.preparationRevision) { "Este producto cambió. Actualiza su estado." }
                row.preparation.move(action, quantity, from, reason, id)
                saved.copy(effects = saved.effects + PreparationEffect(id, row.preparationRevision, action, quantity, from, reason))
            }
            val payload = buildJsonObject {
                orders.single()?.let { put("orderId", it) }
                localOrderRefs.singleOrNull()?.let { put("localOrderId", it) }
                selected.first().sourceKey?.let { put("sourceKey", it) }
                put("action", action.name)
                reason?.let { put("reason", it) }
                putJsonArray("items") { selected.forEach { row -> add(buildJsonObject {
                    if (row.id.startsWith("local:")) {
                        put("externalId", row.externalId ?: error("Falta el identificador del producto"))
                        put("sourceKey", row.sourceKey ?: error("Falta el folio"))
                        put("stationId", row.stationId?.let(::JsonPrimitive) ?: JsonNull)
                    } else put("id", row.id)
                    put("expectedRevision", row.preparationRevision)
                    put("quantity", quantity)
                    from?.let { put("from", it.name) }
                }) } }
            }
            validateDraftOrigins(venueId, selected)
            checkOrigin()
            val frozen = selected.first().draftRoundKey?.let { round ->
                val order = selected.first().orderId ?: return@let null
                draftStore?.load(venueId, order)?.preparationRouting?.get(round)
            }
            val job = delivery?.makeJob(venueId, actor, id, selected, action, quantity, from, reason, frozen)
            checkOrigin()
            outbox.enqueue(venueId, SyncIntentTypes.KDS_ITEM_PROGRESS, payload, id = id,
                preparationSnapshots = snapshots.map { cached(venueId, it) } + listOfNotNull(job), expectedActorStaffId = actor)
            snapshots.map { it.projected() }
          }
        }
        delivery?.wake(venueId)
        return updated
    }
}

private class PreparationRequestException(val status: Int, message: String) : RuntimeException(message)
