package com.avoqado.pos.printing.data

import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * D6 de Flautas (20-nov): la caja le pregunta al SERVIDOR si una comanda ya está en la pantalla de cocina. Ante cualquier
 * duda contesta «ninguna», y la caja avisa «No salió» como siempre: callar de más esconde una comanda que nadie vio.
 */
class ConfirmacionDePantallaTest {
    private val api = mockk<ApiService>()
    private val confirmacion = ConfirmacionDePantalla(api)

    @Test
    fun `devuelve los folios que el servidor confirma, mandando sus unidades`() = runTest {
        coEvery { api.foliosEnPantalla("venue-1", FoliosEnPantallaRequest(listOf(ComandaPorConfirmar("round:k:st_cocina", 3)))) } returns
            FoliosEnPantallaResponse(data = FoliosEnPantallaData(enPantalla = listOf("round:k:st_cocina")))

        assertEquals(setOf("round:k:st_cocina"), confirmacion.enPantalla("venue-1", mapOf("round:k:st_cocina" to 3)))
    }

    @Test
    fun `sin red o con un error del servidor (o uno viejo que no conoce la ruta) contesta ninguna`() = runTest {
        coEvery { api.foliosEnPantalla(any(), any()) } throws IOException("sin red")

        assertEquals(emptySet<String>(), confirmacion.enPantalla("venue-1", mapOf("sale:e:st_cocina" to 1)))
    }

    @Test
    fun `si el servidor tarda mas del tope contesta ninguna`() = runTest {
        coEvery { api.foliosEnPantalla(any(), any()) } coAnswers {
            delay(ConfirmacionDePantalla.TOPE_MS + 1)
            FoliosEnPantallaResponse(data = FoliosEnPantallaData(enPantalla = listOf("sale:e:st_cocina")))
        }

        assertEquals(emptySet<String>(), confirmacion.enPantalla("venue-1", mapOf("sale:e:st_cocina" to 1)))
    }

    @Test
    fun `sin folios, con mas del tope o con unidades en cero ni pregunta`() = runTest {
        assertEquals(emptySet<String>(), confirmacion.enPantalla("venue-1", emptyMap()))
        assertEquals(emptySet<String>(), confirmacion.enPantalla("venue-1", (0..ConfirmacionDePantalla.MAX_FOLIOS).associate { "sale:e:st$it" to 1 }))
        assertEquals(emptySet<String>(), confirmacion.enPantalla("venue-1", mapOf("sale:e:st_cocina" to 0)))
        coVerify(exactly = 0) { api.foliosEnPantalla(any(), any()) }
    }
}
