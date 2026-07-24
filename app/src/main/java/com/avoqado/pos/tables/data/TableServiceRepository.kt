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
) {
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
            Log.d(TAG, "✅ ${response.data.size} tables (${response.data.count { it.isOccupied }} occupied)")
            response.data
        } finally {
            _isLoading.value = false
        }
    }.onFailure { Log.e(TAG, "❌ refresh failed: ${it.message}") }

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
    }.onFailure { Log.e(TAG, "❌ moveOrder failed: ${it.message}") }

    /** "Asignar": reassigns the OPEN check to another waiter (tips/corte follow). */
    suspend fun assignOrder(venueId: String, orderId: String, staffId: String): Result<Unit> = runCatching {
        apiService.assignOrder(venueId, orderId, AssignOrderRequest(staffId))
        Unit
    }.onFailure { Log.e(TAG, "❌ assignOrder failed: ${it.message}") }

    /** Menús del venue con su horario y cuál aplica ahora. */
    suspend fun getMenus(venueId: String): Result<MenusPayload> = runCatching {
        apiService.getMenus(venueId).data ?: MenusPayload()
    }.onFailure { Log.e(TAG, "❌ getMenus failed: ${it.message}") }

    /** Catálogo de cobros por servicio del venue. */
    suspend fun getServiceCharges(venueId: String): Result<List<ServiceChargeOption>> = runCatching {
        apiService.getServiceCharges(venueId).data
    }.onFailure { Log.e(TAG, "❌ getServiceCharges failed: ${it.message}") }

    /** Aplica un cobro por servicio a la cuenta (SUMA al total). */
    suspend fun applyServiceCharge(venueId: String, orderId: String, serviceChargeId: String): Result<Unit> = runCatching {
        apiService.applyServiceCharge(venueId, orderId, ApplyServiceChargeRequest(serviceChargeId))
        Unit
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

    /** "Fusionar cuentas": vuelca [sourceOrderId] en [orderId] y cierra el origen. */
    suspend fun mergeOrders(venueId: String, orderId: String, sourceOrderId: String): Result<Unit> = runCatching {
        apiService.mergeOrders(venueId, orderId, MergeOrdersRequest(sourceOrderId))
        Unit
    }.onFailure { Log.e(TAG, "❌ mergeOrders failed: ${it.message}") }

    /** "Dividir por puesto": un cheque por asiento (atómico en el server). */
    suspend fun splitOrderBySeat(venueId: String, orderId: String): Result<Unit> = runCatching {
        apiService.splitOrderBySeat(venueId, orderId)
        Unit
    }.onFailure { Log.e(TAG, "❌ splitOrderBySeat failed: ${it.message}") }

    /** Separa artículos en una cuenta NUEVA de la misma mesa (Square). */
    suspend fun splitOrder(venueId: String, orderId: String, itemIds: List<String>): Result<Unit> = runCatching {
        apiService.splitOrder(venueId, orderId, SplitOrderRequest(itemIds))
        Unit
    }.onFailure { Log.e(TAG, "❌ splitOrder failed: ${it.message}") }

    /** Aplica un descuento de catálogo (scope ORDER) a la cuenta abierta. */
    suspend fun applyOrderDiscount(venueId: String, orderId: String, discountId: String): Result<Unit> = runCatching {
        apiService.applyOrderDiscount(venueId, orderId, ApplyOrderDiscountRequest(discountId))
        Unit
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
    }.onFailure { Log.e(TAG, "❌ compWholeOrder failed: ${it.message}") }

    /** Detalles del cheque: nombre/notas/comensales/cliente (null = sin cambio). */
    suspend fun updateOrderDetails(venueId: String, orderId: String, request: OrderDetailsRequest): Result<Unit> = runCatching {
        apiService.updateOrderDetails(venueId, orderId, request)
        Unit
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
    }.onFailure { Log.e(TAG, "❌ cancelOrder failed: ${it.message}") }

    /** Releases the table (the server rejects clearing a table with an unpaid order). */
    suspend fun clearTable(venueId: String, tableId: String): Result<Unit> = runCatching {
        apiService.clearTable(venueId, tableId)
        Unit
    }.onFailure { Log.e(TAG, "❌ clearTable failed: ${it.message}") }
}
