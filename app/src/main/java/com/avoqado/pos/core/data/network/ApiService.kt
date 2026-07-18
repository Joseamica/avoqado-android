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
import retrofit2.http.POST
import retrofit2.http.Path

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
