package com.avoqado.pos.reports.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.data.network.LecturaFallida
import com.avoqado.pos.reports.data.model.CategorySales
import com.avoqado.pos.reports.data.model.ConsultaDeInforme
import com.avoqado.pos.reports.data.model.InformeConsultado
import com.avoqado.pos.reports.data.model.HourlySales
import com.avoqado.pos.reports.data.model.PaymentMethodBreakdown
import com.avoqado.pos.reports.data.model.PeriodSales
import com.avoqado.pos.reports.data.model.ReportData
import com.avoqado.pos.reports.data.model.SalesSummaryReport
import com.avoqado.pos.reports.data.model.TopProduct
import kotlinx.coroutines.CancellationException
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

    private val _informe = MutableStateFlow<InformeConsultado?>(null)

    /**
     * El último informe bueno y la consulta que lo trajo (D18). Un fallo NO lo borra: la pantalla sólo lo enseña bajo la
     * misma consulta (el de «Hoy» nunca bajo «1M»).
     */
    val informe: StateFlow<InformeConsultado?> = _informe.asStateFlow()

    private val _isLoading = MutableStateFlow(false)

    /**
     * Hay una consulta en camino. Con el informe del periodo a la vista la recarga es silenciosa (spec refresco §6): lo
     * decide la pantalla, que sólo enseña el esqueleto si no tiene el informe del periodo elegido (D18).
     */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _ultimoFallo = MutableStateFlow<FalloDeLectura?>(null)

    /** D18: por qué falló la última carga —sin red, o el servidor contestó mal— y `null` si salió bien. */
    val ultimoFallo: StateFlow<FalloDeLectura?> = _ultimoFallo.asStateFlow()

    // MARK: - Helpers

    /** La sucursal activa: la parte de la consulta que va en la ruta. */
    fun sucursalActiva(): String? = secureStorage.venueId

    private fun baseUrl(): String? = secureStorage.venueId?.let(::rutaDeLaSucursal)

    private fun rutaDeLaSucursal(sucursal: String): String = "${ApiConstants.BASE_URL}/mobile/venues/$sucursal"

    /**
     * Un GET: el cuerpo de un 2xx, o por qué no se pudo ([LecturaFallida]). Un HTTP que no es 2xx o un cuerpo vacío es del
     * servidor; que nadie conteste, sin red. Nunca lanza, salvo la cancelación.
     */
    private suspend fun leer(url: String): Result<String> = try {
        val (code, body) = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                response.code to (response.body?.string() ?: "")
            }
        }
        if (code in 200..299 && body.isNotEmpty()) {
            Result.success(body)
        } else {
            Log.e(TAG, "❌ HTTP $code (cuerpo de ${body.length} caracteres)")
            Result.failure(LecturaFallida(FalloDeLectura.SERVIDOR))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "❌ sin respuesta: ${e.message}")
        Result.failure(LecturaFallida(FalloDeLectura.de(e), e))
    }

    /** Lee un cuerpo ya recibido; si no tiene la forma esperada, el fallo es del servidor. */
    private inline fun <T> interpretar(cuerpo: Result<String>, que: String, leerCuerpo: (String) -> T?): Result<T> =
        cuerpo.fold(
            onSuccess = { body ->
                val valor = try {
                    leerCuerpo(body)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ $que ilegible: ${e.message}")
                    null
                }
                if (valor != null) Result.success(valor) else Result.failure(LecturaFallida(FalloDeLectura.SERVIDOR))
            },
            onFailure = { Result.failure(it) },
        )

    // MARK: - Sales Summary

    suspend fun fetchSalesSummary(
        startDate: String,
        endDate: String,
    ): Triple<SalesSummaryReport?, List<PaymentMethodBreakdown>, List<HourlySales>>? {
        // null = FALLO (red/parse); un Triple con summary null es "sin ventas", que es válido.
        val url = baseUrl() ?: return null
        return resumen(url, startDate, endDate).getOrNull()
    }

    private suspend fun resumen(
        url: String,
        startDate: String,
        endDate: String,
    ): Result<Triple<SalesSummaryReport?, List<PaymentMethodBreakdown>, List<HourlySales>>> = interpretar(
        leer("$url/reports/sales-summary?startDate=$startDate&endDate=$endDate&groupBy=paymentMethod&reportType=hourlySum"),
        "resumen",
    ) { body ->
        leerResumenDeVentas(body)?.let { (summary, paymentMethods) ->
            val ventasPorHora = leerVentasPorHora(body)
            Log.d(TAG, "✅ fetchSalesSummary: summary=$summary, methods=${paymentMethods.size}, horas=${ventasPorHora.size}")
            Triple(summary, paymentMethods, ventasPorHora)
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
        return ventasPorPeriodo(url, startDate, endDate, reportType).getOrNull()
    }

    private suspend fun ventasPorPeriodo(
        url: String,
        startDate: String,
        endDate: String,
        reportType: String,
    ): Result<List<PeriodSales>> = interpretar(
        leer("$url/reports/sales-summary?startDate=$startDate&endDate=$endDate&reportType=$reportType"),
        "ventas por periodo",
    ) { body ->
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return@interpretar null
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
    }

    // MARK: - Top Products

    suspend fun fetchTopProducts(
        startDate: String,
        endDate: String,
    ): List<TopProduct>? {
        // null = FALLO; emptyList() = respuesta válida sin filas.
        val url = baseUrl() ?: return null
        return productosMasVendidos(url, startDate, endDate).getOrNull()
    }

    private suspend fun productosMasVendidos(url: String, startDate: String, endDate: String): Result<List<TopProduct>> =
        interpretar(leer("$url/reports/sales-by-item?startDate=$startDate&endDate=$endDate"), "productos") { body ->
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return@interpretar null
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
        }

    // MARK: - Categories

    suspend fun fetchCategories(
        startDate: String,
        endDate: String,
    ): List<CategorySales>? {
        // null = FALLO; emptyList() = respuesta válida sin filas.
        val url = baseUrl() ?: return null
        return categorias(url, startDate, endDate).getOrNull()
    }

    private suspend fun categorias(url: String, startDate: String, endDate: String): Result<List<CategorySales>> =
        interpretar(
            leer("$url/reports/sales-by-item?startDate=$startDate&endDate=$endDate&groupBy=category"),
            "categorías",
        ) { body ->
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return@interpretar null
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
        }

    // MARK: - Load Full Report (concurrent)

    /**
     * Carga el informe de [consulta]. [hasta] es la fecha que viaja como fin: la de la consulta o, en un periodo fijo,
     * «ahora».
     */
    suspend fun loadReport(consulta: ConsultaDeInforme, hasta: String): Result<Unit> {
        if (consulta.sucursal.isEmpty()) return Result.failure(IllegalStateException("Sin sucursal activa"))
        val url = rutaDeLaSucursal(consulta.sucursal)
        val inicio = consulta.inicio
        _isLoading.value = true
        return try {
            coroutineScope {
                val resumenD = async { resumen(url, inicio, hasta) }
                val periodoD = async { ventasPorPeriodo(url, inicio, hasta, consulta.tipo) }
                val productosD = async { productosMasVendidos(url, inicio, hasta) }
                val categoriasD = async { categorias(url, inicio, hasta) }

                val resumenResult = resumenD.await()
                val periodSalesResult = periodoD.await()
                val topProductsResult = productosD.await()
                val categoriesResult = categoriasD.await()

                // 🔴 Regla §4.1 (era el caso rojo de la auditoría): un fallo PARCIAL
                // o total jamás publica un reporte de CEROS encima del bueno. Un
                // reporte de dinero en cero que no es cero es peor que uno viejo.
                val fallos = listOf(resumenResult, periodSalesResult, topProductsResult, categoriesResult)
                    .mapNotNull { it.exceptionOrNull() }
                if (fallos.isNotEmpty()) {
                    // D18: si alguna parte no tuvo respuesta, es falta de red; si todas contestaron, el servidor contestó mal.
                    val fallo = if (fallos.any { FalloDeLectura.de(it) == FalloDeLectura.SIN_RED }) {
                        FalloDeLectura.SIN_RED
                    } else {
                        FalloDeLectura.SERVIDOR
                    }
                    Log.e(TAG, "❌ loadReport: sub-reporte fallido ($fallo); datos previos intactos")
                    _ultimoFallo.value = fallo
                    return@coroutineScope Result.failure(LecturaFallida(fallo, fallos.first()))
                }
                // «Ventas por hora» son las 24 horas del día que trae el resumen, como en iOS (D6, 2-nov). Antes salían de la serie
                // del periodo de HOY: sólo las horas con venta y como instantes ISO, así que la hora nunca se podía rotular.
                val (summary, paymentMethods, hourlySales) = resumenResult.getOrThrow()

                val report = ReportData(
                    summary = summary ?: SalesSummaryReport(),
                    paymentMethods = paymentMethods,
                    hourlySales = hourlySales,
                    periodSales = periodSalesResult.getOrThrow(),
                    topProducts = topProductsResult.getOrThrow(),
                    categories = categoriesResult.getOrThrow(),
                )

                _informe.value = InformeConsultado(consulta, report)
                _ultimoFallo.value = null
                Log.d(TAG, "✅ loadReport complete")
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ loadReport exception: ${e.message}", e)
            // Datos previos INTACTOS (spec §6).
            _ultimoFallo.value = FalloDeLectura.de(e)
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
