package com.avoqado.pos.onlineorders

import com.avoqado.pos.onlineorders.data.ImpresionDePedidoEnLinea
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.data.ResultadoImpresion
import com.avoqado.pos.onlineorders.data.lineasDeComanda
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderItem
import com.avoqado.pos.onlineorders.domain.OnlineOrderModifier
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.routing.CategoryRoute
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintRoutingMapper
import com.avoqado.pos.printing.routing.ProductOverride
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.printing.routing.StationInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task A4: la comanda del pedido en línea sale UNA vez, con reclamo, y sólo se confirma si el papel salió. */
class ImpresionDePedidoEnLineaTest {

    private val repo = mockk<OnlineOrdersRepository>()

    private class Despacho(var estado: EstadoDeComanda? = EstadoDeComanda.Salio) {
        var llamadas = 0
        var venue: String? = null
        var folio: String? = null
        var tipo: String? = null
        var orderId: String? = null
        var lineas: List<RoutableItem> = emptyList()
        var compuerta: CompletableDeferred<Unit>? = null
    }

    private fun armar(despacho: Despacho = Despacho(), hayImpresora: Boolean = true) = ImpresionDePedidoEnLinea(
        repositorio = repo,
        hayImpresoraDeCocina = { hayImpresora },
        venueId = { "v1" },
        despachar = { venue, lineas, folio, tipo, orderId ->
            despacho.llamadas++
            despacho.venue = venue; despacho.lineas = lineas; despacho.folio = folio; despacho.tipo = tipo; despacho.orderId = orderId
            despacho.compuerta?.await()
            despacho.estado
        },
    )

    private fun pedido(items: List<OnlineOrderItem> = listOf(renglon("Taco", 2))) = OnlineOrder(
        orderId = "p1", onlineOrderId = "o-p1", orderNumber = "W-17", status = OnlineOrderStatus.ACCEPTED,
        paymentMode = "PAY_AT_PICKUP", paymentStatus = "PENDING", customerName = "Ana", customerPhone = "5512345678", note = null,
        createdAt = Instant.parse("2026-10-22T10:00:00Z"), acceptDeadlineAt = null, estimatedReadyAt = null,
        totalCents = 20000, remainingCents = 20000, needsPrint = true, items = items,
    )

    private fun renglon(
        nombre: String,
        cantidad: Int,
        productId: String? = "prod-1",
        categoryId: String? = "cat-1",
        nota: String? = null,
        mods: List<OnlineOrderModifier> = emptyList(),
    ) = OnlineOrderItem(productId, categoryId, nombre, cantidad, nota, mods)

    @Test
    fun `claims, prints and confirms once`() = runTest {
        coEvery { repo.claimPrint("p1", "dev-1") } returns true
        coEvery { repo.confirmPrint("p1", "dev-1") } returns true
        val despacho = Despacho()
        val resultado = armar(despacho).imprimirSiToca(pedido(), "dev-1")
        assertEquals(ResultadoImpresion.Impresa, resultado)
        assertEquals(1, despacho.llamadas)
        assertEquals("W-17", despacho.folio)
        assertEquals("v1", despacho.venue)
        assertEquals("p1", despacho.orderId)
        coVerify(exactly = 1) { repo.claimPrint("p1", "dev-1") }
        coVerify(exactly = 1) { repo.confirmPrint("p1", "dev-1") }
    }

    @Test
    fun `lost claim prints nothing`() = runTest {
        coEvery { repo.claimPrint("p1", "dev-1") } returns false
        val despacho = Despacho()
        val resultado = armar(despacho).imprimirSiToca(pedido(), "dev-1")
        assertEquals(ResultadoImpresion.OtroAparato, resultado)
        assertEquals(0, despacho.llamadas)
        coVerify(exactly = 0) { repo.confirmPrint(any(), any()) }
    }

    @Test
    fun `printer failure does not confirm`() = runTest {
        coEvery { repo.claimPrint("p1", "dev-1") } returns true
        for (estado in listOf<EstadoDeComanda?>(EstadoDeComanda.NoSalio(listOf("Cocina"), "sin papel", "W-17"), null)) {
            val resultado = armar(Despacho(estado)).imprimirSiToca(pedido(), "dev-1")
            assertEquals(ResultadoImpresion.NoSalio, resultado)
        }
        coVerify(exactly = 0) { repo.confirmPrint(any(), any()) }
    }

