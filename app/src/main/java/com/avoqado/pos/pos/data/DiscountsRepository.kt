package com.avoqado.pos.pos.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.pos.data.model.CouponCode
import com.avoqado.pos.pos.data.model.CouponValidationResponse
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.DiscountsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscountsRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val payloadCache: com.avoqado.pos.core.data.local.PayloadCache,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _discounts = MutableStateFlow<List<Discount>>(emptyList())
    val discounts: StateFlow<List<Discount>> = _discounts.asStateFlow()

    suspend fun fetchDiscounts(venueId: String? = null) {
        val venue = venueId ?: secureStorage.venueId ?: return
        Log.d("📦", "Fetching discounts for venue: $venue")

        try {
            val token = secureStorage.accessToken ?: return
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venue/discounts")
                .header("Authorization", "Bearer $token")
                .build()

            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return
                val result = json.decodeFromString<DiscountsResponse>(body)
                _discounts.value = result.data.filter { it.active }
                // Offline-first: el picker de Descuentos necesita el catálogo sin red.
                payloadCache.save("discounts", venue, body)
                Log.d("📦", "✅ Loaded ${_discounts.value.size} discounts")
            } else {
                Log.e("📦", "❌ Discounts fetch failed: ${response.code}")
            }
        } catch (e: Exception) {
            // Sin red: hidratar del cache si el estado está vacío.
            if (_discounts.value.isEmpty()) {
                payloadCache.load("discounts", venue)?.let { cached ->
                    runCatching { json.decodeFromString<DiscountsResponse>(cached.json) }.getOrNull()?.let { result ->
                        _discounts.value = result.data.filter { it.active }
                        Log.w("📦", "⚠️ Descuentos sin red — cache (hace ${cached.ageMinutes} min)")
                    }
                }
            }
            Log.e("📦", "❌ Discounts fetch error: ${e.message}")
        }
    }

    fun getOrderDiscounts(): List<Discount> {
        return _discounts.value.filter {
            it.discountScope == com.avoqado.pos.pos.data.model.DiscountScope.ORDER
        }
    }

    fun getItemDiscounts(productId: String, categoryId: String?): List<Discount> {
        return _discounts.value.filter { it.appliesTo(productId, categoryId) }
    }

    // MARK: - Coupon Validation (POST /dashboard/venues/{id}/coupons/validate)

    sealed class CouponResult {
        data class Valid(val coupon: CouponCode) : CouponResult()
        data class Invalid(val reason: String) : CouponResult()
        data class Error(val message: String) : CouponResult()
    }

    suspend fun validateCoupon(code: String): CouponResult {
        val venue = secureStorage.venueId ?: return CouponResult.Error("No venue selected")

        return try {
            val body = """{"code":"${code.uppercase()}"}"""
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venue/coupons/validate")
                .post(body)
                .build()

            val (responseCode, responseBody) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            when {
                responseCode in 200..299 -> {
                    val result = json.decodeFromString<CouponValidationResponse>(responseBody)
                    val coupon = result.coupon
                    if (coupon != null && coupon.isValid) {
                        Log.d("🎟️", "Coupon valid: ${coupon.name} (${coupon.displayValue})")
                        CouponResult.Valid(coupon)
                    } else {
                        val reason = coupon?.invalidReason ?: result.error ?: "Cupon no valido"
                        CouponResult.Invalid(reason)
                    }
                }
                responseCode == 404 -> CouponResult.Invalid("Cupon no encontrado")
                responseCode == 400 -> CouponResult.Invalid("Cupon no valido")
                else -> CouponResult.Error("Error al validar cupon ($responseCode)")
            }
        } catch (e: Exception) {
            Log.e("🎟️", "Coupon validation error: ${e.message}")
            CouponResult.Error("Error de conexion")
        }
    }

    fun clearCache() {
        _discounts.value = emptyList()
    }
}
