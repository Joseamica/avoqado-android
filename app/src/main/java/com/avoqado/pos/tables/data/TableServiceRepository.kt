package com.avoqado.pos.tables.data

import android.util.Log
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TableServiceRepository"
/** Espejo de `ESTADOS_FUERA_DE_LA_MESA` (avoqado-server `services/shared/cuentaEnLaMesa.ts`): la cuenta ya no vuelve a la mesa. */
private val CUENTA_CERRADA = setOf("COMPLETED", "CANCELLED", "DELETED")

/**
 * TABLE_SERVICE (PRO) — floor-plan state + table lifecycle for the restaurant
 * mode. Thin wrapper over the /mobile table endpoints (which delegate to the
 * same TPV services, so status transitions match the terminal 1:1).
 *
 * Distinct from [TablesRepository] (the lightweight id/number list the
 * reservations table-picker uses) — this one carries live status, canvas
 * position and the open order per table.
 */
@Singleton
class TableServiceRepository @Inject constructor(
    private val apiService: ApiService,
    private val payloadCache: com.avoqado.pos.core.data.local.PayloadCache,
    private val syncOutbox: com.avoqado.pos.core.data.sync.SyncOutbox,
    private val tableSession: TableSession,
    /** Sólo para `tables:pay-any`: quién puede liquidar un cheque ajeno. */
    private val roleManager: com.avoqado.pos.core.domain.RoleManager,
    private val draftStore: TableRoundDraftStore,
) {
    // MARK: - Offline fallback (Corte B2)

    /** Red caída/timeout — nunca un rechazo de negocio del server. */
    private fun isNetworkError(e: Throwable): Boolean = when (e) {
        is java.io.IOException -> true
        is retrofit2.HttpException -> e.code() in 502..504
        else -> false
    }

    /**
     * Offline-first Corte B2: si la acción falló POR RED, se vuelve intent
     * (write-ahead al outbox) y la UI sigue como si hubiera funcionado —
     * offline es estado normal, no error. Los rechazos de NEGOCIO del server
     * (403/409/4xx) siguen propagándose tal cual.
     */
    private suspend fun <T> Result<T>.orQueueOffline(
        venueId: String,
        type: String,
        offlineValue: T,
        payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Result<T> = recoverCatching { e ->
        if (!isNetworkError(e)) throw e
        syncOutbox.enqueue(venueId, type, kotlinx.serialization.json.buildJsonObject(payload))
        Log.w(TAG, "📴 $type sin red — encolado al outbox")
        offlineValue
    }

    /**
     * Referencia a una línea del cheque: id real del server, o el externalId
     * determinista de una línea enviada sin red que aún no sincroniza.
     */
    data class SplitItemRef(val serverId: String?, val externalId: String?)

    /** orderId real, o localOrderId si la sesión activa es provisional. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putOrderRef(orderId: String) {
        val session = tableSession.current()
        if (session?.isProvisional == true && session.orderId == orderId) {
            put("localOrderId", kotlinx.serialization.json.JsonPrimitive(orderId))
        } else {
            put("orderId", kotlinx.serialization.json.JsonPrimitive(orderId))
        }
    }

    /** Blob que espejamos a disco: mesas + regla de propiedad (Corte A offline). */
    @kotlinx.serialization.Serializable
    private data class TablesCacheBlob(
        val tables: List<DiningTable> = emptyList(),
        val enforced: Boolean = false,
        val staffId: String? = null,
        val canManageAll: Boolean = true,
        /** Plano en el POS (2026-10-09): la versión de estas mesas — lo guardado al día no se vuelve a bajar. */
        val tablesVersion: String? = null,
        val floorPlanVersion: String? = null,
    )

    private val floorWrites = Mutex()
    @Volatile private var loadedVenueId: String? = null
    // Un GET /tables a la vez: dos en el aire pueden llegar al revés y la foto vieja pisar a la nueva.
    // ponytail: si un GET se cuelga, los demás esperan su timeout; cola por sucursal si algún día estorba.
    private val refrescos = Mutex()

    private val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val _tables = MutableStateFlow<List<DiningTable>>(emptyList())
    val tables: StateFlow<List<DiningTable>> = _tables.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Propiedad de mesa ("Solo el propietario puede modificar sus mesas").
     * El server manda el switch + si YO puedo saltármelo (tables:manage-all)
     * como siblings del listado de mesas. Default = regla apagada (histórico).
     */
    data class TableOwnership(
        val enforced: Boolean = false,
        val staffId: String? = null,
        val canManageAll: Boolean = true,
        /**
         * `tables:pay-any` — puedo LIQUIDAR el cheque de otro sin poder editarlo.
         *
         * No viene del payload del server (el blob `viewer` sólo trae
         * `canManageAllTables`): sale de la lista efectiva de permisos del login,
         * vía `RoleManager.canSettleAnyTable`. Por eso tampoco se cachea en disco
         * junto al plano — se recalcula, que es gratis y siempre está fresco.
         */
        val canPayAny: Boolean = false,
    ) {
        /** ¿Esta cuenta es intocable para mí? (la regla la refuerza el server; esto pinta la UI) */
        fun isLockedForMe(ownerId: String?): Boolean = enforced && !canManageAll && ownerId != null && ownerId != staffId

        /**
         * ¿No puedo ni COBRARLA?
         *
         * 🔴 Editar y cobrar dejaron de ser la misma pregunta. El server exime la
         * ruta de cobro de la propiedad de mesa con `tables:pay-any`
         * (`PAYMENT_OWNERSHIP_OVERRIDES`), así que el cajero liquida cualquier
         * cheque sin ganar el derecho a tocarlo. Mientras esto colgó de
         * `isLockedForMe`, la app le negaba el cobro que el server ya le concedía
         * — y el gate del cliente era lo único que bloqueaba.
         */
        fun isLockedForPayment(ownerId: String?): Boolean = isLockedForMe(ownerId) && !canPayAny
    }

    private val _ownership = MutableStateFlow(TableOwnership())
    val ownership: StateFlow<TableOwnership> = _ownership.asStateFlow()

    /** Plano en el POS (spec 2026-10-09 §4.1): versión de mesas de la última respuesta buena (o de la guardada). */
    @Volatile
    var tablesVersion: String? = null
        private set

    /**
     * La versión del plano que trajo esa misma respuesta de /tables. Es un flujo porque el armado del plano la observa: tras
     * un fallo parcial, /tables puede volver con la MISMA lista (no re-emite) y sólo cambia esta versión (auditoría Codex del
     * código, hallazgo 1). Se asigna ANTES que las mesas.
     */
    private val _floorPlanVersionFromTables = MutableStateFlow<String?>(null)
    val floorPlanVersionFromTablesFlow: StateFlow<String?> = _floorPlanVersionFromTables.asStateFlow()
    val floorPlanVersionFromTables: String? get() = _floorPlanVersionFromTables.value

    /**
     * Cambiar de sucursal vacía mesas, propiedad y VERSIONES antes de hidratar la nueva: la versión de A nunca se compara
     * con la de B. Siempre dentro de `floorWrites` (el Mutex no es reentrante: no lo tomes aquí).
     */
    private suspend fun mirarA(venueId: String) {
        if (loadedVenueId == venueId) return
        loadedVenueId = venueId
        _tables.value = emptyList()
        _ownership.value = TableOwnership()
        tablesVersion = null
        _floorPlanVersionFromTables.value = null
        hydrateFromCache(venueId)
    }

    private suspend fun cachedFloor(venueId: String): TablesCacheBlob? {
        val cached = payloadCache.load(com.avoqado.pos.core.data.local.PayloadCache.TYPE_TABLES, venueId) ?: return null
        return cacheJson.decodeFromString(TablesCacheBlob.serializer(), cached.json)
    }

    /**
     * La cuenta provisional ya no retiene nada en este aparato: ascendida a la real, sin productos sin mandar, CADA ronda del
     * borrador confirmada por la cola (una ronda sin intent —la app murió antes de encolarla— retiene: sólo se vuelve a
     * encolar al abrir la cuenta) y la cola sin nada de ella. Devuelve las rondas que vio (para `sigueSuelta`) o null si
     * retiene. Las rondas sólo las poda la pantalla de la mesa; antes, la mesa cobrada sin red quedaba «Por sincronizar»
     * hasta abrirla. Si algo no se puede leer, retiene.
     * ponytail: el ACKED se purga a los 7 días (`ACKED_TTL_MS`); una ronda purgada antes de un refresco vuelve a retener, como antes.
     */
    private suspend fun rondasSiYaNoRetiene(venueId: String, checkId: String): Set<String>? = try {
        val (id, snapshot) = draftStore.loadResolved(venueId, checkId)
        snapshot.rounds.keys.takeIf {
            id != checkId && snapshot.pending.isEmpty() &&
                snapshot.rounds.keys.all { syncOutbox.observarEstado(it).first() == com.avoqado.pos.core.data.local.database.SyncIntentEntity.STATUS_ACKED } &&
                syncOutbox.trabajoSinResolverDeLaOrden(venueId, setOf(checkId, id)).intents == 0
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

    /**
     * Sin esperas, justo antes de guardar el plano: el borrador sigue como se revisó y nadie tiene la cuenta abierta en el
     * aparato (sólo la pantalla abierta le agrega productos). Entre esto y guardar no hay más esperas que la propia escritura.
     */
    private fun sigueSuelta(venueId: String, checkId: String, rondas: Set<String>): Boolean = try {
        val (id, snapshot) = draftStore.loadResolved(venueId, checkId)
        snapshot.pending.isEmpty() && snapshot.rounds.keys == rondas &&
            tableSession.current()?.idsDeLaOrden?.let { checkId in it || id in it } != true
    } catch (_: Exception) { false }

    /**
     * El servidor dice que la cuenta real está CERRADA (cobrada, cancelada o borrada; no se reabre). Que falte en /tables no
     * lo prueba: una mesa archivada no viene, ni una cuenta movida a ella (Codex r5 #2, r6). Sin respuesta, retiene.
     */
    private suspend fun cerradaEnServidor(venueId: String, checkId: String): Boolean = try {
        apiService.getOrderDetail(venueId, draftStore.loadResolved(venueId, checkId).first).order?.status in CUENTA_CERRADA
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }

    /** Provisionales que ya no retenían nada ANTES del GET: sólo a éstas las puede soltar su respuesta (una respuesta vieja no). */
    private suspend fun sueltasAntesDelGet(venueId: String): Set<String> =
        (cachedFloor(venueId)?.tables ?: _tables.value).flatMap { it.openOrders }.filter { it.isProvisional }
            .map { it.id }.filterTo(mutableSetOf()) { rondasSiYaNoRetiene(venueId, it) != null }

    /** An early GET cannot erase an OPEN_TABLE or its unsent products. */
    private suspend fun preserveLocalChecks(venueId: String, fresh: List<DiningTable>, cached: List<DiningTable>, sueltas: Set<String>): List<DiningTable> {
        val serverIds = fresh.flatMap { it.openOrders.map { check -> check.id } + listOfNotNull(it.currentOrder?.id) }.toSet()
        // Primero todo lo que espera (la cola); después, sin esperas hasta guardar, la última revisión de cada una.
        val candidatas = cached.flatMap { it.openOrders }.filter { it.isProvisional && it.id in sueltas }
            .mapNotNull { check -> rondasSiYaNoRetiene(venueId, check.id)?.let { check.id to it } }.toMap()
        val locals = cached.mapNotNull { table ->
            val checks = table.openOrders.filter { check ->
                if (!check.isProvisional) false else {
                    draftStore.loadResolved(venueId, check.id).first !in serverIds &&
                        candidatas[check.id]?.let { sigueSuelta(venueId, check.id, it) } != true
                }
            }
            if (checks.isEmpty()) null else table.id to table.copy(openOrders = checks, currentOrder = null)
        }.toMap()
        val merged = fresh.map { table ->
            val local = locals[table.id] ?: return@map table
            table.copy(status = "OCCUPIED", openOrders = table.openOrders + local.openOrders)
        }
        val freshIds = fresh.map { it.id }.toSet()
        return merged + locals.values.filter { it.id !in freshIds }
    }

    suspend fun refresh(venueId: String): Result<List<DiningTable>> {
        // Se anota al pedirlo, sin esperar candados: tras esperar, ya puede estar activa otra sucursal (Codex r5 #1).
        val alPedirlo = loadedVenueId
        return refrescos.withLock { runCatching {
        val sueltas = floorWrites.withLock {
            // Si mientras esperaba su turno el plano cambió a otra sucursal, este refresco ya no aplica: no la vuelve a activar.
            check(loadedVenueId == alPedirlo || loadedVenueId == venueId) { "La sucursal cambió" }
            mirarA(venueId); sueltasAntesDelGet(venueId)
        }
        _isLoading.value = true
        try {
            val response = apiService.getTables(venueId)
            // Fuera del candado del plano: es una consulta de red por cuenta suelta (casi siempre ninguna).
            val cerradas = sueltas.filterTo(mutableSetOf()) { cerradaEnServidor(venueId, it) }
            floorWrites.withLock {
                check(loadedVenueId == venueId) { "La sucursal cambió" }
                val tables = preserveLocalChecks(venueId, response.data, cachedFloor(venueId)?.tables ?: _tables.value, cerradas)
                val ownership = TableOwnership(response.settings?.enforceTableOwnership == true,
                    response.viewer?.staffId, response.viewer?.canManageAllTables ?: true, roleManager.canSettleAnyTable)
                // Con cuentas provisionales la lista no está reconciliada: sin versión, la siguiente vuelta vuelve a pedir
                // /tables, como antes del poller.
                val versionGuardada = if (tables.any { t -> t.openOrders.any { it.isProvisional } }) null else response.tablesVersion
                // Espejo en disco ANTES de pintar, con su versión (plano en el POS, spec 2026-10-09 §4.1): si el proceso
                // muere aquí, lo que el mesero vio ya está guardado y su versión también.
                payloadCache.save(com.avoqado.pos.core.data.local.PayloadCache.TYPE_TABLES, venueId,
                    cacheJson.encodeToString(TablesCacheBlob.serializer(), TablesCacheBlob(tables, ownership.enforced, ownership.staffId,
                        ownership.canManageAll, versionGuardada, response.floorPlanVersion)))
                tablesVersion = versionGuardada
                _floorPlanVersionFromTables.value = response.floorPlanVersion
                _tables.value = tables
                _ownership.value = ownership
                tables
            }
        } finally {
            if (loadedVenueId == venueId) _isLoading.value = false
        }
    }.onFailure {
        Log.e(TAG, "❌ refresh failed: ${it.message}")
        floorWrites.withLock { if (loadedVenueId == venueId) hydrateFromCache(venueId) }
    } }
    }

    /** Persist the check pointer before ordering; status alone cannot reopen a table after restart. */
    suspend fun markTableOccupiedLocally(tableId: String, covers: Int? = null, scopedVenueId: String? = loadedVenueId) = floorWrites.withLock {
        val venueId = checkNotNull(scopedVenueId) { "No hay sucursal seleccionada" }
        check(loadedVenueId == venueId) { "La sucursal cambió" }
        val session = checkNotNull(tableSession.current()) { "No hay cuenta abierta" }
        check(session.tableId == tableId && session.isProvisional)
        check(_tables.value.any { it.id == tableId }) { "No se pudo guardar la mesa en este aparato" }
        val check = OpenCheckSummary(session.orderId, session.orderNumber, covers, version = session.version,
            waiterId = _ownership.value.staffId, isProvisional = true)
        val tables = _tables.value.map { table ->
            if (table.id != tableId) table else table.copy(status = "OCCUPIED", openOrders = table.openOrders.filterNot { it.id == check.id } + check)
        }
        payloadCache.saveDurable(com.avoqado.pos.core.data.local.PayloadCache.TYPE_TABLES, venueId,
            cacheJson.encodeToString(TablesCacheBlob.serializer(), TablesCacheBlob(tables, _ownership.value.enforced, _ownership.value.staffId,
                _ownership.value.canManageAll, tablesVersion, floorPlanVersionFromTables)))
        _tables.value = tables
    }

    private suspend fun hydrateFromCache(venueId: String) {
        if (_tables.value.isNotEmpty()) return
        runCatching {
            val blob = cachedFloor(venueId) ?: return
            tablesVersion = blob.tablesVersion
            _floorPlanVersionFromTables.value = blob.floorPlanVersion
            _tables.value = blob.tables
            _ownership.value = TableOwnership(blob.enforced, blob.staffId, blob.canManageAll, roleManager.canSettleAnyTable)
        }.onFailure { Log.e(TAG, "❌ Cache de mesas corrupto: ${it.message}") }
    }

    /** Plano en el POS: pinta lo guardado al entrar, antes de tocar la red (sólo si no hay mesas en memoria). */
    suspend fun hydrate(venueId: String) = floorWrites.withLock {
        if (loadedVenueId != venueId) mirarA(venueId) else hydrateFromCache(venueId)
    }

    /** Opens a table (reuses its active order or creates an empty one). Returns the order. */
    suspend fun openTable(venueId: String, tableId: String, covers: Int): Result<OpenedOrder> = runCatching {
        val response = apiService.openTable(venueId, tableId, OpenTableRequest(covers))
        response.data?.order ?: error("Respuesta sin orden")
    }.onFailure { Log.e(TAG, "❌ openTable failed: ${it.message}") }

    /** Full check (items + modifiers + send times) for the table order panel. */
    suspend fun getOrderDetail(venueId: String, orderId: String): Result<OrderDetail> = runCatching {
        val response = apiService.getOrderDetail(venueId, orderId)
        response.order ?: error("Respuesta sin orden")
    }.onFailure { Log.e(TAG, "❌ getOrderDetail failed: ${it.message}") }

    /** Appends a round to the open order. 409 = stale version → caller refreshes and retries. */
    suspend fun addRound(
        venueId: String,
        orderId: String,
        items: List<AddOrderItemRequest>,
        version: Int,
    ): Result<UpdatedOrder> = runCatching {
        val response = apiService.addOrderItems(venueId, orderId, AddItemsRequest(items, version))
        response.data ?: error("Respuesta sin orden")
    }.onFailure { Log.e(TAG, "❌ addRound failed: ${it.message}") }

    /** Pays the FULL order in cash (amount/tip in cents) — the server marks it PAID. */
    suspend fun payOrderCash(venueId: String, orderId: String, amountCents: Int, tipCents: Int): Result<Unit> = runCatching {
        apiService.payOrderCash(venueId, orderId, PayCashRequest(amount = amountCents, tip = tipCents))
        Unit
    }.onFailure { Log.e(TAG, "❌ payOrderCash failed: ${it.message}") }

    /** "Mover": moves the OPEN check to another table (source freed, target occupied). */
    suspend fun moveOrder(venueId: String, orderId: String, targetTableId: String): Result<Unit> = runCatching {
        apiService.moveOrder(venueId, orderId, MoveOrderRequest(targetTableId))
        Unit
    }.orQueueOffline(venueId, "MOVE_ORDER", Unit) {
        putOrderRef(orderId)
        put("targetTableId", kotlinx.serialization.json.JsonPrimitive(targetTableId))
    }.onFailure { Log.e(TAG, "❌ moveOrder failed: ${it.message}") }

    /** "Asignar": reassigns the OPEN check to another waiter (tips/corte follow). */
    suspend fun assignOrder(venueId: String, orderId: String, staffId: String): Result<Unit> = runCatching {
        apiService.assignOrder(venueId, orderId, AssignOrderRequest(staffId))
        Unit
    }.orQueueOffline(venueId, "ASSIGN_ORDER", Unit) {
        putOrderRef(orderId)
        put("staffId", kotlinx.serialization.json.JsonPrimitive(staffId))
    }.onFailure { Log.e(TAG, "❌ assignOrder failed: ${it.message}") }

    /** Menús del venue con su horario y cuál aplica ahora. Cache-first al fallar red. */
    suspend fun getMenus(venueId: String): Result<MenusPayload> = runCatching {
        val payload = apiService.getMenus(venueId).data ?: MenusPayload()
        payloadCache.save(
            com.avoqado.pos.core.data.local.PayloadCache.TYPE_MENUS,
            venueId,
            cacheJson.encodeToString(MenusPayload.serializer(), payload),
        )
        payload
    }.recoverCatching { e ->
        // Sin red: los menús cacheados siguen sirviendo (horarios se evalúan local).
        val cached = payloadCache.load(com.avoqado.pos.core.data.local.PayloadCache.TYPE_MENUS, venueId)
            ?: throw e
        Log.w(TAG, "⚠️ getMenus sin red — usando cache (hace ${cached.ageMinutes} min)")
        cacheJson.decodeFromString(MenusPayload.serializer(), cached.json)
    }.onFailure { Log.e(TAG, "❌ getMenus failed: ${it.message}") }

    /** Catálogo de cobros por servicio del venue. Cache-first al fallar red. */
    suspend fun getServiceCharges(venueId: String): Result<List<ServiceChargeOption>> = runCatching {
        val charges = apiService.getServiceCharges(venueId).data
        payloadCache.save(
            "service_charges",
            venueId,
            cacheJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ServiceChargeOption.serializer()),
                charges,
            ),
        )
        charges
    }.recoverCatching { e ->
        val cached = payloadCache.load("service_charges", venueId) ?: throw e
        Log.w(TAG, "⚠️ getServiceCharges sin red — cache (hace ${cached.ageMinutes} min)")
        cacheJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ServiceChargeOption.serializer()),
            cached.json,
        )
    }.onFailure { Log.e(TAG, "❌ getServiceCharges failed: ${it.message}") }

    /** Aplica un cobro por servicio a la cuenta (SUMA al total). */
    suspend fun applyServiceCharge(venueId: String, orderId: String, serviceChargeId: String): Result<Unit> = runCatching {
        apiService.applyServiceCharge(venueId, orderId, ApplyServiceChargeRequest(serviceChargeId))
        Unit
    }.orQueueOffline(venueId, "APPLY_SERVICE_CHARGE", Unit) {
        putOrderRef(orderId)
        put("serviceChargeId", kotlinx.serialization.json.JsonPrimitive(serviceChargeId))
    }.onFailure { Log.e(TAG, "❌ applyServiceCharge failed: ${it.message}") }

    /** Quita un cobro por servicio aplicado. */
    suspend fun removeServiceCharge(venueId: String, orderId: String, orderServiceChargeId: String): Result<Unit> = runCatching {
        apiService.removeServiceCharge(venueId, orderId, orderServiceChargeId)
        Unit
    }.onFailure { Log.e(TAG, "❌ removeServiceCharge failed: ${it.message}") }

    /** Recompensas: saldo de puntos del cliente adjunto al cheque. */
    suspend fun getCustomerLoyalty(venueId: String, customerId: String, orderId: String?): Result<CustomerLoyalty> = runCatching {
        apiService.getCustomerLoyalty(venueId, customerId, orderId).data
            ?: error("Respuesta sin datos de lealtad")
    }.onFailure { Log.e(TAG, "❌ getCustomerLoyalty failed: ${it.message}") }

    /** Canjea puntos como descuento en la cuenta abierta. */
    suspend fun redeemLoyaltyPoints(venueId: String, orderId: String, customerId: String, points: Int): Result<Unit> = runCatching {
        apiService.redeemLoyaltyPoints(venueId, orderId, RedeemPointsRequest(customerId, points))
        Unit
    }.onFailure { Log.e(TAG, "❌ redeemLoyaltyPoints failed: ${it.message}") }

    /**
     * "Fusionar cuentas": vuelca [sourceOrderId] en [orderId] y cierra el origen.
     * Sin red cae al outbox: el reducer resuelve ambos cheques y valida propiedad
     * de mesa en los DOS — absorber el cheque de otro mesero es modificar el suyo.
     */
    suspend fun mergeOrders(venueId: String, orderId: String, sourceOrderId: String): Result<Unit> = runCatching {
        apiService.mergeOrders(venueId, orderId, MergeOrdersRequest(sourceOrderId))
        Unit
    }.orQueueOffline(venueId, "MERGE_ORDERS", Unit) {
        putOrderRef(orderId)
        put("sourceOrderId", kotlinx.serialization.json.JsonPrimitive(sourceOrderId))
    }.onFailure { Log.e(TAG, "❌ mergeOrders failed: ${it.message}") }

    /**
     * "Dividir por puesto": un cheque por asiento (atómico en el server).
     *
     * Sin red cae al outbox como SPLIT_BY_SEAT con un id local POR ASIENTO:
     * crea N cheques de golpe y cada uno puede recibir un cobro después, así que
     * un solo id local no alcanzaría para saber a cuál aterriza cada pago. Los
     * asientos que el server no llegue a crear (el más bajo se queda en la
     * cuenta original) simplemente no se mapean.
     */
    suspend fun splitOrderBySeat(venueId: String, orderId: String, seats: List<Int>): Result<Unit> = runCatching {
        apiService.splitOrderBySeat(venueId, orderId)
        Unit
    }.orQueueOffline(venueId, "SPLIT_BY_SEAT", Unit) {
        putOrderRef(orderId)
        put(
            "seatLocalOrderIds",
            kotlinx.serialization.json.buildJsonObject {
                seats.forEach { seat ->
                    put(seat.toString(), kotlinx.serialization.json.JsonPrimitive(java.util.UUID.randomUUID().toString()))
                }
            },
        )
    }.onFailure { Log.e(TAG, "❌ splitOrderBySeat failed: ${it.message}") }

    /**
     * Separa artículos en una cuenta NUEVA de la misma mesa (Square).
     *
     * Sin red cae al outbox como SPLIT_ORDER. Dos detalles que importan:
     * - `itemRefs` (no `itemIds`): el reducer acepta id de server O externalId,
     *   así que la misma forma sirve para líneas que aún no sincronizan.
     * - `newLocalOrderId`: id local del cheque NUEVO. El reducer lo mapea al id
     *   real al aplicar el split, para que un cobro posterior sobre el cheque
     *   separado aterrice ahí y no en el original (igual que abrir mesa).
     */
    suspend fun splitOrder(venueId: String, orderId: String, refs: List<SplitItemRef>): Result<Unit> {
        suspend fun enqueueSplit() {
            syncOutbox.enqueue(
                venueId,
                "SPLIT_ORDER",
                kotlinx.serialization.json.buildJsonObject {
                    putOrderRef(orderId)
                    put(
                        "itemRefs",
                        kotlinx.serialization.json.buildJsonArray {
                            refs.forEach { ref ->
                                add(
                                    kotlinx.serialization.json.buildJsonObject {
                                        if (ref.serverId != null) {
                                            put("id", kotlinx.serialization.json.JsonPrimitive(ref.serverId))
                                        } else {
                                            put("externalId", kotlinx.serialization.json.JsonPrimitive(ref.externalId.orEmpty()))
                                        }
                                    },
                                )
                            }
                        },
                    )
                    put("newLocalOrderId", kotlinx.serialization.json.JsonPrimitive(java.util.UUID.randomUUID().toString()))
                },
            )
        }

        // Si alguna línea todavía no sincroniza, la ruta online NO puede
        // expresarla (exige itemIds reales): va derecho al outbox en vez de
        // intentar una llamada que sabemos que no puede funcionar.
        val serverIds = refs.mapNotNull { it.serverId }
        if (serverIds.size != refs.size) {
            return runCatching {
                enqueueSplit()
                Log.w(TAG, "📴 SPLIT_ORDER con líneas sin sincronizar — encolado al outbox")
            }
        }

        return runCatching {
            apiService.splitOrder(venueId, orderId, SplitOrderRequest(serverIds))
            Unit
        }.orQueueOffline(venueId, "SPLIT_ORDER", Unit) {
            putOrderRef(orderId)
            put(
                "itemRefs",
                kotlinx.serialization.json.buildJsonArray {
                    serverIds.forEach { id ->
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put("id", kotlinx.serialization.json.JsonPrimitive(id))
                            },
                        )
                    }
                },
            )
            put("newLocalOrderId", kotlinx.serialization.json.JsonPrimitive(java.util.UUID.randomUUID().toString()))
        }.onFailure { Log.e(TAG, "❌ splitOrder failed: ${it.message}") }
    }

    /** Aplica un descuento de catálogo (scope ORDER) a la cuenta abierta. */
    suspend fun applyOrderDiscount(venueId: String, orderId: String, discountId: String): Result<Unit> = runCatching {
        apiService.applyOrderDiscount(venueId, orderId, ApplyOrderDiscountRequest(discountId))
        Unit
    }.orQueueOffline(venueId, "APPLY_DISCOUNT", Unit) {
        putOrderRef(orderId)
        put("discountId", kotlinx.serialization.json.JsonPrimitive(discountId))
    }.onFailure { Log.e(TAG, "❌ applyOrderDiscount failed: ${it.message}") }

    /** Quita un descuento aplicado de la cuenta. */
    suspend fun removeOrderDiscount(venueId: String, orderId: String, orderDiscountId: String): Result<Unit> = runCatching {
        apiService.removeOrderDiscount(venueId, orderId, orderDiscountId)
        Unit
    }.onFailure { Log.e(TAG, "❌ removeOrderDiscount failed: ${it.message}") }

    /** "Cortesía en la cuenta": comps EVERY line of the open order with one reason. */
    suspend fun compWholeOrder(venueId: String, orderId: String, reason: String): Result<Unit> = runCatching {
        apiService.compWholeOrder(venueId, orderId, CompItemRequest(reason))
        Unit
    }.orQueueOffline(venueId, "COMP_ORDER", Unit) {
        putOrderRef(orderId)
        put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
    }.onFailure { Log.e(TAG, "❌ compWholeOrder failed: ${it.message}") }

    /** Detalles del cheque: nombre/notas/comensales/cliente (null = sin cambio). */
    suspend fun updateOrderDetails(venueId: String, orderId: String, request: OrderDetailsRequest): Result<Unit> = runCatching {
        apiService.updateOrderDetails(venueId, orderId, request)
        Unit
    }.orQueueOffline(venueId, "UPDATE_DETAILS", Unit) {
        putOrderRef(orderId)
        cacheJson.encodeToJsonElement(OrderDetailsRequest.serializer(), request)
            .let { it as kotlinx.serialization.json.JsonObject }
            .forEach { (k, v) -> if (v !is kotlinx.serialization.json.JsonNull) put(k, v) }
    }.onFailure { Log.e(TAG, "❌ updateOrderDetails failed: ${it.message}") }

    /** "Dar de cortesía": comps one line of the open order (stays on the check, costs 0). */
    suspend fun compItem(venueId: String, orderId: String, itemId: String, reason: String): Result<Unit> = runCatching {
        apiService.compOrderItem(venueId, orderId, itemId, CompItemRequest(reason))
        Unit
    }.onFailure { Log.e(TAG, "❌ compItem failed: ${it.message}") }

    /** "Anular cuenta": cancels the table's open order (server releases the table). */
    suspend fun cancelOrder(venueId: String, orderId: String, reason: String): Result<Unit> = runCatching {
        apiService.cancelOrder(venueId, orderId, CancelOrderRequest(reason))
        Unit
    }.orQueueOffline(venueId, "CANCEL_ORDER", Unit) {
        putOrderRef(orderId)
        put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
    }.onFailure { Log.e(TAG, "❌ cancelOrder failed: ${it.message}") }

    /** Releases the table (the server rejects clearing a table with an unpaid order). */
    suspend fun clearTable(venueId: String, tableId: String): Result<Unit> = runCatching {
        apiService.clearTable(venueId, tableId)
        Unit
    }.orQueueOffline(venueId, "CLEAR_TABLE", Unit) {
        put("tableId", kotlinx.serialization.json.JsonPrimitive(tableId))
    }.onFailure { Log.e(TAG, "❌ clearTable failed: ${it.message}") }
}
