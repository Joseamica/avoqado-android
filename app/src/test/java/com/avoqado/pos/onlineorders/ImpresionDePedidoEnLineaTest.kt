package com.avoqado.pos.onlineorders

import com.avoqado.pos.onlineorders.data.ImpresionDePedidoEnLinea
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.data.ResultadoImpresion
import com.avoqado.pos.onlineorders.data.encabezadoDeComanda
import com.avoqado.pos.onlineorders.data.hayImpresoraDeCocina
import com.avoqado.pos.onlineorders.data.lineasDeComanda
import com.avoqado.pos.printing.data.model.MARCA_NEGRITA_ENCABEZADO
import com.avoqado.pos.printing.routing.PrinterInfo
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
import io.mockk.every
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
        var encabezado: List<String> = emptyList()
        var compuerta: CompletableDeferred<Unit>? = null
    }

    @Test fun `changing venue during claim never prints or confirms in the next venue`() = runTest {
        var venue = "v1"
        var prints = 0
        coEvery { repo.claimPrint("p1", "dev-1") } answers { venue = "v2"; true }
        coEvery { repo.confirmPrint("p1", "dev-1") } returns true
        val sut = ImpresionDePedidoEnLinea(repo, { true }, { venue }, { _, _, _, _, _, _ -> prints++; EstadoDeComanda.Salio })
        assertEquals(ResultadoImpresion.NoSalio, sut.imprimirSiToca(pedido(), "dev-1"))
        assertEquals(0, prints)
        coVerify(exactly = 0) { repo.confirmPrint(any(), any()) }
    }

    @Test fun `dispatch never adopts a new venue config after the context check`() = runTest {
        var venue = "v1"
        var configReads = 0
        val storage = mockk<com.avoqado.pos.core.data.local.SecureStorage>()
        val configRepo = mockk<com.avoqado.pos.printing.routing.PrintConfigRepository>()
        val printers = mockk<com.avoqado.pos.printing.data.PrinterService>()
        val dispatcher = mockk<com.avoqado.pos.core.domain.printing.ComandaDispatcher>(relaxed = true)
        val config = PrintConfig(printers = listOf(PrinterInfo("p", "Cocina", "NETWORK", "192.168.1.50:9100")),
            stations = listOf(StationInfo("s", "Cocina", printerId = "p")))
        every { storage.venueId } answers { venue }
        every { printers.savedPrinters } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        every { configRepo.getCurrentConfig() } answers { if (++configReads > 1) venue = "v2"; config }
        coEvery { configRepo.savedConfig("v1") } coAnswers { venue = "v2"; config }
        coEvery { repo.claimPrint("p1", "dev-1") } returns true
        coEvery { repo.confirmPrint("p1", "dev-1") } returns true
        coEvery { dispatcher.dispatch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns EstadoDeComanda.Salio
        val sut = ImpresionDePedidoEnLinea(repo, dispatcher, printers, configRepo, storage)
        assertEquals(ResultadoImpresion.NoSalio, sut.imprimirSiToca(pedido(), "dev-1"))
    }

    @Test fun `unreadable saved route reports no print instead of escaping the task`() = runTest {
        val storage = mockk<com.avoqado.pos.core.data.local.SecureStorage>()
        val configRepo = mockk<com.avoqado.pos.printing.routing.PrintConfigRepository>()
        val printers = mockk<com.avoqado.pos.printing.data.PrinterService>()
        val dispatcher = mockk<com.avoqado.pos.core.domain.printing.ComandaDispatcher>(relaxed = true)
        every { storage.venueId } returns "v1"
        every { printers.savedPrinters } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        every { configRepo.getCurrentConfig() } returns PrintConfig(
            printers = listOf(PrinterInfo("p", "Cocina", "NETWORK", "192.168.1.50:9100")),
            stations = listOf(StationInfo("s", "Cocina", printerId = "p")))
        coEvery { configRepo.savedConfig("v1") } throws IllegalStateException("caché ilegible")
        coEvery { repo.claimPrint("p1", "dev-1") } returns true
        val sut = ImpresionDePedidoEnLinea(repo, dispatcher, printers, configRepo, storage)
        assertEquals(ResultadoImpresion.NoSalio, sut.imprimirSiToca(pedido(), "dev-1"))
        coVerify(exactly = 0) { repo.confirmPrint(any(), any()) }
    }

    private var ahora = 1_000_000L

    private fun armar(despacho: Despacho = Despacho(), hayImpresora: Boolean = true) = ImpresionDePedidoEnLinea(
        repositorio = repo,
        hayImpresoraDeCocina = { hayImpresora },
        venueId = { "v1" },
        despachar = { venue, lineas, folio, tipo, orderId, encabezado ->
            despacho.llamadas++
            despacho.venue = venue; despacho.lineas = lineas; despacho.folio = folio; despacho.tipo = tipo; despacho.orderId = orderId; despacho.encabezado = encabezado
            despacho.compuerta?.await()
            despacho.estado
        },
        reloj = { ahora },
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
    fun `the web ticket carries customer, ready time, payment and note as header lines`() = runTest {
        coEvery { repo.claimPrint(any(), any()) } returns true
        coEvery { repo.confirmPrint(any(), any()) } returns true
        val despacho = Despacho()
        val base = pedido()
        val conTodo = base.copy(note = "Sin salsa", estimatedReadyAt = Instant.parse("2026-10-22T20:30:00Z"), remainingCents = 18550, totalCents = 18550)
        armar(despacho).imprimirSiToca(conTodo, "dev-1")
        val hora = com.avoqado.pos.core.util.VenueTimeZone.at(conTodo.estimatedReadyAt!!).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        assertEquals(
            listOf("Ana", "5512345678", "Listo a las $hora", "${MARCA_NEGRITA_ENCABEZADO}Cobrar al recoger \$185.50", "Nota: Sin salsa"),
            despacho.encabezado,
        )
        // Pagado, sin hora ni nota ni teléfono: sólo lo que hay.
        val pagado = base.copy(paymentStatus = "PAID", remainingCents = 0, customerPhone = null, estimatedReadyAt = null, note = " ")
        armar(despacho).imprimirSiToca(pagado, "dev-1")
        assertEquals(listOf("Ana", "${MARCA_NEGRITA_ENCABEZADO}Pagado"), despacho.encabezado)
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

    @Test
    fun `after a NoSalio this device does not claim that order again for 3 minutes`() = runTest {
        coEvery { repo.claimPrint(any(), any()) } returns true
        coEvery { repo.confirmPrint(any(), any()) } returns true
        val despacho = Despacho(EstadoDeComanda.NoSalio(listOf("Cocina"), "sin papel", "W-17"))
        val impresion = armar(despacho)
        assertEquals(ResultadoImpresion.NoSalio, impresion.imprimirSiToca(pedido(), "dev-1"))
        coVerify(exactly = 1) { repo.claimPrint("p1", "dev-1") }
        // Dentro de los 3 minutos: ni reclamo ni papel (otro aparato la puede tomar).
        ahora += 179_000
        assertEquals(ResultadoImpresion.NoSalio, impresion.imprimirSiToca(pedido(), "dev-1"))
        coVerify(exactly = 1) { repo.claimPrint("p1", "dev-1") }
        assertEquals(1, despacho.llamadas)
        // «Reimprimir» ignora el descanso.
        despacho.estado = EstadoDeComanda.Salio
        assertEquals(ResultadoImpresion.Impresa, impresion.reimprimir(pedido()))
        // Pasados los 3 minutos vuelve a reclamar.
        ahora += 2_000
        assertEquals(ResultadoImpresion.Impresa, impresion.imprimirSiToca(pedido(), "dev-1"))
        coVerify(exactly = 2) { repo.claimPrint("p1", "dev-1") }
    }

    @Test
    fun `a business printer that is only the receipt printer gives no claim`() {
        val recibo = PrinterInfo("pr_caja", "Caja", "NETWORK", "192.168.1.9:9100")
        val cocina = PrinterInfo("pr_cocina", "Cocina", "NETWORK", "192.168.1.50:9100")
        // Impresora activa sin estación: es la de recibos.
        assertFalse(hayImpresoraDeCocina(false, PrintConfig(printers = listOf(recibo))))
        // Con estación activa que la usa: es de cocina.
        assertTrue(hayImpresoraDeCocina(false, PrintConfig(printers = listOf(recibo, cocina), stations = listOf(StationInfo("st", "Cocina", printerId = "pr_cocina")))))
        // Estación apagada o impresora apagada: no cuenta.
        assertFalse(hayImpresoraDeCocina(false, PrintConfig(printers = listOf(cocina), stations = listOf(StationInfo("st", "Cocina", printerId = "pr_cocina", active = false)))))
        assertFalse(hayImpresoraDeCocina(false, PrintConfig(printers = listOf(cocina.copy(active = false)), stations = listOf(StationInfo("st", "Cocina", printerId = "pr_cocina")))))
        // Una cocina local del aparato basta.
        assertTrue(hayImpresoraDeCocina(true, PrintConfig()))
    }

    @Test
    fun `an order with no items is never claimed`() = runTest {
        val despacho = Despacho()
        val resultado = armar(despacho).imprimirSiToca(pedido(emptyList()), "dev-1")
        assertEquals(ResultadoImpresion.NoSalio, resultado)
        assertEquals(0, despacho.llamadas)
        coVerify(exactly = 0) { repo.claimPrint(any(), any()) }
    }

    @Test
    fun `the payment line of the header is marked bold and the others are not`() {
        val cobrar = encabezadoDeComanda(pedido().copy(remainingCents = 18550))
        assertTrue(cobrar.single { it.contains("Cobrar al recoger") }.startsWith(MARCA_NEGRITA_ENCABEZADO))
        assertEquals(listOf("Ana", "5512345678"), cobrar.filterNot { it.contains("Cobrar al recoger") })
        val pagado = encabezadoDeComanda(pedido().copy(paymentStatus = "PAID", remainingCents = 0))
        assertTrue(pagado.single { it.contains("Pagado") }.startsWith(MARCA_NEGRITA_ENCABEZADO))
    }
}
