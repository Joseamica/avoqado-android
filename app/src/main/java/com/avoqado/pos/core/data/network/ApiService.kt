package com.avoqado.pos.core.data.network

import com.avoqado.pos.auth.data.model.LoginRequest
import com.avoqado.pos.auth.data.model.LoginResponse
import com.avoqado.pos.auth.data.model.RefreshRequest
import com.avoqado.pos.auth.data.model.RefreshResponse
import com.avoqado.pos.printing.routing.GatewayHeartbeatRequest
import com.avoqado.pos.printing.routing.PrintConfigResponse
import com.avoqado.pos.printing.routing.SyncPrintJobsRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    // MARK: - Auth

    @POST("mobile/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("mobile/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): RefreshResponse

    // MARK: - Products

    @GET("dashboard/venues/{venueId}/products")
    suspend fun getProducts(@Path("venueId") venueId: String): Any // TODO: typed response

    // MARK: - Discounts

    @GET("dashboard/venues/{venueId}/discounts")
    suspend fun getDiscounts(@Path("venueId") venueId: String): Any // TODO: typed response

    // MARK: - TPV Settings

    @GET("mobile/venues/{venueId}/tpv-settings")
    suspend fun getTpvSettings(@Path("venueId") venueId: String): Any // TODO: typed response

    // MARK: - Orders

    @POST("mobile/venues/{venueId}/orders")
    suspend fun createOrder(
        @Path("venueId") venueId: String,
        @Body request: Any, // TODO: typed request
    ): Any // TODO: typed response

    // MARK: - Area Tickets v7

    @GET("mobile/venues/{venueId}/area-ticket-settings")
    suspend fun getAreaTicketSettings(
        @Path("venueId") venueId: String,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.AreaTicketSettingsData>

    @GET("mobile/venues/{venueId}/scale-settings")
    suspend fun getScaleSettings(
        @Path("venueId") venueId: String,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.ScaleIntegrationSettings>

    @POST("mobile/venues/{venueId}/scans/resolve")
    suspend fun resolveAreaTicketScan(
        @Path("venueId") venueId: String,
        @Body request: com.avoqado.pos.areatickets.data.ScanRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.AreaTicketScanData>

    @POST("mobile/venues/{venueId}/area-ticket-checkouts")
    suspend fun createAreaTicketCheckout(
        @Path("venueId") venueId: String,
        @Body request: com.avoqado.pos.areatickets.data.IdempotentRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.CheckoutData>

    @POST("mobile/venues/{venueId}/area-ticket-checkouts/{sessionId}/tickets")
    suspend fun addAreaTicketToCheckout(
        @Path("venueId") venueId: String,
        @Path("sessionId") sessionId: String,
        @Body request: com.avoqado.pos.areatickets.data.AddTicketRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.CheckoutData>

    @HTTP(
        method = "DELETE",
        path = "mobile/venues/{venueId}/area-ticket-checkouts/{sessionId}/tickets/{ticketId}",
        hasBody = true,
    )
    suspend fun removeAreaTicketFromCheckout(
        @Path("venueId") venueId: String,
        @Path("sessionId") sessionId: String,
        @Path("ticketId") ticketId: String,
        @Body request: com.avoqado.pos.areatickets.data.IdempotentRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.CheckoutData>

    @POST("mobile/venues/{venueId}/area-ticket-checkouts/{sessionId}/materialize-order")
    suspend fun materializeAreaTicketCheckout(
        @Path("venueId") venueId: String,
        @Path("sessionId") sessionId: String,
        @Body request: com.avoqado.pos.areatickets.data.MaterializeCheckoutRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.CheckoutData>

    @POST("mobile/venues/{venueId}/area-ticket-checkouts/{sessionId}/heartbeat")
    suspend fun heartbeatAreaTicketCheckout(
        @Path("venueId") venueId: String,
        @Path("sessionId") sessionId: String,
        @Body request: com.avoqado.pos.areatickets.data.IdempotentRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.CheckoutData>

    @GET("mobile/venues/{venueId}/area-ticket-checkouts/{sessionId}")
    suspend fun getAreaTicketCheckout(
        @Path("venueId") venueId: String,
        @Path("sessionId") sessionId: String,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.CheckoutData>

    @POST("mobile/venues/{venueId}/area-ticket-checkouts/{sessionId}/cancel")
    suspend fun cancelAreaTicketCheckout(
        @Path("venueId") venueId: String,
        @Path("sessionId") sessionId: String,
        @Body request: com.avoqado.pos.areatickets.data.IdempotentRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.CheckoutData>

    @POST("mobile/venues/{venueId}/area-tickets")
    suspend fun issueAreaTicket(
        @Path("venueId") venueId: String,
        @Body request: com.avoqado.pos.areatickets.data.IssueAreaTicketRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.IssuedTicketData>

    @POST("mobile/venues/{venueId}/area-tickets/{ticketId}/print-attempts")
    suspend fun recordAreaTicketPrintAttempt(
        @Path("venueId") venueId: String,
        @Path("ticketId") ticketId: String,
        @Body request: com.avoqado.pos.areatickets.data.PrintAttemptRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<kotlinx.serialization.json.JsonObject>

    @GET("mobile/venues/{venueId}/area-ticket-fulfillment/pending")
    suspend fun getPendingAreaTicketFulfillment(
        @Path("venueId") venueId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.PendingFulfillmentData>

    @POST("mobile/venues/{venueId}/area-ticket-fulfillment/resolve")
    suspend fun resolveAreaTicketFulfillment(
        @Path("venueId") venueId: String,
        @Body request: com.avoqado.pos.areatickets.data.ScanRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<com.avoqado.pos.areatickets.data.DeliveryResolutionData>

    @POST("mobile/venues/{venueId}/area-tickets/{ticketId}/fulfill")
    suspend fun fulfillAreaTicket(
        @Path("venueId") venueId: String,
        @Path("ticketId") ticketId: String,
        @Body request: com.avoqado.pos.areatickets.data.FulfillAreaTicketRequest,
    ): com.avoqado.pos.areatickets.data.AreaTicketEnvelope<kotlinx.serialization.json.JsonObject>

    // MARK: - Print Stations (PRINT_STATIONS)

    @GET("mobile/venues/{venueId}/print-config")
    suspend fun getPrintConfig(@Path("venueId") venueId: String): PrintConfigResponse

    @POST("mobile/venues/{venueId}/print-jobs/sync")
    suspend fun syncPrintJobs(
        @Path("venueId") venueId: String,
        @Body request: SyncPrintJobsRequest,
    ): Any // replica ack (upserted / errors / newlyFailed)

    @POST("mobile/venues/{venueId}/print-gateway/heartbeat")
    suspend fun gatewayHeartbeat(
        @Path("venueId") venueId: String,
        @Body request: GatewayHeartbeatRequest,
    ): Any // { registered, printersUpdated }

    // MARK: - Time Clock

    @POST("mobile/venues/{venueId}/time-clock/identify")
    suspend fun identifyStaff(
        @Path("venueId") venueId: String,
        @Body request: Any,
    ): Any

    // MARK: - Device Registration

    @POST("mobile/venues/{venueId}/devices/register")
    suspend fun registerDevice(
        @Path("venueId") venueId: String,
        @Body request: Any,
    ): Any

    @POST("mobile/venues/{venueId}/devices/unregister")
    suspend fun unregisterDevice(
        @Path("venueId") venueId: String,
        @Body request: Any,
    ): Any

    // MARK: - Offline-first sync (Corte B/D)

    @POST("mobile/venues/{venueId}/sync/intents")
    suspend fun syncIntents(
        @Path("venueId") venueId: String,
        @Body request: com.avoqado.pos.core.data.sync.SyncIntentsRequest,
    ): com.avoqado.pos.core.data.sync.SyncIntentsResponse

    // MARK: - Table Service (TABLE_SERVICE, PRO)

    @GET("mobile/venues/{venueId}/tables")
    suspend fun getTables(@Path("venueId") venueId: String): com.avoqado.pos.tables.data.TablesResponse

    @POST("mobile/venues/{venueId}/tables/{tableId}/open")
    suspend fun openTable(
        @Path("venueId") venueId: String,
        @Path("tableId") tableId: String,
        @Body request: com.avoqado.pos.tables.data.OpenTableRequest,
    ): com.avoqado.pos.tables.data.OpenTableResponse

    @POST("mobile/venues/{venueId}/tables/{tableId}/clear")
    suspend fun clearTable(
        @Path("venueId") venueId: String,
        @Path("tableId") tableId: String,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // Full check for the table panel: items with modifiers, course and
    // createdAt (== send time), ordered by createdAt.
    @GET("mobile/venues/{venueId}/orders/{orderId}")
    suspend fun getOrderDetail(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
    ): com.avoqado.pos.tables.data.OrderDetailResponse

    @POST("mobile/venues/{venueId}/orders/{orderId}/items")
    suspend fun addOrderItems(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.AddItemsRequest,
    ): com.avoqado.pos.tables.data.AddItemsResponse

    @POST("mobile/venues/{venueId}/orders/{orderId}/pay")
    suspend fun payOrderCash(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.PayCashRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // "Mover" (TABLE_SERVICE) — moves the OPEN check to another table.
    @POST("mobile/venues/{venueId}/orders/{orderId}/move")
    suspend fun moveOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.MoveOrderRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // "Asignar" (TABLE_SERVICE) — reassigns the OPEN check to another waiter.
    @POST("mobile/venues/{venueId}/orders/{orderId}/assign")
    suspend fun assignOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.AssignOrderRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // Menús por horario: cuál aplica ahora + categorías de cada uno.
    @GET("mobile/venues/{venueId}/menus")
    suspend fun getMenus(
        @Path("venueId") venueId: String,
    ): com.avoqado.pos.tables.data.MenusResponse

    // Cobros por servicio (TABLE_SERVICE): catálogo del venue.
    @GET("mobile/venues/{venueId}/service-charges")
    suspend fun getServiceCharges(
        @Path("venueId") venueId: String,
    ): com.avoqado.pos.tables.data.ServiceChargesResponse

    // Aplica un cobro por servicio a la cuenta (SUMA al total).
    @POST("mobile/venues/{venueId}/orders/{orderId}/service-charges")
    suspend fun applyServiceCharge(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.ApplyServiceChargeRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // Quita un cobro por servicio aplicado.
    @retrofit2.http.DELETE("mobile/venues/{venueId}/orders/{orderId}/service-charges/{orderServiceChargeId}")
    suspend fun removeServiceCharge(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Path("orderServiceChargeId") orderServiceChargeId: String,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // Recompensas (LOYALTY_PROGRAM): saldo de puntos del cliente del cheque.
    @GET("mobile/venues/{venueId}/customers/{customerId}/loyalty")
    suspend fun getCustomerLoyalty(
        @Path("venueId") venueId: String,
        @Path("customerId") customerId: String,
        @Query("orderId") orderId: String? = null,
    ): com.avoqado.pos.tables.data.CustomerLoyaltyResponse

    // Canjea puntos como descuento en la cuenta abierta (una sola transacción).
    @POST("mobile/venues/{venueId}/orders/{orderId}/loyalty/redeem")
    suspend fun redeemLoyaltyPoints(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.RedeemPointsRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // "Fusionar cuentas": vuelca sourceOrderId en esta cuenta (inverso de dividir).
    @POST("mobile/venues/{venueId}/orders/{orderId}/merge")
    suspend fun mergeOrders(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.MergeOrdersRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // "Dividir por puesto": un cheque por asiento, en una sola transacción.
    @POST("mobile/venues/{venueId}/orders/{orderId}/split-by-seat")
    suspend fun splitOrderBySeat(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // Separar artículos en una cuenta NUEVA de la misma mesa (TABLE_SERVICE).
    @POST("mobile/venues/{venueId}/orders/{orderId}/split")
    suspend fun splitOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.SplitOrderRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // Descuentos de orden en el cheque (TABLE_SERVICE).
    @POST("mobile/venues/{venueId}/orders/{orderId}/discounts")
    suspend fun applyOrderDiscount(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.ApplyOrderDiscountRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    @retrofit2.http.DELETE("mobile/venues/{venueId}/orders/{orderId}/discounts/{orderDiscountId}")
    suspend fun removeOrderDiscount(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Path("orderDiscountId") orderDiscountId: String,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // "Cortesía en la cuenta" (TABLE_SERVICE) — comps EVERY line with one reason.
    @POST("mobile/venues/{venueId}/orders/{orderId}/comp")
    suspend fun compWholeOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.CompItemRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // Detalles del cheque (TABLE_SERVICE) — nombre/notas/comensales/cliente.
    // null = sin cambio; string vacío borra (semántica tolerante del server).
    @POST("mobile/venues/{venueId}/orders/{orderId}/details")
    suspend fun updateOrderDetails(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.OrderDetailsRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // "Dar de cortesía" (TABLE_SERVICE) — comps one line with a required reason.
    @POST("mobile/venues/{venueId}/orders/{orderId}/items/{itemId}/comp")
    suspend fun compOrderItem(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Path("itemId") itemId: String,
        @Body request: com.avoqado.pos.tables.data.CompItemRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse

    // "Anular cuenta" (TABLE_SERVICE) — cancels the open order with a required
    // reason; the server releases the bound table (Square's void-check flow).
    @retrofit2.http.HTTP(method = "DELETE", path = "mobile/venues/{venueId}/orders/{orderId}", hasBody = true)
    suspend fun cancelOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: com.avoqado.pos.tables.data.CancelOrderRequest,
    ): com.avoqado.pos.tables.data.SimpleSuccessResponse
}
