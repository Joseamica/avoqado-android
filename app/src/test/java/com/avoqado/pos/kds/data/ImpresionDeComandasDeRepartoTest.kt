package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.domain.ImpresionReparto
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderItem
import com.avoqado.pos.kds.domain.KDSOrderStatus
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.TrabajoPendiente
import com.avoqado.pos.printing.routing.CategoryRoute
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.printing.routing.StationInfo
import io.mockk.MockKMatcherScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

/**
 * La pieza que imprime las comandas de Rappi/Uber sin importar en qué pantalla esté el aparato. Lo que se prueba es la
 * tabla de decisión: qué se le dice al servidor (`confirm-print` / `release-print`) y qué se apunta en la pizarra [AvisosDeReparto].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImpresionDeComandasDeRepartoTest {

    private val repo = mockk<KDSRepository>(relaxed = true)
    private val dispatcher = mockk<ComandaDispatcher>(relaxed = true)
    private val printConfig = mockk<PrintConfigRepository>(relaxed = true)
    private val cola = mockk<SyncOutbox>(relaxed = true)
    private var config = PrintConfig()
    private val pizarra = AvisosDeReparto()

    /** Una llamada a `dispatch` (la comanda): sólo lo que decide este reparto. */
    private data class Despacho(val lineas: List<String>, val config: PrintConfig?, val omitir: Boolean, val fija: String?, val callar: Boolean?)

    /** Un ticket por `reintentar` (el de empaque): la estación, su encabezado, sus renglones y la config con que sale. */
    private data class Ticket(val estacion: String?, val encabezado: String, val lineas: List<String>, val config: PrintConfig, val configFija: PrintConfig?)

    /** Lo que se mandó a imprimir, en el orden en que salió: «comanda» o «empaque». */
    private val orden = mutableListOf<String>()
    private val despachos = mutableListOf<Despacho>()
    private val tickets = mutableListOf<Ticket>()
    private var estadoComanda: (Despacho) -> EstadoDeComanda? = { EstadoDeComanda.Salio }
    private var estadoEmpaque: (Ticket) -> EstadoDeComanda = { EstadoDeComanda.Salio }

    /** `dispatch` con TODOS sus argumentos en `any()`: un default sin matcher se registra como `eq` y el stub no casaría. */
    private suspend fun MockKMatcherScope.despacho() = dispatcher.dispatch(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any(), any(), any(),
    )

    private suspend fun MockKMatcherScope.ticket() = dispatcher.reintentar(any(), any(), any(), any())

    private fun armar(deviceId: String? = "dev-1"): ImpresionDeComandasDeReparto {
        every { repo.venueIdActual() } returns "v1"
        if (deviceId != null) every { cola.deviceId } returns deviceId else every { cola.deviceId } throws IllegalStateException("sin id")
        every { printConfig.getCurrentConfig() } answers { config }
        coEvery { repo.reclamarImpresion(any(), any()) } returns true
        coEvery { despacho() } coAnswers {
            val d = Despacho(arg<List<RoutableItem>>(1).map { it.productName }, arg(11), arg(16), arg(17), arg(18))
            orden += "comanda"
            despachos += d
            estadoComanda(d)
        }
        coEvery { ticket() } coAnswers {
            val t = firstArg<TrabajoPendiente>()
            val plan = t.planes.single()
            val k = Ticket(plan.stationId, t.orderType, plan.lines.map { it.productName }, t.config, arg(3))
            orden += "empaque"
            tickets += k
            estadoEmpaque(k)
        }
        return ImpresionDeComandasDeReparto(repo, dispatcher, printConfig, Provider { cola }, pizarra)
    }

    private val taco = KDSOrderItem(id = "i-taco", productName = "Taco", quantity = 1, categoryId = "cat-cocina")
    /** De una estación «Sin comanda» (la tienda): no va a cocina, pero sí a la bolsa. */
    private val refresco = KDSOrderItem(id = "i-ref", productName = "Refresco", quantity = 1, categoryId = "cat-tienda")

    private fun pedido(
        id: String,
        needsPrint: Boolean = true,
        needsAcceptance: Boolean = false,
        impresion: ImpresionReparto? = null,
        items: List<KDSOrderItem> = listOf(KDSOrderItem(id = "i-$id", productName = "Taco", quantity = 1)),
    ) = KDSOrder(
        id = id, orderId = "o-$id", orderNumber = "N-$id", orderType = "Delivery", needsPrint = needsPrint,
        needsAcceptance = needsAcceptance, items = items, createdAt = 1_000, status = KDSOrderStatus.NEW,
        impresionReparto = impresion,
    )

    private val trabajo = mockk<TrabajoPendiente>(relaxed = true)

    private fun conEmpaque() { config = PrintConfig(packingStationId = "st-empaque") }

    /** Un negocio con cocina, barra, caja, empaque, una tienda «Sin comanda» y una estación apagada. */
    private fun negocio(packing: String? = "st-empaque", impresora: String = "imp-A") = PrintConfig(
        printers = listOf(PrinterInfo(id = impresora, name = impresora, connectionType = "NETWORK", address = "10.0.0.9")),
        stations = listOf(
            StationInfo(id = "st-cocina", name = "Cocina", printerId = impresora),
            StationInfo(id = "barra", name = "Barra", printerId = impresora),
            StationInfo(id = "caja", name = "Caja", printerId = impresora),
            StationInfo(id = "st-empaque", name = "Empaque", printerId = impresora),
            StationInfo(id = "st-tienda", name = "Tienda", sendsComanda = false),
            StationInfo(id = "vieja", name = "Vieja", printerId = impresora, active = false),
        ),
        packingStationId = packing,
        categoryRouting = listOf(CategoryRoute("cat-cocina", "st-cocina"), CategoryRoute("cat-tienda", "st-tienda")),
        version = impresora,
    )

    private fun dispatchDevuelve(estado: EstadoDeComanda?) {
        estadoComanda = { estado }
    }

    @Test
    fun `solo reclama las que piden papel`() = runTest {
        val sut = armar()
        dispatchDevuelve(EstadoDeComanda.Salio)

        sut.imprimirPendientes(listOf(pedido("a"), pedido("b", needsPrint = false)))

        coVerify(exactly = 1) { repo.reclamarImpresion("a", "dev-1") }
        coVerify(exactly = 0) { repo.reclamarImpresion("b", any()) }
    }

    @Test
    fun `la que pierde la reclamacion no se imprime ni se apunta`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        coEvery { repo.reclamarImpresion("a", any()) } returns false

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify(exactly = 0) { despacho() }
        coVerify(exactly = 0) { repo.marcarImpresion(any(), any(), any()) }
        assertTrue(pizarra.avisos.value.isEmpty())
    }

    @Test
    fun `todo salio confirma y apunta impreso`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        dispatchDevuelve(EstadoDeComanda.Salio)

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertEquals(1, pizarra.avisos.value.size)
        assertTrue(pizarra.avisos.value[0].salioEnPapel)
        assertTrue(pizarra.avisos.value[0].faltaron.isEmpty())
        assertTrue(pizarra.avisos.value[0].porReimprimir.isEmpty())
    }

    @Test
    fun `nada salio suelta y no intenta empaque`() = runTest(UnconfinedTestDispatcher()) {
        conEmpaque()
        val sut = armar()
        dispatchDevuelve(EstadoDeComanda.NoSalio(listOf("Cocina"), "sin papel", "N-a", trabajo, algoSalio = false))

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "release-print") }
        coVerify(exactly = 0) { ticket() }
        assertFalse(pizarra.avisos.value.single().salioEnPapel)
        assertTrue(pizarra.avisos.value.single().porReimprimir.isEmpty())
    }

    @Test
    fun `una excepcion del despachador suelta y no intenta empaque`() = runTest {
        conEmpaque()
        val sut = armar()
        estadoComanda = { throw IllegalStateException("boom") }

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "release-print") }
        coVerify(exactly = 0) { ticket() }
    }

    @Test
    fun `sin deviceId no hace nada`() = runTest {
        val sut = armar(deviceId = null)

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify(exactly = 0) { repo.reclamarImpresion(any(), any()) }
        coVerify(exactly = 0) { despacho() }
    }

    @Test
    fun `el resultado queda apuntado en la pizarra`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        dispatchDevuelve(null)

        sut.imprimirPendientes(listOf(pedido("a")))

        val c = pizarra.avisos.value.single()
        assertEquals("a", c.kdsId)
        assertEquals("N-a", c.orderNumber)
        assertEquals(1_000L, c.createdAt)
        assertTrue(c.salioEnPapel)
        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
    }

    @Test
    fun `parcial confirma y guarda lo que falto`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        dispatchDevuelve(EstadoDeComanda.NoSalio(listOf("Barra"), "sin papel", "N-a", trabajo, algoSalio = true))

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertFalse(pizarra.avisos.value.single().salioEnPapel)
        assertEquals(listOf("Barra"), pizarra.avisos.value.single().faltaron)
        assertEquals(listOf(trabajo), pizarra.avisos.value.single().porReimprimir)
    }

    @Test
    fun `estacion sin impresora queda en faltaron sin boton`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        dispatchDevuelve(EstadoDeComanda.NoSalio(listOf("Barra"), "sin impresora", "N-a", trabajo = null, algoSalio = true))

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertFalse(pizarra.avisos.value.single().salioEnPapel)
        assertEquals(listOf("Barra"), pizarra.avisos.value.single().faltaron)
        assertTrue(pizarra.avisos.value.single().porReimprimir.isEmpty())
        assertEquals(listOf("Barra"), pizarra.avisos.value.single().sinReimprimir)
    }

    @Test
    fun `empaque que no sale queda por reimprimir`() = runTest(UnconfinedTestDispatcher()) {
        conEmpaque()
        val sut = armar()
        val trabajoEmpaque = mockk<TrabajoPendiente>(relaxed = true)
        estadoEmpaque = { EstadoDeComanda.NoSalio(listOf("Empaque"), "sin papel", "N-a", trabajoEmpaque) }
        dispatchDevuelve(EstadoDeComanda.Salio)

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertFalse(pizarra.avisos.value.single().salioEnPapel)
        assertEquals(listOf("Empaque"), pizarra.avisos.value.single().faltaron)
        assertEquals(listOf(trabajoEmpaque), pizarra.avisos.value.single().porReimprimir)
    }

    @Test
    fun `reimprimirLoQueFalto manda solo los trabajos`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        coEvery { dispatcher.reintentar(trabajo, any(), any()) } returns EstadoDeComanda.Salio
        val parcial = ComandaImpresa("a", "N-a", 1_000, salioEnPapel = false, faltaron = listOf("Barra"), porReimprimir = listOf(trabajo))

        pizarra.publicar(parcial, pizarra.epoca())
        val nueva = sut.reimprimirLoQueFalto("a")!!

        coVerify(exactly = 1) { dispatcher.reintentar(trabajo, any(), any()) }
        coVerify(exactly = 0) { despacho() }
        assertTrue(nueva.salioEnPapel)
        assertTrue(nueva.faltaron.isEmpty())
        assertTrue(nueva.porReimprimir.isEmpty())
        assertEquals(nueva, pizarra.avisos.value.single())
    }

    @Test
    fun `reimprimirLoQueFalto conserva lo que siga sin salir`() = runTest {
        val sut = armar()
        coEvery { dispatcher.reintentar(trabajo, any(), any()) } returns
            EstadoDeComanda.NoSalio(listOf("Barra"), "sin papel", "N-a", trabajo, algoSalio = false)
        val parcial = ComandaImpresa("a", "N-a", 1_000, salioEnPapel = false, faltaron = listOf("Barra"), porReimprimir = listOf(trabajo))

        pizarra.publicar(parcial, pizarra.epoca())
        val nueva = sut.reimprimirLoQueFalto("a")!!

        assertFalse(nueva.salioEnPapel)
        assertEquals(listOf(trabajo), nueva.porReimprimir)
    }

    @Test
    fun `la estacion sin impresora sobrevive a reimprimir el empaque`() = runTest {
        val sut = armar()
        coEvery { dispatcher.reintentar(trabajo, any(), any()) } returns EstadoDeComanda.Salio
        val c = ComandaImpresa(
            "a", "N-a", 1_000, salioEnPapel = false, faltaron = listOf("Barra", "Empaque"),
            porReimprimir = listOf(trabajo), sinReimprimir = listOf("Barra"),
        )

        pizarra.publicar(c, pizarra.epoca())
        val nueva = sut.reimprimirLoQueFalto("a")!!

        assertEquals(listOf("Barra"), nueva.faltaron)
        assertFalse(nueva.salioEnPapel)
        assertTrue(nueva.porReimprimir.isEmpty())
        assertEquals(listOf("Barra"), nueva.sinReimprimir)
    }

    @Test
    fun `reimprimir apunta antes de soltar y un segundo toque ya no manda nada`() = runTest {
        val sut = armar()
        coEvery { dispatcher.reintentar(trabajo, any(), any()) } returns EstadoDeComanda.Salio
        pizarra.publicar(ComandaImpresa("a", "N-a", 1_000, salioEnPapel = false, porReimprimir = listOf(trabajo)), pizarra.epoca())

        sut.reimprimirLoQueFalto("a")
        val segundo = sut.reimprimirLoQueFalto("a")

        assertEquals(null, segundo)
        coVerify(exactly = 1) { dispatcher.reintentar(trabajo, any(), any()) }
        assertTrue(pizarra.avisos.value.single().salioEnPapel)
    }

    @Test
    fun `lo que termina despues de Entendido no vuelve a la pizarra`() = runTest {
        val sut = armar()
        dispatchDevuelve(EstadoDeComanda.Salio)
        // «a» ya estaba en la franja (su papel falló y se soltó); se vuelve a imprimir y, a media impresión, «Entendido».
        pizarra.publicar(ComandaImpresa("a", "N-a", 1_000, salioEnPapel = false), pizarra.epoca())
        coEvery { repo.reclamarImpresion("a", any()) } coAnswers { pizarra.descartar(); true }

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertTrue(pizarra.avisos.value.isEmpty())
    }

    @Test
    fun `un pedido nuevo que se imprimia durante Entendido si aparece`() = runTest {
        val sut = armar()
        dispatchDevuelve(EstadoDeComanda.Salio)
        pizarra.publicar(ComandaImpresa("x", "N-x", 500, salioEnPapel = true), pizarra.epoca())
        coEvery { repo.reclamarImpresion("b", any()) } coAnswers { pizarra.descartar(); true }

        sut.imprimirPendientes(listOf(pedido("b")))

        assertEquals(listOf("b"), pizarra.avisos.value.map { it.kdsId })
        assertEquals("v1", pizarra.avisos.value.single().venueId)
    }

    @Test
    fun `dos reimpresiones al mismo tiempo mandan una sola vez`() = runTest {
        val sut = armar()
        val sigue = CompletableDeferred<Unit>()
        coEvery { dispatcher.reintentar(trabajo, any(), any()) } coAnswers { sigue.await(); EstadoDeComanda.Salio }
        pizarra.publicar(ComandaImpresa("a", "N-a", 1_000, salioEnPapel = false, porReimprimir = listOf(trabajo)), pizarra.epoca())

        val primera = async { sut.reimprimirLoQueFalto("a") }
        val segunda = async { sut.reimprimirLoQueFalto("a") }
        runCurrent()
        sigue.complete(Unit)

        assertTrue(primera.await()!!.salioEnPapel)
        assertEquals(null, segunda.await())
        coVerify(exactly = 1) { dispatcher.reintentar(trabajo, any(), any()) }
        // Y ya apuntado: un toque más no encuentra nada que mandar.
        assertEquals(null, sut.reimprimirLoQueFalto("a"))
    }

    @Test
    fun `Entendido a media reimpresion no deja que el resultado vuelva`() = runTest {
        val sut = armar()
        val sigue = CompletableDeferred<Unit>()
        coEvery { dispatcher.reintentar(trabajo, any(), any()) } coAnswers { sigue.await(); EstadoDeComanda.Salio }
        pizarra.publicar(ComandaImpresa("a", "N-a", 1_000, salioEnPapel = false, porReimprimir = listOf(trabajo)), pizarra.epoca())

        val reimpresion = async { sut.reimprimirLoQueFalto("a") }
        runCurrent()
        pizarra.descartar()
        sigue.complete(Unit)
        reimpresion.await()

        assertTrue(pizarra.avisos.value.isEmpty())
    }

    // MARK: - Bloque 2: dónde se imprime el delivery (spec 2026-11-13 §4.3 y §7, Codex p1 #1 y #2, p2 #3)

    @Test
    fun `una comanda que falta aceptar no se reclama ni se imprime`() = runTest {
        val sut = armar()

        sut.imprimirPendientes(listOf(pedido("a", needsAcceptance = true)))

        coVerify(exactly = 0) { repo.reclamarImpresion(any(), any()) }
        assertTrue(orden.isEmpty())
    }

    @Test
    fun `sin instruccion del canal rutea como el salon y el empaque va al del negocio`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(taco, refresco))))

        // Hay «Sin comanda» y empaque: el empaque sale primero y, como sí salió, la comanda ya no lleva el refresco.
        assertEquals(listOf("empaque", "comanda"), orden)
        val d = despachos.single()
        assertEquals(null, d.fija)
        assertTrue(d.omitir)
        assertEquals(false, d.callar)
        val t = tickets.single()
        assertEquals("st-empaque", t.estacion)
        assertEquals("EMPAQUE · Delivery", t.encabezado)
        assertEquals(listOf("Taco", "Refresco"), t.lineas)
        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
    }

    @Test
    fun `sin instruccion y sin empaque del negocio la comanda conserva lo Sin comanda`() = runTest {
        config = negocio(packing = null)
        val sut = armar()

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(taco, refresco))))

        assertEquals(listOf("comanda"), orden)
        assertFalse(despachos.single().omitir)
        assertEquals(false, despachos.single().callar)
    }

    @Test
    fun `sin renglones Sin comanda el orden es el de hoy`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(taco))))

        assertEquals(listOf("comanda", "empaque"), orden)
        assertFalse(despachos.single().omitir)
    }

    @Test
    fun `todo junto en barra sin empaque`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(taco, refresco), impresion = ImpresionReparto("RAPPI", estacionUnicaId = "barra", empaque = false))))

        assertEquals(listOf("comanda"), orden)
        val d = despachos.single()
        assertEquals("barra", d.fija)
        assertFalse(d.omitir)
        assertEquals(false, d.callar)
        assertTrue(tickets.isEmpty())
        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
    }

    @Test
    fun `el empaque sale en la estacion que eligio el canal`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()

        sut.imprimirPendientes(listOf(pedido("a", impresion = ImpresionReparto("UBER_EATS", empaque = true, empaqueEstacionId = "caja"))))

        assertEquals("caja", tickets.single().estacion)
        assertEquals(null, despachos.single().fija)
    }

    @Test
    fun `un empaque del canal que ya no esta activo cae al del negocio`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()

        sut.imprimirPendientes(
            listOf(
                pedido("a", impresion = ImpresionReparto("RAPPI", empaque = true, empaqueEstacionId = "vieja")),
                pedido("b", impresion = ImpresionReparto("RAPPI", empaque = true, empaqueEstacionId = "no-existe")),
            ),
        )

        assertEquals(listOf("st-empaque", "st-empaque"), tickets.map { it.estacion })
    }

    @Test
    fun `todo junto y empaque en la misma estacion sale un solo papel`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()

        sut.imprimirPendientes(
            listOf(pedido("a", items = listOf(taco, refresco), impresion = ImpresionReparto("RAPPI", estacionUnicaId = "caja", empaque = true, empaqueEstacionId = "caja"))),
        )

        coVerify(exactly = 0) { despacho() }
        val t = tickets.single()
        assertEquals("caja", t.estacion)
        assertEquals("EMPAQUE · Delivery", t.encabezado)
        assertEquals(listOf("Taco", "Refresco"), t.lineas)
        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertTrue(pizarra.avisos.value.single().salioEnPapel)
    }

    @Test
    fun `un solo papel que no sale suelta el pedido`() = runTest {
        config = negocio(packing = "caja")
        val sut = armar()
        estadoEmpaque = { EstadoDeComanda.NoSalio(listOf("Caja"), "sin papel", "N-a", trabajo, algoSalio = false) }

        sut.imprimirPendientes(listOf(pedido("a", impresion = ImpresionReparto("RAPPI", estacionUnicaId = "caja", empaque = true))))

        coVerify(exactly = 0) { despacho() }
        coVerify { repo.marcarImpresion("a", "dev-1", "release-print") }
        coVerify(exactly = 0) { repo.marcarImpresion("a", any(), "confirm-print") }
        val aviso = pizarra.avisos.value.single()
        assertFalse(aviso.salioEnPapel)
        assertEquals(listOf("Caja"), aviso.faltaron)
        assertTrue(aviso.porReimprimir.isEmpty())
    }

    @Test
    fun `un solo papel con dos copias - la primera sale y la segunda no - confirma y deja la copia por reimprimir`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()
        val laCopiaQueFalta = mockk<TrabajoPendiente>(relaxed = true)
        estadoEmpaque = { EstadoDeComanda.NoSalio(listOf("Caja"), "sin papel", "N-a", laCopiaQueFalta, algoSalio = true) }

        sut.imprimirPendientes(listOf(pedido("a", impresion = ImpresionReparto("RAPPI", estacionUnicaId = "caja", empaque = true, empaqueEstacionId = "caja"))))

        coVerify(exactly = 0) { despacho() }
        assertEquals(listOf("caja"), tickets.map { it.estacion })
        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        val aviso = pizarra.avisos.value.single()
        assertFalse(aviso.salioEnPapel)
        assertEquals(listOf("Caja"), aviso.faltaron)
        assertEquals(listOf(laCopiaQueFalta), aviso.porReimprimir)
    }

    @Test
    fun `un solo papel cuyas dos copias no salen suelta el pedido`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()
        estadoEmpaque = { EstadoDeComanda.NoSalio(listOf("Caja"), "sin papel", "N-a", trabajo, algoSalio = false) }

        sut.imprimirPendientes(listOf(pedido("a", impresion = ImpresionReparto("RAPPI", estacionUnicaId = "caja", empaque = true, empaqueEstacionId = "caja"))))

        coVerify { repo.marcarImpresion("a", "dev-1", "release-print") }
        coVerify(exactly = 0) { repo.marcarImpresion("a", any(), "confirm-print") }
    }

    @Test
    fun `puros Sin comanda con empaque que sale - el empaque es el papel y se confirma`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()
        estadoComanda = { d -> if (d.omitir) EstadoDeComanda.SinComanda else EstadoDeComanda.Salio }

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(refresco))))

        assertEquals(listOf("empaque", "comanda"), orden)
        assertTrue(despachos.single().omitir)
        assertEquals(false, despachos.single().callar)
        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertTrue(pizarra.avisos.value.single().salioEnPapel)
    }

    @Test
    fun `puros Sin comanda con empaque que no sale - la comanda los lleva y si tampoco sale se suelta`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()
        estadoEmpaque = { EstadoDeComanda.NoSalio(listOf("Empaque"), "sin papel", "N-a", trabajo, algoSalio = false) }
        estadoComanda = { d -> if (d.omitir) EstadoDeComanda.SinComanda else EstadoDeComanda.NoSalio(listOf("SIN ESTACIÓN"), "sin papel", "N-a", trabajo) }

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(refresco))))

        assertEquals(listOf("empaque", "comanda"), orden)
        assertFalse(despachos.single().omitir)
        coVerify { repo.marcarImpresion("a", "dev-1", "release-print") }
        coVerify(exactly = 0) { repo.marcarImpresion("a", any(), "confirm-print") }
        val aviso = pizarra.avisos.value.single()
        assertFalse(aviso.salioEnPapel)
        assertEquals(listOf("Empaque", "SIN ESTACIÓN"), aviso.faltaron)
    }

    @Test
    fun `mezcla con empaque fallido - la comanda lleva todo y se confirma con el empaque por reimprimir`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()
        val trabajoEmpaque = mockk<TrabajoPendiente>(relaxed = true)
        estadoEmpaque = { EstadoDeComanda.NoSalio(listOf("Empaque"), "sin papel", "N-a", trabajoEmpaque, algoSalio = false) }
        estadoComanda = { d -> if (d.omitir) EstadoDeComanda.SinComanda else EstadoDeComanda.Salio }

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(taco, refresco))))

        assertEquals(listOf("empaque", "comanda"), orden)
        assertFalse(despachos.single().omitir)
        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        val aviso = pizarra.avisos.value.single()
        assertFalse(aviso.salioEnPapel)
        assertEquals(listOf("Empaque"), aviso.faltaron)
        assertEquals(listOf(trabajoEmpaque), aviso.porReimprimir)
    }

    @Test
    fun `puros Sin comanda sin empaque configurado - sale la comanda como hoy`() = runTest {
        config = negocio(packing = null)
        val sut = armar()

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(refresco))))

        assertEquals(listOf("comanda"), orden)
        assertFalse(despachos.single().omitir)
        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
    }

    @Test
    fun `la config del pedido se refresca y los dos papeles salen con la del servidor`() = runTest {
        config = negocio(packing = "st-empaque", impresora = "imp-A")
        val delServidor = negocio(packing = "st-empaque", impresora = "imp-B")
        coEvery { printConfig.refreshConTope(any(), any()) } coAnswers { config = delServidor }
        val sut = armar()

        sut.imprimirPendientes(
            listOf(
                pedido("a", items = listOf(taco)),
                pedido("b", impresion = ImpresionReparto("RAPPI", estacionUnicaId = "caja", empaque = true, empaqueEstacionId = "caja")),
            ),
        )

        coVerify(exactly = 2) { printConfig.refreshConTope("v1", any()) }
        assertEquals(listOf(delServidor), despachos.map { it.config })
        // El empaque del pedido «a» y el papel único del «b».
        assertEquals(listOf(delServidor, delServidor), tickets.map { it.configFija })
        assertEquals(listOf(delServidor, delServidor), tickets.map { it.config })
    }

    @Test
    fun `una descarga que llega entre los dos papeles no los separa`() = runTest {
        config = negocio(packing = "st-empaque", impresora = "imp-B")
        val capturada = config
        val tardia = negocio(packing = "st-empaque", impresora = "imp-C")
        val sut = armar()
        estadoComanda = { config = tardia; EstadoDeComanda.Salio }

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(taco))))

        assertEquals(listOf("comanda", "empaque"), orden)
        assertEquals(capturada, despachos.single().config)
        assertEquals(capturada, tickets.single().configFija)
        assertEquals(capturada, tickets.single().config)
    }

    @Test
    fun `mezcla con empaque que sale y comanda sin hojas - se confirma con Cocina por reimprimir`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()
        val trabajoCocina = mockk<TrabajoPendiente>(relaxed = true)
        estadoComanda = { EstadoDeComanda.NoSalio(listOf("Cocina"), "sin papel", "N-a", trabajoCocina, algoSalio = false) }

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(taco, refresco))))

        assertEquals(listOf("empaque", "comanda"), orden)
        assertTrue(despachos.single().omitir)
        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        coVerify(exactly = 0) { repo.marcarImpresion("a", any(), "release-print") }
        val aviso = pizarra.avisos.value.single()
        assertFalse(aviso.salioEnPapel)
        assertEquals(listOf("Cocina"), aviso.faltaron)
        assertEquals(listOf(trabajoCocina), aviso.porReimprimir)
    }

    @Test
    fun `el empaque truena en el camino Sin comanda - la comanda conserva lo Sin comanda y el empaque falta sin boton`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()
        estadoEmpaque = { throw IllegalStateException("boom") }

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(taco, refresco))))

        assertEquals(listOf("empaque", "comanda"), orden)
        assertFalse(despachos.single().omitir)
        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        val aviso = pizarra.avisos.value.single()
        assertFalse(aviso.salioEnPapel)
        assertEquals(listOf("Empaque"), aviso.faltaron)
        assertEquals(listOf("Empaque"), aviso.sinReimprimir)
        assertTrue(aviso.porReimprimir.isEmpty())
    }

    @Test
    fun `una comanda que truena tras el empaque falta con su etiqueta y sin boton`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()
        estadoComanda = { throw IllegalStateException("boom") }

        sut.imprimirPendientes(listOf(pedido("a", items = listOf(taco, refresco))))

        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        val aviso = pizarra.avisos.value.single()
        assertFalse(aviso.salioEnPapel)
        assertEquals(listOf("Comanda"), aviso.faltaron)
        assertEquals(listOf("Comanda"), aviso.sinReimprimir)
    }

    @Test
    fun `un empaque del canal en una estacion activa Sin comanda cae al del negocio`() = runTest {
        config = negocio(packing = "st-empaque")
        val sut = armar()

        sut.imprimirPendientes(listOf(pedido("a", impresion = ImpresionReparto("RAPPI", empaque = true, empaqueEstacionId = "st-tienda"))))

        assertEquals(listOf("st-empaque"), tickets.map { it.estacion })
    }

    @Test
    fun `el aviso lleva el proveedor del pedido`() = runTest {
        val sut = armar()

        sut.imprimirPendientes(listOf(pedido("a", impresion = ImpresionReparto("UBER_EATS")), pedido("b")))

        assertEquals(mapOf("a" to "UBER_EATS", "b" to null), pizarra.avisos.value.associate { it.kdsId to it.proveedor })
    }
}
