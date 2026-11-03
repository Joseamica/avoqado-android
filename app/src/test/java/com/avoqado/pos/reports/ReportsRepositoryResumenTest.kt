package com.avoqado.pos.reports

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reports.data.ReportsRepository
import com.avoqado.pos.reports.data.model.SalesSummaryReport
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReportsRepositoryResumenTest {
    private var body = ""
    private var sinRed = false
    private lateinit var repository: ReportsRepository
    private val json = Json { encodeDefaults = true }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        val storage = mockk<SecureStorage>()
        every { storage.venueId } returns "venue-prueba"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (sinRed) throw IOException("sin red de prueba")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        repository = ReportsRepository(storage, client)
    }

    @After
    fun teardown() { unmockkStatic(Log::class) }

    private fun leer() = runBlocking { repository.fetchSalesSummary("2026-10-01", "2026-10-27") }
    private fun campos(summary: SalesSummaryReport) = json.encodeToJsonElement(SalesSummaryReport.serializer(), summary).jsonObject

    @Test
    fun `con salesByPaymentMethod la lista sale de ahi y trae GIFT_CARD`() {
        body = """{"success":true,"data":{"summary":{"grossSales":1000.5},"byPaymentMethod":[{"method":"CASH","amount":1500.75,"count":3,"percentage":100}],"salesByPaymentMethod":[{"method":"CASH","amount":600.25,"count":2,"percentage":60},{"method":"GIFT_CARD","amount":400.25,"count":1,"percentage":40}]}}"""
        val (summary, methods) = requireNotNull(leer())
        assertEquals(1000.5, summary!!.grossSales, 0.001)
        assertEquals(listOf("CASH", "GIFT_CARD"), methods.map { it.method })
        assertEquals(400.25, methods[1].amount, 0.001)
        assertEquals(1, methods[1].count)
        assertEquals(40.0, methods[1].percentage, 0.001)
    }

    @Test
    fun `sin salesByPaymentMethod sale de byPaymentMethod como hoy`() {
        body = """{"success":true,"data":{"summary":{"grossSales":75.25,"transactionCount":1},"byPaymentMethod":[{"method":"CASH","amount":75.25,"count":1,"percentage":100}]}}"""
        val (summary, methods) = requireNotNull(leer())
        assertEquals(75.25, summary!!.grossSales, 0.001)
        assertEquals(listOf("CASH"), methods.map { it.method })
        assertEquals(75.25, methods.single().amount, 0.001)
        for (field in listOf("giftCardLoads", "giftCardLoadsCount", "paidWithGiftCardBalance")) {
            assertEquals(JsonNull, campos(summary)[field] ?: JsonNull)
        }
    }

    @Test
    fun `giftCardLoads y giftCardLoadsCount se leen aparte y no suman a grossSales`() {
        body = """{"success":true,"data":{"summary":{"grossSales":1000.5,"netSales":950.25,"transactionCount":2,"giftCardLoads":300.25,"giftCardLoadsCount":3,"paidWithGiftCardBalance":200.1}}}"""
        val summary = requireNotNull(leer()?.first)
        assertEquals(1000.5, summary.grossSales, 0.001)
        assertEquals(950.25, summary.netSales, 0.001)
        assertEquals(475.125, summary.averageTicket, 0.001)
        assertEquals(JsonPrimitive(300.25), campos(summary)["giftCardLoads"])
        assertEquals(JsonPrimitive(3), campos(summary)["giftCardLoadsCount"])
        assertEquals(JsonPrimitive(200.1), campos(summary)["paidWithGiftCardBalance"])
    }

    @Test
    fun `salesByPaymentMethod vacio es autoritativo y no revive las cargas`() {
        body = """{"success":true,"data":{"summary":{"grossSales":0,"giftCardLoads":500,"giftCardLoadsCount":1},"salesByPaymentMethod":[],"byPaymentMethod":[{"method":"CASH","amount":500,"count":1,"percentage":100}]}}"""
        val (summary, methods) = requireNotNull(leer())
        assertEquals(0.0, summary!!.grossSales, 0.001)
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `campos nuevos null conservan compatibilidad y fallback`() {
        body = """{"success":true,"data":{"summary":{"grossSales":20,"giftCardLoads":null,"giftCardLoadsCount":null,"paidWithGiftCardBalance":null},"salesByPaymentMethod":null,"byPaymentMethod":[{"method":"CASH","amount":20}]}}"""
        val (summary, methods) = requireNotNull(leer())
        assertEquals(listOf("CASH"), methods.map { it.method })
        assertEquals(20.0, summary!!.grossSales, 0.001)
        for (field in listOf("giftCardLoads", "giftCardLoadsCount", "paidWithGiftCardBalance")) {
            assertEquals(JsonNull, campos(summary)[field] ?: JsonNull)
        }
    }

    @Test
    fun `sin summary es respuesta valida sin ventas como hoy`() {
        body = """{"success":true,"data":{}}"""
        val result = requireNotNull(leer())
        assertNull(result.first)
        assertTrue(result.second.isEmpty())
    }

    @Test
    fun `parse invalido y fallo de red siguen devolviendo fallo`() {
        for (invalid in listOf("", "no-json", "{}", "{\"data\":null}")) {
            body = invalid
            assertNull(leer())
        }
        sinRed = true
        assertNull(leer())
    }

    @Test
    fun `perder red conserva el reporte bueno sin inventar ceros`() = runBlocking {
        body = """{"success":true,"data":{"summary":{"grossSales":75.25},"byPaymentMethod":[{"method":"CASH","amount":75.25}],"byPeriod":[],"items":[]}}"""
        assertTrue(repository.loadReport("2026-10-01", "2026-10-27", "days").isSuccess)
        val bueno = repository.reportData.value
        assertEquals(75.25, bueno!!.summary.grossSales, 0.001)
        sinRed = true
        assertTrue(repository.loadReport("2026-10-01", "2026-10-27", "days").isFailure)
        assertSame(bueno, repository.reportData.value)
    }
}
