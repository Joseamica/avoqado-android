package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.printing.data.AlmacenDeTexto
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** na13e014: el diario de comandas de ronda y su recuperación al abrir la app. */
class ComandasDeRondaPorDecidirTest {
    private class Disco : AlmacenDeTexto {
        var texto: String? = null
        override fun leer() = texto
        override fun escribir(texto: String): Boolean { this.texto = texto; return true }
        override fun borrar() { texto = null }
    }

    private val disco = Disco()
    private val diario = ComandasDeRondaPorDecidir(disco)
    private val despachador = mockk<ComandaDispatcher>(relaxed = true)
    private val libreta = mockk<ComandasPendientesStore>(relaxed = true)
    private var sucursal = "v-1"
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } answers { sucursal } }
    private val replay = ReplayDeRondasSinPapel(diario, despachador, libreta, storage)

    private val arranque = 1_000_000L
    private val cafe = PendingLine(CartItem(id = "c1", type = CartItemType.ProductItem("p-cafe"), name = "Café", unitPrice = 5000), null)
    private val importeLibre = PendingLine(CartItem(id = "x1", type = CartItemType.CustomAmount, name = "Varios", unitPrice = 1000), null)

    private fun ronda(llave: String, creada: Long = arranque - 1, venue: String = "v-1", lines: List<PendingLine> = listOf(cafe)) =
        ComandasDeRondaPorDecidir.Ronda(llave, venue, "LOCAL", null, "5", lines, null, creada)

    @Test fun `apuntar y quitar sobreviven a releer el disco`() {
        assertTrue(diario.apuntar(ronda("r1")))
        assertTrue(diario.apuntar(ronda("r2")))
        assertEquals(listOf("r1", "r2"), ComandasDeRondaPorDecidir(disco).todas().map { it.roundKey })
        diario.quitar("r1")
        assertEquals(listOf("r2"), ComandasDeRondaPorDecidir(disco).todas().map { it.roundKey })
        assertEquals(cafe, diario.todas().single().lines.single())
    }

    @Test fun `P1 la ronda de un proceso anterior sale una sola vez con su folio y se borra al decidirse`() {
        val termino = CompletableDeferred<Unit>()
        every { despachador.despacharEnFondo(any(), any(), any(), any(), any(), any(), any()) } returns termino
        diario.apuntar(ronda("r1"))

        replay.reproducirAlAbrir("v-1", ahora = arranque + 60_000, arranqueDelProceso = arranque)
        replay.reproducirAlAbrir("v-1", ahora = arranque + 61_000, arranqueDelProceso = arranque) // startOfflineOutbox corre 2-3 veces

        val pedidos = slot<List<ComandaDispatcher.Pedido>>()
        verify(exactly = 1) { despachador.despacharEnFondo("v-1", "LOCAL", capture(pedidos), null, false, "round:r1", any()) }
        assertEquals("Mesa 5", pedidos.captured.single().orderType)
        assertEquals(listOf("sync:r1:0"), pedidos.captured.single().lines.map { it.externalId })
        assertEquals("sigue en el diario mientras se decide", listOf("r1"), diario.todas().map { it.roundKey })
        termino.complete(Unit)
        assertTrue(diario.todas().isEmpty())
    }

    @Test fun `lo que no sale va a la libreta`() {
        val aviso = slot<(EstadoDeComanda) -> Unit>()
        every { despachador.despacharEnFondo(any(), any(), any(), any(), any(), any(), capture(aviso)) } returns Job()
        diario.apuntar(ronda("r1"))
        replay.reproducirAlAbrir("v-1", ahora = arranque, arranqueDelProceso = arranque)
        val noSalio = EstadoDeComanda.NoSalio(listOf("Cocina"), "apagada", "LOCAL", mockk(relaxed = true))
        aviso.captured(noSalio)
        verify(exactly = 1) { libreta.guardar(noSalio, any()) }
    }

    @Test fun `P1 una ronda de este proceso no se toca - su despacho vivo la decide`() {
        diario.apuntar(ronda("viva", creada = arranque + 5))
        replay.reproducirAlAbrir("v-1", ahora = arranque + 10, arranqueDelProceso = arranque)
        verify(exactly = 0) { despachador.despacharEnFondo(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(listOf("viva"), diario.todas().map { it.roundKey })
    }

    @Test fun `P1 la de otra sucursal espera a que vuelva y la de mas de 8 h se borra sin imprimir`() {
        diario.apuntar(ronda("de-b", venue = "v-2"))
        diario.apuntar(ronda("vieja", creada = arranque - 9L * 60 * 60 * 1000))
        replay.reproducirAlAbrir("v-1", ahora = arranque, arranqueDelProceso = arranque)
        verify(exactly = 0) { despachador.despacharEnFondo(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(listOf("de-b"), diario.todas().map { it.roundKey })

        sucursal = "v-2"
        replay.reproducirAlAbrir("v-2", ahora = arranque, arranqueDelProceso = arranque)
        verify(exactly = 1) { despachador.despacharEnFondo("v-2", any(), any(), any(), any(), "round:de-b", any()) }
    }

    @Test fun `una ronda sin productos de cocina no imprime y sale del diario`() {
        diario.apuntar(ronda("libre", lines = listOf(importeLibre)))
        replay.reproducirAlAbrir("v-1", ahora = arranque, arranqueDelProceso = arranque)
        verify(exactly = 0) { despachador.despacharEnFondo(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(diario.todas().isEmpty())
    }
}
