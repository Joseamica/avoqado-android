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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

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

    // MARK: - Coupon Validation (POST /mobile/venues/{id}/coupons/validate)

    sealed class CouponResult {
        data class Valid(val coupon: CouponCode) : CouponResult()
        data class Invalid(val reason: String) : CouponResult()
        /** Nadie contestó ESTA petición. Se decide por cómo falló, nunca por la bandera global del aparato (memoria `bandera-de-conexion-vieja`). */
        data object SinConexion : CouponResult()
        data class Error(val message: String) : CouponResult()
    }

    /**
     * @param orderTotalCents lo que vale la venta sin el descuento de cuenta: el SERVIDOR mide contra eso la compra mínima.
     * @param customerId el cliente de la venta: sin él no se puede mirar el límite por cliente.
     */
    suspend fun validateCoupon(code: String, orderTotalCents: Int, customerId: String?): CouponResult {
        val venue = secureStorage.venueId ?: return CouponResult.Error("No venue selected")

        return try {
            val body = buildJsonObject {
                put("code", code.trim().uppercase())
                put("orderTotal", orderTotalCents / 100.0)
                customerId?.takeIf { it.isNotBlank() }?.let { put("customerId", it) }
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venue/coupons/validate")
                .post(body)
                .build()

            val (responseCode, responseBody) = withContext(Dispatchers.IO) {
                // La cajera tiene a alguien enfrente: el «Validando...» no se queda los 90 s de los tres tiempos del cliente.
                client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute().use {
                    it.code to (it.body?.string() ?: "")
                }
            }
            leerValidacionDeCupon(responseCode, responseBody)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w("🎟️", "Coupon validation sin respuesta: ${e.message}")
            CouponResult.SinConexion
        } catch (e: Exception) {
            Log.e("🎟️", "Coupon validation error: ${e.message}")
            CouponResult.Error(CUPON_NO_SE_PUDO_VALIDAR)
        }
    }

    fun clearCache() {
        _discounts.value = emptyList()
    }
}

const val CUPON_NO_SE_PUDO_VALIDAR = "No se pudo validar el cupón. Intenta de nuevo."
const val CUPON_SIN_RED_AL_VALIDAR = "Sin conexión: el cupón necesita internet para validarse. Reintenta cuando vuelva la conexión."
const val CUPON_NO_ES_DE_CUENTA = "Este cupón sólo aplica a ciertos productos y todavía no se puede usar en caja."

private val jsonDelCupon = Json { ignoreUnknownKeys = true }

/**
 * Cómo se lee la respuesta de validar un cupón. PURA, para fijarla con la respuesta real del SERVIDOR. Un `200` que no es la API (un portal
 * cautivo) no dice nada del cupón: nunca se lee como «no es válido».
 */
fun leerValidacionDeCupon(codigoHttp: Int, cuerpo: String): DiscountsRepository.CouponResult {
    if (codigoHttp !in 200..299) {
        val mensaje = runCatching { jsonDelCupon.parseToJsonElement(cuerpo).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return DiscountsRepository.CouponResult.Error(mensaje?.takeIf { codigoHttp in 400..499 && it.isNotBlank() } ?: CUPON_NO_SE_PUDO_VALIDAR)
    }
    val datos = runCatching { jsonDelCupon.decodeFromString<CouponValidationResponse>(cuerpo).data }.getOrNull()
        ?: return DiscountsRepository.CouponResult.Error(CUPON_NO_SE_PUDO_VALIDAR)
    val cupon = datos.coupon
    return when {
        !datos.valid || cupon == null -> DiscountsRepository.CouponResult.Invalid(datos.error?.takeIf { it.isNotBlank() } ?: "Este cupón no es válido.")
        !cupon.discount.scope.equals("ORDER", ignoreCase = true) -> DiscountsRepository.CouponResult.Invalid(CUPON_NO_ES_DE_CUENTA)
        else -> DiscountsRepository.CouponResult.Valid(cupon.aCoupon())
    }
}
