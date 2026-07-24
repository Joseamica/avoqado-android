package com.avoqado.pos.tables.data

import android.util.Log
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TableServiceRepository"

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
    )

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
    ) {
        /** ¿Esta cuenta es intocable para mí? (la regla la refuerza el server; esto pinta la UI) */
        fun isLockedForMe(ownerId: String?): Boolean = enforced && !canManageAll && ownerId != null && ownerId != staffId
    }

    private val _ownership = MutableStateFlow(TableOwnership())
    val ownership: StateFlow<TableOwnership> = _ownership.asStateFlow()

    suspend fun refresh(venueId: String): Result<List<DiningTable>> = runCatching {
        _isLoading.value = true
        try {
            val response = apiService.getTables(venueId)
            _tables.value = response.data
            _ownership.value = TableOwnership(
                enforced = response.settings?.enforceTableOwnership == true,
                staffId = response.viewer?.staffId,
                canManageAll = response.viewer?.canManageAllTables ?: true,
            )
            // Espejo en disco (Corte A): plano + regla de propiedad sobreviven
            // un reinicio sin red.
            payloadCache.save(
                com.avoqado.pos.core.data.local.PayloadCache.TYPE_TABLES,
                venueId,
                cacheJson.encodeToString(
                    TablesCacheBlob.serializer(),
                    TablesCacheBlob(
                        tables = response.data,
                        enforced = _ownership.value.enforced,
                        staffId = _ownership.value.staffId,
                        canManageAll = _ownership.value.canManageAll,
                    ),
                ),
            )
            Log.d(TAG, "✅ ${response.data.size} tables (${response.data.count { it.isOccupied }} occupied)")
            response.data
        } finally {
            _isLoading.value = false
        }
    }.onFailure {
        Log.e(TAG, "❌ refresh failed: ${it.message}")
        // Sin red: hidratar el plano del espejo en disco para que las mesas
        // sigan visibles (frescura marcada; el server sigue siendo la verdad).
        hydrateFromCache(venueId)
    }

    /**
     * Offline-first: marca la mesa OCUPADA en el estado local (optimista) al
     * abrirla sin red — el plano refleja la apertura al instante; el server la
     * confirmará en el replay.
     */
    fun markTableOccupiedLocally(tableId: String) {
        _tables.value = _tables.value.map { if (it.id == tableId) it.copy(status = "OCCUPIED") else it }
    }

    /** Hidrata mesas + regla de propiedad del cache SOLO si el estado está vacío. */
    private suspend fun hydrateFromCache(venueId: String) {
        if (_tables.value.isNotEmpty()) return
        val cached = payloadCache.load(com.avoqado.pos.core.data.local.PayloadCache.TYPE_TABLES, venueId) ?: return
        runCatching {
            val blob = cacheJson.decodeFromString(TablesCacheBlob.serializer(), cached.json)
            if (blob.tables.isNotEmpty() && _tables.value.isEmpty()) {
                _tables.value = blob.tables
                _ownership.value = TableOwnership(blob.enforced, blob.staffId, blob.canManageAll)
                Log.d(TAG, "🗂️ Plano hidratado del cache: ${blob.tables.size} mesas (hace ${cached.ageMinutes} min)")
            }
        }.onFailure { Log.e(TAG, "❌ Cache de mesas corrupto: ${it.message}") }
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
     * ONLINE-ONLY a propósito: crea N cheques de golpe y cada uno necesitaría su
     * propio id local para que un cobro posterior aterrice bien. Separar
     * artículos ([splitOrder]) sí funciona offline.
     */
    suspend fun splitOrderBySeat(venueId: String, orderId: String): Result<Unit> = runCatching {
        apiService.splitOrderBySeat(venueId, orderId)
        Unit
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
    suspend fun splitOrder(venueId: String, orderId: String, itemIds: List<String>): Result<Unit> = runCatching {
        apiService.splitOrder(venueId, orderId, SplitOrderRequest(itemIds))
        Unit
    }.orQueueOffline(venueId, "SPLIT_ORDER", Unit) {
        putOrderRef(orderId)
        put(
            "itemRefs",
            kotlinx.serialization.json.buildJsonArray {
                itemIds.forEach { id ->
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
