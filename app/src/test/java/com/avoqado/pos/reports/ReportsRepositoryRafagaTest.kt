package com.avoqado.pos.reports

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * D24: las 4 consultas de UNA carga del informe viajan con la misma marca de ráfaga, así que comparten UN PIN de gerente
 * si el servidor las rechaza por permiso. Cada carga (también «Reintentar») trae una marca nueva.
 */
class ReportsRepositoryRafagaTest {
    private lateinit var repository: ReportsRepository
    private val marcas = CopyOnWriteArrayList<String?>()
    private val hoy = ConsultaDeInforme("venue-prueba", "2026-11-03T06:00:00.000Z", null, "hours")

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        val storage = mockk<SecureStorage>()
        every { storage.venueId } returns "venue-prueba"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            marcas += chain.request().header(ForbiddenInterceptor.RAFAGA_HEADER)
            val cuerpo = if (chain.request().url.encodedPath.endsWith("/reports/sales-by-item")) {
                """{"success":true,"data":{"items":[]}}"""
            } else {
                """{"success":true,"data":{"summary":{"grossSales":1},"byPaymentMethod":[],"byPeriod":[]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("prueba")
                .body(cuerpo.toResponseBody("application/json".toMediaType())).build()
        }.build()
        repository = ReportsRepository(storage, client)
    }

    @After fun teardown() { unmockkStatic(Log::class) }

    @Test fun `las cuatro consultas de una carga llevan la misma marca y la siguiente carga otra`() = runBlocking<Unit> {
        assertTrue(repository.loadReport(hoy, "2026-11-03T18:00:00.000Z").isSuccess)
        val primera = marcas.toList()
        assertEquals(4, primera.size)
        assertEquals("una sola marca para la carga", 1, primera.toSet().size)
        assertTrue("la marca no va vacía", !primera.first().isNullOrBlank())

        marcas.clear()
        assertTrue(repository.loadReport(hoy, "2026-11-03T18:00:00.000Z").isSuccess)
        val segunda = marcas.toList()
        assertEquals(1, segunda.toSet().size)
        assertNotEquals("«Reintentar» es otra carga", primera.first(), segunda.first())
    }
}
