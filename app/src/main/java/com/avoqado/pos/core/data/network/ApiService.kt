package com.avoqado.pos.core.data.network

import com.avoqado.pos.auth.data.model.LoginRequest
import com.avoqado.pos.auth.data.model.LoginResponse
import com.avoqado.pos.auth.data.model.RefreshRequest
import com.avoqado.pos.auth.data.model.RefreshResponse
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
}
