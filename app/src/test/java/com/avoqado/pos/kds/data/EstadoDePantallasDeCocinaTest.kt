package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * D6 de Flautas (20-nov, opción B simplificada): qué pantallas de cocina NO están conectadas a internet lo decide el
 * servidor; la caja sólo guarda los nombres. Ante cualquier duda no se afirma nada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EstadoDePantallasDeCocinaTest {
    private val api = mockk<ApiService>()
    private val estado = EstadoDePantallasDeCocina(api)

    private fun contesta(venue: String, vararg nombres: String, completo: Boolean = true, hayPantallas: Boolean = true) =
        coEvery { api.pantallasDesconectadas(venue) } returns PantallasDesconectadasResponse(
            data = PantallasDesconectadasData(hayPantallas, completo, nombres.mapIndexed { i, n -> PantallaDesconectada("st_$i", n) }),
        )

    @Test
    fun `guarda los nombres de las pantallas que el servidor da por desconectadas`() = runTest {
        contesta("venue-1", "COCINA 1", "Barra")

        assertEquals(EstadoDePantallasDeCocina.Revision(hayPantallas = true, nombres = listOf("COCINA 1", "Barra")), estado.revisar("venue-1"))
    }

    @Test
    fun `Codex r5 #4 recien arrancado el servidor (completo = false) no se da a ninguna por desconectada`() = runTest {
        contesta("venue-1", "COCINA 1", completo = false)

        assertEquals(emptyList<String>(), estado.revisar("venue-1")!!.nombres)
    }

    @Test
    fun `sin red, con error, sin permiso o con un servidor viejo no se sabe`() = runTest {
        coEvery { api.pantallasDesconectadas(any()) } throws IOException("sin red")

        assertNull(estado.revisar("venue-1"))
    }

    @Test
    fun `si el servidor tarda mas del tope no se sabe`() = runTest {
        coEvery { api.pantallasDesconectadas(any()) } coAnswers {
            delay(EstadoDePantallasDeCocina.TOPE_MS + 1)
            PantallasDesconectadasResponse(data = PantallasDesconectadasData(true, true, listOf(PantallaDesconectada("st", "COCINA 1"))))
        }

        assertNull(estado.revisar("venue-1"))
    }

    @Test
    fun `pregunta cada 20 s con pantallas, cada 5 min sin pantallas y al minuto tras un error`() {
        assertEquals(EstadoDePantallasDeCocina.INTERVALO_MS, estado.esperaTras(EstadoDePantallasDeCocina.Revision(true, emptyList())))
        assertEquals(EstadoDePantallasDeCocina.INTERVALO_SIN_PANTALLAS_MS, estado.esperaTras(EstadoDePantallasDeCocina.Revision(false, emptyList())))
        assertEquals(EstadoDePantallasDeCocina.ESPERA_SIN_RESPUESTA_MS, estado.esperaTras(null))
    }

    @Test
    fun `publica lo del servidor, lo limpia si deja de saberse y al cambiar de sucursal olvida lo de la anterior`() = runTest {
        contesta("venue-1", "COCINA 1")
        contesta("venue-2")

        estado.iniciar(backgroundScope, "venue-1")
        runCurrent()
        assertEquals(listOf("COCINA 1"), estado.sinInternet.value)

        coEvery { api.pantallasDesconectadas("venue-1") } throws IOException("sin red")
        advanceTimeBy(EstadoDePantallasDeCocina.INTERVALO_MS + 1)
        assertEquals("sin respuesta no se afirma nada", emptyList<String>(), estado.sinInternet.value)

        contesta("venue-1", "COCINA 1")
        advanceTimeBy(EstadoDePantallasDeCocina.ESPERA_SIN_RESPUESTA_MS + 1)
        assertEquals(listOf("COCINA 1"), estado.sinInternet.value)

        estado.iniciar(backgroundScope, "venue-2")
        assertEquals("al cambiar de sucursal se olvida en el acto", emptyList<String>(), estado.sinInternet.value)
        runCurrent()
        assertEquals(emptyList<String>(), estado.sinInternet.value)

        estado.detener()
        assertEquals(emptyList<String>(), estado.sinInternet.value)
    }
}
