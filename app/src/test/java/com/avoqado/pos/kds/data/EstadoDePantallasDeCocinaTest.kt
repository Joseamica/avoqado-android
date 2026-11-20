package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.lan.RachaSinAcuse
import com.avoqado.pos.core.data.lan.TransporteLan
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.StationInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * D6 de Flautas (20-nov, opción B): qué pantallas de cocina NO están conectadas, según el servidor y los acuses recientes
 * por WiFi. Ante cualquier duda contesta «no se sabe» (`null`): la franja nunca afirma algo que no se comprobó.
 */
class EstadoDePantallasDeCocinaTest {
    private val api = mockk<ApiService>()
    private val config = mockk<PrintConfigRepository>()
    private val racha = RachaSinAcuse()
    private val transporte = mockk<TransporteLan> { every { racha } returns this@EstadoDePantallasDeCocinaTest.racha }
    private val estado = EstadoDePantallasDeCocina(api, config, transporte)

    private fun estaciones(vararg s: StationInfo) = coEvery { config.savedConfig("venue-1") } returns PrintConfig(stations = s.toList())
    private val cocina = StationInfo(id = "st_cocina", name = "COCINA 1", active = true, hasKitchenDisplay = true)
    private val barra = StationInfo(id = "st_barra", name = "Barra", printerId = "pr_1", active = true, hasKitchenDisplay = true)
    private val caja = StationInfo(id = "st_caja", name = "Caja", printerId = "pr_2", active = true)
    private fun contesta(conectadas: List<String>, completo: Boolean = true) =
        coEvery { api.pantallasConectadas("venue-1") } returns PantallasConectadasResponse(data = PantallasConectadasData(conectadas, completo))

    @Test
    fun `las estaciones con pantalla que el servidor no ve son las desconectadas, con la config de ESA sucursal`() = runTest {
        estaciones(cocina, barra, caja)
        contesta(listOf("st_barra"))

        assertEquals(setOf("st_cocina"), estado.revisar("venue-1"))
    }

    @Test
    fun `Codex r6 #1 un acuse por WiFi reciente cuenta como recibiendo, uno viejo no`() = runTest {
        estaciones(cocina)
        contesta(emptyList())
        val ahora = 10 * 60_000L
        racha.registrar(setOf("st_cocina"), setOf("st_cocina"), ahora = ahora - 60_000)

        assertEquals(emptySet<String>(), estado.revisar("venue-1", ahora))
        assertEquals(setOf("st_cocina"), estado.revisar("venue-1", ahora + EstadoDePantallasDeCocina.ACUSE_WIFI_VIGENTE_MS))
    }

    @Test
    fun `sin estaciones con pantalla ni pregunta`() = runTest {
        estaciones(caja)

        assertEquals(emptySet<String>(), estado.revisar("venue-1"))
        coVerify(exactly = 0) { api.pantallasConectadas(any()) }
    }

    @Test
    fun `Codex r5 #4 recien arrancado el servidor no se sabe`() = runTest {
        estaciones(cocina)
        contesta(emptyList(), completo = false)

        assertNull(estado.revisar("venue-1"))
    }

    @Test
    fun `sin red, con error, sin permiso o con un servidor viejo no se sabe`() = runTest {
        estaciones(cocina)
        coEvery { api.pantallasConectadas(any()) } throws IOException("sin red")

        assertNull(estado.revisar("venue-1"))
    }

    @Test
    fun `si el servidor tarda mas del tope no se sabe`() = runTest {
        estaciones(cocina)
        coEvery { api.pantallasConectadas(any()) } coAnswers {
            delay(EstadoDePantallasDeCocina.TOPE_MS + 1)
            PantallasConectadasResponse(data = PantallasConectadasData(emptyList(), true))
        }

        assertNull(estado.revisar("venue-1"))
    }
}
