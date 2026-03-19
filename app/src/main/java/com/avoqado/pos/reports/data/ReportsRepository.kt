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
    ): Pair<SalesSummaryReport?, List<PaymentMethodBreakdown>> {
        val url = baseUrl() ?: return Pair(null, emptyList())
        return try {
            val request = Request.Builder()
                .url("$url/reports/sales-summary?startDate=$startDate&endDate=$endDate&groupBy=paymentMethod&reportType=hourlySum")
                .get()
                .build()

            val body = executeRequest(request)
            if (body.isEmpty()) return Pair(null, emptyList())

            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject ?: return Pair(null, emptyList())

            val summary = data["summary"]?.jsonObject?.let { parseSalesSummary(it) }
            val paymentMethods = data["byPaymentMethod"]?.jsonArray
                ?.mapNotNull { it.jsonObject.let { obj -> parsePaymentMethodBreakdown(obj) } }
                ?: emptyList()

            Log.d(TAG, "✅ fetchSalesSummary: summary=$summary, methods=${paymentMethods.size}")
            Pair(summary, paymentMethods)
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchSalesSummary exception: ${e.message}", e)
            Pair(null, emptyList())
        }
    }

    // MARK: - Period Sales

    suspend fun fetchPeriodSales(
        startDate: String,
        endDate: String,
        reportType: String,
    ): List<PeriodSales> {
        val url = baseUrl() ?: return emptyList()
        return try {
            val request = Request.Builder()
                .url("$url/reports/sales-summary?startDate=$startDate&endDate=$endDate&reportType=$reportType")
                .get()
                .build()

            val body = executeRequest(request)
            if (body.isEmpty()) return emptyList()

            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject ?: return emptyList()

            val result = data["byPeriod"]?.jsonArray?.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val period = obj["period"]?.jsonPrimitive?.content ?: ""
                    val periodLabel = obj["periodLabel"]?.jsonPrimitive?.content ?: ""
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
            emptyList()
        }
    }

    // MARK: - Top Products

    suspend fun fetchTopProducts(
        startDate: String,
        endDate: String,
    ): List<TopProduct> {
        val url = baseUrl() ?: return emptyList()
        return try {
            val request = Request.Builder()
                .url("$url/reports/sales-by-item?startDate=$startDate&endDate=$endDate")
                .get()
                .build()

            val body = executeRequest(request)
            if (body.isEmpty()) return emptyList()

            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject ?: return emptyList()

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
            emptyList()
        }
    }

    // MARK: - Categories

    suspend fun fetchCategories(
        startDate: String,
        endDate: String,
    ): List<CategorySales> {
        val url = baseUrl() ?: return emptyList()
        return try {
            val request = Request.Builder()
                .url("$url/reports/sales-by-item?startDate=$startDate&endDate=$endDate&groupBy=category")
                .get()
                .build()

            val body = executeRequest(request)
            if (body.isEmpty()) return emptyList()

            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject ?: return emptyList()

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
            emptyList()
        }
    }

    // MARK: - Load Full Report (concurrent)

    suspend fun loadReport(
        startDate: String,
        endDate: String,
        reportType: String,
    ): ReportData = coroutineScope {
        _isLoading.value = true
        _errorMessage.value = null

        try {
            val summaryDeferred = async { fetchSalesSummary(startDate, endDate) }
            val periodDeferred = async { fetchPeriodSales(startDate, endDate, reportType) }
            val productsDeferred = async { fetchTopProducts(startDate, endDate) }
            val categoriesDeferred = async { fetchCategories(startDate, endDate) }

            val (summary, paymentMethods) = summaryDeferred.await()
            val periodSales = periodDeferred.await()
            val topProducts = productsDeferred.await()
            val categories = categoriesDeferred.await()

            // Extract hourly sales from summary when reportType is "hours"
            val hourlySales = if (reportType == "hours") {
                periodSales.map { ps ->
                    HourlySales(
                        id = ps.id,
                        hour = ps.period,
                        grossSales = ps.grossSales,
                        transactionCount = ps.transactionCount,
                    )
                }
            } else {
                emptyList()
            }

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
            report
        } catch (e: Exception) {
            Log.e(TAG, "❌ loadReport exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar el reporte"
            val empty = ReportData(
                summary = SalesSummaryReport(),
                paymentMethods = emptyList(),
                hourlySales = emptyList(),
                periodSales = emptyList(),
                topProducts = emptyList(),
                categories = emptyList(),
            )
            _reportData.value = empty
            empty
        } finally {
            _isLoading.value = false
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
        )
    }

    private fun parsePaymentMethodBreakdown(obj: JsonObject): PaymentMethodBreakdown {
        return PaymentMethodBreakdown(
            id = obj["id"]?.jsonPrimitive?.content ?: "",
            method = obj["method"]?.jsonPrimitive?.content ?: "",
            amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            count = obj["count"]?.jsonPrimitive?.intOrNull ?: 0,
            percentage = obj["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }
}