    @Test
    fun `ticket says Para recoger and never Delivery`() = runTest {
        coEvery { repo.claimPrint(any(), any()) } returns true
        coEvery { repo.confirmPrint(any(), any()) } returns true
        val despacho = Despacho()
        armar(despacho).imprimirSiToca(pedido(), "dev-1")
        assertEquals("Para recoger", despacho.tipo)
        assertFalse(despacho.tipo!!.contains("Delivery", ignoreCase = true))
        // Lo que el cliente pidió llega a la comanda con su cantidad, notas y modificadores.
        val r = armar(despacho)
        r.imprimirSiToca(
            pedido(listOf(renglon("Burger", 3, nota = "sin cebolla", mods = listOf(OnlineOrderModifier("Queso", 1), OnlineOrderModifier("Tocino", 2))))),
            "dev-1",
        )
        val linea = despacho.lineas.single()
        assertEquals("Burger", linea.productName)
        assertEquals(3, linea.quantity)
        assertEquals("sin cebolla", linea.notes)
        assertEquals(listOf("Queso", "2 × Tocino"), linea.modifiers)
    }

    @Test
    fun `routes by productId and categoryId and falls back to default station`() {
        val config = PrintConfig(
            stations = listOf(StationInfo(id = "cocina", name = "Cocina", isDefault = true), StationInfo(id = "barra", name = "Barra"), StationInfo(id = "postres", name = "Postres")),
            defaultStationId = "cocina",
            categoryRouting = listOf(CategoryRoute("cat-bebidas", "barra")),
            productOverrides = listOf(ProductOverride("prod-flan", "postres")),
        )
        val lineas = lineasDeComanda(
            pedido(
                listOf(
                    renglon("Agua", 1, productId = "prod-agua", categoryId = "cat-bebidas"),
                    renglon("Flan", 1, productId = "prod-flan", categoryId = "cat-bebidas"),
                    renglon("Taco", 2, productId = "prod-taco", categoryId = "cat-tacos"),
                    renglon("Viejo", 1, productId = null, categoryId = null),
                ),
            ),
        )
        val planes = PrintRoutingMapper.buildComandas(lineas, config).associate { it.stationId to it.lines.map { l -> l.productName }.toSet() }
        assertEquals(setOf("Agua"), planes["barra"])
        assertEquals(setOf("Flan"), planes["postres"])
        // Un producto de otra categoría y uno SIN ids (servidor viejo) caen en la estación por defecto.
        assertEquals(setOf("Taco", "Viejo"), planes["cocina"])
    }

    @Test
    fun `no kitchen printer means no claim`() = runTest {
        val despacho = Despacho()
        val resultado = armar(despacho, hayImpresora = false).imprimirSiToca(pedido(), "dev-1")
        assertEquals(ResultadoImpresion.NoSalio, resultado)
        assertEquals(0, despacho.llamadas)
        coVerify(exactly = 0) { repo.claimPrint(any(), any()) }
    }

    @Test
    fun `same order never prints twice at once from the same device`() = runTest {
        coEvery { repo.claimPrint(any(), any()) } returns true
        coEvery { repo.confirmPrint(any(), any()) } returns true
        val despacho = Despacho().apply { compuerta = CompletableDeferred() }
        val impresion = armar(despacho)
        val primera = async { impresion.imprimirSiToca(pedido(), "dev-1") }
        testScheduler.runCurrent()
        val segunda = impresion.imprimirSiToca(pedido(), "dev-1")
        assertEquals(ResultadoImpresion.YaEnCurso, segunda)
        despacho.compuerta!!.complete(Unit)
        assertEquals(ResultadoImpresion.Impresa, primera.await())
        coVerify(exactly = 1) { repo.claimPrint("p1", "dev-1") }
        assertEquals(1, despacho.llamadas)
        // Terminada la primera, el pedido se puede volver a intentar (p. ej. el servidor lo pide otra vez).
        assertTrue(impresion.imprimirSiToca(pedido(), "dev-1") != ResultadoImpresion.YaEnCurso)
    }

    @Test
    fun `reprint does not claim and does not confirm`() = runTest {
        val despacho = Despacho()
        val resultado = armar(despacho).reimprimir(pedido())
        assertEquals(ResultadoImpresion.Impresa, resultado)
        assertEquals(1, despacho.llamadas)
        assertEquals("Para recoger", despacho.tipo)
        coVerify(exactly = 0) { repo.claimPrint(any(), any()) }
        coVerify(exactly = 0) { repo.confirmPrint(any(), any()) }
    }

    @Test
    fun `reprint reports failure when the printer does not answer or there is none`() = runTest {
        assertEquals(ResultadoImpresion.NoSalio, armar(Despacho(null)).reimprimir(pedido()))
        val sin = Despacho()
        assertEquals(ResultadoImpresion.NoSalio, armar(sin, hayImpresora = false).reimprimir(pedido()))
        assertEquals(0, sin.llamadas)
    }
}
