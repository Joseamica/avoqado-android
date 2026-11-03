package com.avoqado.pos.reports

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.data.network.SesionSinRenovar
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * D18 (3-nov, Sunmi OrderPAD 3 con el WiFi apagado): Informes decía «No hay datos para este periodo» sin red. Cada
 * sub-lectura devolvía `null` ante CUALQUIER fallo: no se distinguía «nadie contestó» de «el servidor contestó mal».
 */
class ReportsRepositoryFalloTest {
    private lateinit var repository: ReportsRepository

    /** Qué contesta la «red» para cada ruta: un cuerpo con su código, o una excepción. */
    private var responder: (ruta: String) -> Pair<Int, String> = { ruta -> 200 to valido(ruta) }

    /** Lo que se vio de `isLoading` mientras iba la consulta (las cuatro lecturas van en paralelo). */
    private val cargandoVisto = java.util.concurrent.CopyOnWriteArrayList<Boolean>()

    private val hoy = ConsultaDeInforme("venue-prueba", "2026-11-03T06:00:00.000Z", null, "hours")
    private val ahora = "2026-11-03T18:00:00.000Z"

    private fun valido(ruta: String): String = if (ruta.endsWith("/reports/sales-by-item")) {
        """{"success":true,"data":{"items":[]}}"""
    } else {
        """{"success":true,"data":{"summary":{"grossSales":75.25},"byPaymentMethod":[],"byPeriod":[]}}"""
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        val storage = mockk<SecureStorage>()
        every { storage.venueId } returns "venue-prueba"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            cargandoVisto += repository.isLoading.value
            val (codigo, cuerpo) = responder(chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(codigo).message("prueba")
                .body(cuerpo.toResponseBody("application/json".toMediaType())).build()
        }.build()
        repository = ReportsRepository(storage, client)
    }

    @After
    fun teardown() { unmockkStatic(Log::class) }

    private fun cargar() = runBlocking { repository.loadReport(hoy, ahora) }

    @Test
    fun `sin red es SIN_RED y el informe bueno se queda`() {
        assertTrue(cargar().isSuccess)
        val bueno = repository.informe.value

        responder = { throw IOException("sin red de prueba") }
        assertTrue(cargar().isFailure)

        assertEquals(FalloDeLectura.SIN_RED, repository.ultimoFallo.value)
        assertSame(bueno, repository.informe.value)
    }

    @Test
    fun `el servidor contesta 500 es SERVIDOR`() {
        responder = { 500 to """{"success":false,"error":"boom"}""" }

        assertTrue(cargar().isFailure)

        assertEquals(FalloDeLectura.SERVIDOR, repository.ultimoFallo.value)
        assertNull(repository.informe.value)
    }

    @Test
    fun `un 200 que no se puede leer es SERVIDOR`() {
        responder = { 200 to "<html>portal cautivo</html>" }

        assertTrue(cargar().isFailure)

        assertEquals(FalloDeLectura.SERVIDOR, repository.ultimoFallo.value)
    }

    @Test
    fun `si una parte no contesto y otra contesto mal, es SIN_RED`() {
        responder = { ruta ->
            when {
                ruta.endsWith("/reports/sales-by-item") -> throw IOException("tiempo agotado de prueba")
                else -> 503 to "{}"
            }
        }

        assertTrue(cargar().isFailure)

        assertEquals(FalloDeLectura.SIN_RED, repository.ultimoFallo.value)
    }

    @Test
    fun `la sesion que no se renovo porque el servidor tropezo es SERVIDOR`() {
        responder = { throw SesionSinRenovar("servidor:502") }

        assertTrue(cargar().isFailure)

        assertEquals(FalloDeLectura.SERVIDOR, repository.ultimoFallo.value)
    }

    @Test
    fun `una carga buena borra el fallo y guarda la consulta que la trajo`() {
        responder = { throw IOException("sin red de prueba") }
        assertTrue(cargar().isFailure)
        assertEquals(FalloDeLectura.SIN_RED, repository.ultimoFallo.value)

        responder = { ruta -> 200 to valido(ruta) }
        assertTrue(cargar().isSuccess)

        assertNull(repository.ultimoFallo.value)
        assertEquals(hoy, repository.informe.value?.consulta)
        assertEquals(75.25, repository.informe.value!!.datos.summary.grossSales, 0.001)
    }

    @Test
    fun `mientras va la consulta dice que carga, aunque ya haya un informe`() {
        assertTrue(cargar().isSuccess)
        cargandoVisto.clear()

        assertTrue(cargar().isSuccess)

        assertTrue("isLoading visto: $cargandoVisto", cargandoVisto.isNotEmpty() && cargandoVisto.all { it })
        assertEquals(false, repository.isLoading.value)
    }
}
