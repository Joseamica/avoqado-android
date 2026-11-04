package com.avoqado.pos.reports

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.CargaDeFondo
import com.avoqado.pos.core.data.network.ClaseDeFallo
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.data.network.LecturaFallida
import com.avoqado.pos.core.data.network.laAutomaticaReintenta
import com.avoqado.pos.reports.data.ReportsRepository
import com.avoqado.pos.reports.data.model.ConsultaDeInforme
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** D22 (diseño r2 §C): las cuatro consultas de Informes guardan cada error y la recarga automática va marcada de fondo. */
class ReportsRepositoryRecargaSolaTest {
    private lateinit var repository: ReportsRepository
    private var responder: (ruta: String, query: String) -> Pair<Int, String> = { ruta, _ -> 200 to valido(ruta) }
    private val marcas = CopyOnWriteArrayList<String?>()
    private val hoy = ConsultaDeInforme("venue-prueba", "2026-11-03T06:00:00.000Z", null, "hours")
    private val nuestro403 = """{"error":"Forbidden","message":"Permission 'reports:read' required"}"""

    private fun valido(ruta: String): String = if (ruta.endsWith("/reports/sales-by-item")) {
        """{"success":true,"data":{"items":[]}}"""
    } else {
        """{"success":true,"data":{"summary":{"grossSales":75.25},"byPaymentMethod":[],"byPeriod":[]}}"""
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        val storage = mockk<SecureStorage>()
        every { storage.venueId } returns "venue-prueba"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            marcas += chain.request().header(ForbiddenInterceptor.BACKGROUND_HEADER)
            val (codigo, cuerpo) = responder(chain.request().url.encodedPath, chain.request().url.query.orEmpty())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(codigo).message("prueba")
                .body(cuerpo.toResponseBody("application/json".toMediaType())).build()
        }.build()
        repository = ReportsRepository(storage, client)
    }

    @After fun teardown() { unmockkStatic(Log::class) }

    private fun clasesDe(resultado: Result<Unit>) = (resultado.exceptionOrNull() as LecturaFallida).clases

    @Test fun `r1-6 - cuatro 429 reintentan`() = runBlocking {
        responder = { _, _ -> 429 to "" }
        val clases = clasesDe(repository.loadReport(hoy, "2026-11-03T18:00:00.000Z"))
        assertEquals(4, clases.size)
        assertTrue(clases.laAutomaticaReintenta())
    }

    @Test fun `r1-6 - un 403 propio con un 5xx se detiene`() = runBlocking {
        responder = { ruta, query -> if (ruta.endsWith("/reports/sales-by-item") && "groupBy=category" in query) 403 to nuestro403 else 503 to "" }
        assertFalse(clasesDe(repository.loadReport(hoy, "2026-11-03T18:00:00.000Z")).laAutomaticaReintenta())
    }

    @Test fun `r1-6 - un 404 con un 5xx reintenta y cuatro 404 se detienen`() = runBlocking {
        responder = { ruta, _ -> if (ruta.endsWith("/reports/sales-by-item")) 404 to "" else 502 to "" }
        assertTrue(clasesDe(repository.loadReport(hoy, "2026-11-03T18:00:00.000Z")).laAutomaticaReintenta())
        responder = { _, _ -> 404 to "" }
        val clases = clasesDe(repository.loadReport(hoy, "2026-11-03T18:00:00.000Z"))
        assertEquals(List(4) { ClaseDeFallo.DEFINITIVO }, clases)
    }

    @Test fun `la carga de fondo marca sus cuatro consultas y la del cajero ninguna`() = runBlocking {
        withContext(CargaDeFondo.enElContexto) { repository.loadReport(hoy, "2026-11-03T18:00:00.000Z") }
        assertEquals(List(4) { "1" }, marcas.toList())
        marcas.clear()
        repository.loadReport(hoy, "2026-11-03T18:00:00.000Z")
        assertEquals(List(4) { null }, marcas.toList())
    }
}
