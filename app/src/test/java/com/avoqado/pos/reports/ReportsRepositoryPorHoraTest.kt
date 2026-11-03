package com.avoqado.pos.reports

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reports.data.ReportsRepository
import com.avoqado.pos.reports.data.model.ConsultaDeInforme
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D6 (2-nov): «Ventas por hora» salía de la serie del periodo de HOY, que sólo trae las horas con venta y como instantes ISO
 * («2026-11-02T21:00:00.000Z»): la etiqueta de la hora nunca se podía leer y las barras no eran las horas del día. Como iOS, sale
 * de las 24 horas del día que el resumen ya trae (`reportType=hourlySum`, en la hora del negocio y con ceros).
 */
class ReportsRepositoryPorHoraTest {
    private lateinit var repository: ReportsRepository

    private val resumenConHoras: String = run {
        val horas = (0 until 24).joinToString(",") { h ->
            val venta = when (h) { 9 -> 100.5; 15 -> 50.25; else -> 0.0 }
            """{"period":"${h.toString().padStart(2, '0')}:00","periodLabel":"$h:00","metrics":{"grossSales":$venta,"transactionCount":${if (venta > 0) 1 else 0}}}"""
        }
        """{"success":true,"data":{"summary":{"grossSales":150.75,"transactionCount":2},"byPaymentMethod":[],"byPeriod":[$horas]}}"""
    }

    // La serie de HOY (reportType=hours): sólo las horas con venta, como instantes.
    private val serieDeHoy = """{"success":true,"data":{"summary":{"grossSales":150.75},"byPeriod":[""" +
        """{"period":"2026-11-02T15:00:00.000Z","periodLabel":"2 nov, 09:00 a.m.","metrics":{"grossSales":100.5,"transactionCount":1}},""" +
        """{"period":"2026-11-02T21:00:00.000Z","periodLabel":"2 nov, 03:00 p.m.","metrics":{"grossSales":50.25,"transactionCount":1}}]}}"""

    private var resumen = ""

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        val storage = mockk<SecureStorage>()
        every { storage.venueId } returns "venue-prueba"
        resumen = resumenConHoras
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val body = when {
                url.encodedPath.endsWith("/reports/sales-by-item") -> """{"success":true,"data":{"items":[]}}"""
                url.queryParameter("reportType") == "hourlySum" -> resumen
                else -> serieDeHoy
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        repository = ReportsRepository(storage, client)
    }

    @After
    fun teardown() { unmockkStatic(Log::class) }

    @Test
    fun `ventas por hora son las 24 horas del dia que trae el resumen`() = runBlocking {
        assertTrue(repository.loadReport(ConsultaDeInforme("venue-prueba", "2026-11-02", "2026-11-02", "hours"), "2026-11-02").isSuccess)

        val horas = repository.informe.value!!.datos.hourlySales
        assertEquals(24, horas.size)
        assertEquals((0 until 24).map { it.toString().padStart(2, '0') + ":00" }, horas.map { it.hour })
        assertEquals(100.5, horas[9].grossSales, 0.001)
        assertEquals(50.25, horas[15].grossSales, 0.001)
        assertEquals(0.0, horas[10].grossSales, 0.001)
        assertEquals(1, horas[9].transactionCount)
    }

    @Test
    fun `la serie del periodo de hoy se queda en periodSales`() = runBlocking {
        assertTrue(repository.loadReport(ConsultaDeInforme("venue-prueba", "2026-11-02", "2026-11-02", "hours"), "2026-11-02").isSuccess)

        val periodo = repository.informe.value!!.datos.periodSales
        assertEquals(listOf("2026-11-02T15:00:00.000Z", "2026-11-02T21:00:00.000Z"), periodo.map { it.period })
    }

    @Test
    fun `un resumen sin byPeriod deja ventas por hora vacio sin tumbar el reporte`() = runBlocking {
        resumen = """{"success":true,"data":{"summary":{"grossSales":150.75},"byPaymentMethod":[]}}"""

        assertTrue(repository.loadReport(ConsultaDeInforme("venue-prueba", "2026-11-02", "2026-11-02", "hours"), "2026-11-02").isSuccess)

        val reporte = repository.informe.value!!.datos
        assertTrue(reporte.hourlySales.isEmpty())
        assertEquals(150.75, reporte.summary.grossSales, 0.001)
    }

    @Test
    fun `una hora filtrada sin grossSales vale cero`() = runBlocking {
        resumen = """{"success":true,"data":{"summary":{"grossSales":0},"byPeriod":[{"period":"08:00","metrics":{"grossSales":null}}]}}"""

        assertTrue(repository.loadReport(ConsultaDeInforme("venue-prueba", "2026-11-02", "2026-11-02", "hours"), "2026-11-02").isSuccess)

        val hora = repository.informe.value!!.datos.hourlySales.single()
        assertEquals("08:00", hora.hour)
        assertEquals(0.0, hora.grossSales, 0.001)
    }
}
