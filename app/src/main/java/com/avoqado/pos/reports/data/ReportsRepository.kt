package com.avoqado.pos.reports.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.reports.data.model.CategorySales
import com.avoqado.pos.reports.data.model.HourlySales
import com.avoqado.pos.reports.data.model.PaymentMethodBreakdown
import com.avoqado.pos.reports.data.model.PeriodSales
import com.avoqado.pos.reports.data.model.ReportData
import com.avoqado.pos.reports.data.model.SalesSummaryReport
import com.avoqado.pos.reports.data.model.TopProduct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.contentOrNull

private const val TAG = "📊 ReportsRepo"

@Singleton
class ReportsRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - State Flows

    private val _reportData = MutableStateFlow<ReportData?>(null)
    val reportData: StateFlow<ReportData?> = _reportData.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // MARK: - Helpers

    private fun baseUrl(): String? {
        val venueId = secureStorage.venueId ?: return null
        return "${ApiConstants.BASE_URL}/mobile/venues/$venueId"
    }

    private suspend fun executeRequest(request: Request): String =
        withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.body?.string() ?: ""
        }

    // MARK: - Sales Summary

    suspend fun fetchSalesSummary(
        startDate: String,
        endDate: String,
    ): Triple<SalesSummaryReport?, List<PaymentMethodBreakdown>, List<HourlySales>>? {
        // null = FALLO (red/parse); un Triple con summary null es "sin ventas", que es válido.
        val url = baseUrl() ?: return null
        return try {
            val request = Request.Builder()
                .url("$url/reports/sales-summary?startDate=$startDate&endDate=$endDate&groupBy=paymentMethod&reportType=hourlySum")
                .get()
                .build()

            val body = executeRequest(request)
            if (body.isEmpty()) return null

            val (summary, paymentMethods) = leerResumenDeVentas(body) ?: return null
            val ventasPorHora = leerVentasPorHora(body)

            Log.d(TAG, "✅ fetchSalesSummary: summary=$summary, methods=${paymentMethods.size}, horas=${ventasPorHora.size}")
            Triple(summary, paymentMethods, ventasPorHora)
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchSalesSummary exception: ${e.message}", e)
            null
        }
    }

    // MARK: - Period Sales

    suspend fun fetchPeriodSales(
        startDate: String,
        endDate: String,
        reportType: String,
    ): List<PeriodSales>? {
        // null = FALLO; emptyList() = respuesta válida sin filas.
        val url = baseUrl() ?: return null
        return try {
            val request = Request.Builder()
                .url("$url/reports/sales-summary?startDate=$startDate&endDate=$endDate&reportType=$reportType")
                .get()
                .build()

            val body = executeRequest(request)
            if (body.isEmpty()) return null

            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject ?: return null

            val result = data["byPeriod"]?.jsonArray?.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val period = obj["period"]?.jsonPrimitive?.contentOrNull ?: ""
                    val periodLabel = obj["periodLabel"]?.jsonPrimitive?.contentOrNull ?: ""
                    val metrics = obj["metrics"]?.jsonObject
                    val grossSales = metrics?.get("grossSales")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val transactionCount = metrics?.get("transactionCount")?.jsonPrimitive?.intOrNull ?: 0
                    PeriodSales(
                        id = period,
                        period = period,
                        periodLabel = periodLabel,
                        grossSales = grossSales,
                        transactionCount = transactionCount,
                    )
                } catch (_: Exception) {
                    null
                }
            } ?: emptyList()

            Log.d(TAG, "✅ fetchPeriodSales: ${result.size} periods")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchPeriodSales exception: ${e.message}", e)
            null
        }
    }

    // MARK: - Top Products

    suspend fun fetchTopProducts(
        startDate: String,
        endDate: String,
    ): List<TopProduct>? {
        // null = FALLO; emptyList() = respuesta válida sin filas.
        val url = baseUrl() ?: return null
        return try {
            val request = Request.Builder()
                .url("$url/reports/sales-by-item?startDate=$startDate&endDate=$endDate")
                .get()
                .build()

            val body = executeRequest(request)
            if (body.isEmpty()) return null

            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject ?: return null

            val result = data["items"]?.jsonArray?.mapNotNull { element ->
                try {
                    json.decodeFromString<TopProduct>(element.toString())
                } catch (_: Exception) {
                    null
                }
            }
                ?.sortedByDescending { it.unitsSold }
                ?.take(10)
                ?: emptyList()

            Log.d(TAG, "✅ fetchTopProducts: ${result.size} products")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchTopProducts exception: ${e.message}", e)
            null
        }
    }

    // MARK: - Categories

    suspend fun fetchCategories(
        startDate: String,
        endDate: String,
    ): List<CategorySales>? {
        // null = FALLO; emptyList() = respuesta válida sin filas.
        val url = baseUrl() ?: return null
        return try {
            val request = Request.Builder()
                .url("$url/reports/sales-by-item?startDate=$startDate&endDate=$endDate&groupBy=category")
                .get()
                .build()

            val body = executeRequest(request)
            if (body.isEmpty()) return null

            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject ?: return null

            val result = data["items"]?.jsonArray?.mapNotNull { element ->
                try {
                    json.decodeFromString<CategorySales>(element.toString())
                } catch (_: Exception) {
                    null
                }
            }
                ?.sortedByDescending { it.grossSales }
                ?: emptyList()

            Log.d(TAG, "✅ fetchCategories: ${result.size} categories")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchCategories exception: ${e.message}", e)
            null
        }
    }

    // MARK: - Load Full Report (concurrent)

    suspend fun loadReport(
        startDate: String,
        endDate: String,
        reportType: String,
    ): Result<Unit> = coroutineScope {
        // Refresh de fondo silencioso: skeleton sólo sin datos (spec refresco §6).
        _isLoading.value = _reportData.value == null
        _errorMessage.value = null

        try {
            val summaryDeferred = async { fetchSalesSummary(startDate, endDate) }
            val periodDeferred = async { fetchPeriodSales(startDate, endDate, reportType) }
            val productsDeferred = async { fetchTopProducts(startDate, endDate) }
            val categoriesDeferred = async { fetchCategories(startDate, endDate) }

            val resumen = summaryDeferred.await()
            val periodSalesResult = periodDeferred.await()
            val topProductsResult = productsDeferred.await()
            val categoriesResult = categoriesDeferred.await()

            // 🔴 Regla §4.1 (era el caso rojo de la auditoría): un fallo PARCIAL
            // o total jamás publica un reporte de CEROS encima del bueno. Un
            // reporte de dinero en cero que no es cero es peor que uno viejo.
            if (resumen == null || periodSalesResult == null ||
                topProductsResult == null || categoriesResult == null
            ) {
                Log.e(TAG, "❌ loadReport: sub-reporte fallido; datos previos intactos")
                if (_reportData.value == null) _errorMessage.value = "Error al cargar el reporte"
                _isLoading.value = false
                return@coroutineScope Result.failure(Exception("Reporte incompleto"))
            }
            // «Ventas por hora» son las 24 horas del día que trae el resumen, como en iOS (D6, 2-nov). Antes salían de la serie
            // del periodo de HOY: sólo las horas con venta y como instantes ISO, así que la hora nunca se podía rotular.
            val (summary, paymentMethods, hourlySales) = resumen
            val periodSales = periodSalesResult
            val topProducts = topProductsResult
            val categories = categoriesResult

            val report = ReportData(
                summary = summary ?: SalesSummaryReport(),
                paymentMethods = paymentMethods,
                hourlySales = hourlySales,
                periodSales = periodSales,
                topProducts = topProducts,
                categories = categories,
            )

            _reportData.value = report
            Log.d(TAG, "✅ loadReport complete")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ loadReport exception: ${e.message}", e)
            // Datos previos INTACTOS; error visible sólo cuando no hay nada (spec §6).
            if (_reportData.value == null) _errorMessage.value = "Error al cargar el reporte"
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

}

internal fun leerResumenDeVentas(body: String): Pair<SalesSummaryReport?, List<PaymentMethodBreakdown>>? {
    return try {
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return null
        val summary = data["summary"]?.jsonObject?.let { parseSalesSummary(it) }
        // Una lista de ventas vacía también manda: no revivir las cargas de byPaymentMethod.
        val methods = data["salesByPaymentMethod"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }
            ?: data["byPaymentMethod"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }
        val paymentMethods = methods?.jsonArray?.map { parsePaymentMethodBreakdown(it.jsonObject) } ?: emptyList()
        Pair(summary, paymentMethods)
    } catch (_: Exception) {
        null
    }
}

/**
 * Las horas del día del resumen (`byPeriod` con `reportType=hourlySum`): «HH:00» en la hora del negocio, las 24 con ceros.
 * Espejo de `HourlySales.from(json:)` de iOS. Sin `byPeriod` no hay ninguna, y el resumen sigue valiendo.
 */
internal fun leerVentasPorHora(body: String): List<HourlySales> {
    val horas = try {
        Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("byPeriod")
            ?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.jsonArray
    } catch (_: Exception) {
        null
    } ?: return emptyList()
    return horas.mapNotNull { elemento ->
        try {
            val obj = elemento.jsonObject
            val hora = obj["period"]?.jsonPrimitive?.contentOrNull ?: "00:00"
            val metricas = obj["metrics"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.jsonObject
            HourlySales(
                id = hora,
                hour = hora,
                grossSales = metricas?.get("grossSales")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                transactionCount = metricas?.get("transactionCount")?.jsonPrimitive?.intOrNull ?: 0,
            )
        } catch (_: Exception) {
            null
        }
    }
}

// MARK: - JSON Parsers

private fun parseSalesSummary(obj: JsonObject): SalesSummaryReport {
    return SalesSummaryReport(
        grossSales = obj["grossSales"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        netSales = obj["netSales"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        discounts = obj["discounts"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        refunds = obj["refunds"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        taxes = obj["taxes"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        tips = obj["tips"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        platformFees = obj["platformFees"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        staffCommissions = obj["staffCommissions"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        totalCollected = obj["totalCollected"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        netProfit = obj["netProfit"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        transactionCount = obj["transactionCount"]?.jsonPrimitive?.intOrNull ?: 0,
        giftCardLoads = obj["giftCardLoads"]?.jsonPrimitive?.doubleOrNull,
        giftCardLoadsCount = obj["giftCardLoadsCount"]?.jsonPrimitive?.intOrNull,
        paidWithGiftCardBalance = obj["paidWithGiftCardBalance"]?.jsonPrimitive?.doubleOrNull,
    )
}

private fun parsePaymentMethodBreakdown(obj: JsonObject): PaymentMethodBreakdown {
    return PaymentMethodBreakdown(
        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
        method = obj["method"]?.jsonPrimitive?.contentOrNull ?: "",
        amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        count = obj["count"]?.jsonPrimitive?.intOrNull ?: 0,
        percentage = obj["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
    )
}
