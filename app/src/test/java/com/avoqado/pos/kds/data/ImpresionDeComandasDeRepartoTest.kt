package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderItem
import com.avoqado.pos.kds.domain.KDSOrderStatus
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.TrabajoPendiente
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

/**
 * La pieza que imprime las comandas de Rappi/Uber sin importar en qué pantalla esté el aparato. Lo que se prueba es la
 * tabla de decisión: qué se le dice al servidor (`confirm-print` / `release-print`) y qué se publica en [impresas].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImpresionDeComandasDeRepartoTest {

    private val repo = mockk<KDSRepository>(relaxed = true)
    private val dispatcher = mockk<ComandaDispatcher>(relaxed = true)
    private val printConfig = mockk<PrintConfigRepository>(relaxed = true)
    private val cola = mockk<SyncOutbox>(relaxed = true)
    private var config = PrintConfig()

    private fun armar(deviceId: String? = "dev-1"): ImpresionDeComandasDeReparto {
        every { repo.venueIdActual() } returns "v1"
        if (deviceId != null) every { cola.deviceId } returns deviceId else every { cola.deviceId } throws IllegalStateException("sin id")
        every { printConfig.getCurrentConfig() } answers { config }
        coEvery { repo.reclamarImpresion(any(), any()) } returns true
        coEvery { dispatcher.reintentar(match { it.orderType.startsWith("EMPAQUE") }, any(), any()) } returns EstadoDeComanda.Salio
        return ImpresionDeComandasDeReparto(repo, dispatcher, printConfig, Provider { cola })
    }

    private fun pedido(id: String, needsPrint: Boolean = true) = KDSOrder(
        id = id, orderId = "o-$id", orderNumber = "N-$id", orderType = "Delivery", needsPrint = needsPrint,
        items = listOf(KDSOrderItem(id = "i-$id", productName = "Taco", quantity = 1)),
        createdAt = 1_000, status = KDSOrderStatus.NEW,
    )

    private val trabajo = mockk<TrabajoPendiente>(relaxed = true)

    private fun conEmpaque() { config = PrintConfig(packingStationId = "st-empaque") }

    private fun dispatchDevuelve(estado: EstadoDeComanda?) {
        coEvery { dispatcher.dispatch(any(), any(), any(), any(), any(), any(), any(), any()) } returns estado
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
    fun `la que pierde la reclamacion no se imprime ni se emite`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        coEvery { repo.reclamarImpresion("a", any()) } returns false
        val recibidas = mutableListOf<ComandaImpresa>()
        backgroundScope.launch { sut.impresas.toList(recibidas) }

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repo.marcarImpresion(any(), any(), any()) }
        assertTrue(recibidas.isEmpty())
    }

    @Test
    fun `todo salio confirma y emite impreso`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        dispatchDevuelve(EstadoDeComanda.Salio)
        val recibidas = mutableListOf<ComandaImpresa>()
        backgroundScope.launch { sut.impresas.toList(recibidas) }

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertEquals(1, recibidas.size)
        assertTrue(recibidas[0].salioEnPapel)
        assertTrue(recibidas[0].faltaron.isEmpty())
        assertTrue(recibidas[0].porReimprimir.isEmpty())
    }

    @Test
    fun `nada salio suelta y no intenta empaque`() = runTest(UnconfinedTestDispatcher()) {
        conEmpaque()
        val sut = armar()
        dispatchDevuelve(EstadoDeComanda.NoSalio(listOf("Cocina"), "sin papel", "N-a", trabajo, algoSalio = false))
        val recibidas = mutableListOf<ComandaImpresa>()
        backgroundScope.launch { sut.impresas.toList(recibidas) }

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "release-print") }
        coVerify(exactly = 0) { dispatcher.reintentar(any(), any(), any()) }
        assertFalse(recibidas.single().salioEnPapel)
        assertTrue(recibidas.single().porReimprimir.isEmpty())
    }

    @Test
    fun `una excepcion del despachador suelta y no intenta empaque`() = runTest {
        conEmpaque()
        val sut = armar()
        coEvery { dispatcher.dispatch(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("boom")

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "release-print") }
        coVerify(exactly = 0) { dispatcher.reintentar(any(), any(), any()) }
    }

    @Test
    fun `sin deviceId no hace nada`() = runTest {
        val sut = armar(deviceId = null)

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify(exactly = 0) { repo.reclamarImpresion(any(), any()) }
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `la emision llega por impresas`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        dispatchDevuelve(null)
        val recibidas = mutableListOf<ComandaImpresa>()
        backgroundScope.launch { sut.impresas.toList(recibidas) }

        sut.imprimirPendientes(listOf(pedido("a")))

        val c = recibidas.single()
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
        val recibidas = mutableListOf<ComandaImpresa>()
        backgroundScope.launch { sut.impresas.toList(recibidas) }

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertFalse(recibidas.single().salioEnPapel)
        assertEquals(listOf("Barra"), recibidas.single().faltaron)
        assertEquals(listOf(trabajo), recibidas.single().porReimprimir)
    }

    @Test
    fun `estacion sin impresora queda en faltaron sin boton`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        dispatchDevuelve(EstadoDeComanda.NoSalio(listOf("Barra"), "sin impresora", "N-a", trabajo = null, algoSalio = true))
        val recibidas = mutableListOf<ComandaImpresa>()
        backgroundScope.launch { sut.impresas.toList(recibidas) }

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertFalse(recibidas.single().salioEnPapel)
        assertEquals(listOf("Barra"), recibidas.single().faltaron)
        assertTrue(recibidas.single().porReimprimir.isEmpty())
        assertEquals(listOf("Barra"), recibidas.single().sinReimprimir)
    }

    @Test
    fun `empaque que no sale queda por reimprimir`() = runTest(UnconfinedTestDispatcher()) {
        conEmpaque()
        val sut = armar()
        val trabajoEmpaque = mockk<TrabajoPendiente>(relaxed = true)
        coEvery { dispatcher.reintentar(match { it.orderType.startsWith("EMPAQUE") }, any(), any()) } returns
            EstadoDeComanda.NoSalio(listOf("Empaque"), "sin papel", "N-a", trabajoEmpaque)
        dispatchDevuelve(EstadoDeComanda.Salio)
        val recibidas = mutableListOf<ComandaImpresa>()
        backgroundScope.launch { sut.impresas.toList(recibidas) }

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify { repo.marcarImpresion("a", "dev-1", "confirm-print") }
        assertFalse(recibidas.single().salioEnPapel)
        assertEquals(listOf("Empaque"), recibidas.single().faltaron)
        assertEquals(listOf(trabajoEmpaque), recibidas.single().porReimprimir)
    }

    @Test
    fun `reimprimirLoQueFalto manda solo los trabajos`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        coEvery { dispatcher.reintentar(trabajo, any(), any()) } returns EstadoDeComanda.Salio
        val recibidas = mutableListOf<ComandaImpresa>()
        backgroundScope.launch { sut.impresas.toList(recibidas) }
        val parcial = ComandaImpresa("a", "N-a", 1_000, salioEnPapel = false, faltaron = listOf("Barra"), porReimprimir = listOf(trabajo))

        val nueva = sut.reimprimirLoQueFalto(parcial)

        coVerify(exactly = 1) { dispatcher.reintentar(trabajo, any(), any()) }
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(nueva.salioEnPapel)
        assertTrue(nueva.faltaron.isEmpty())
        assertTrue(nueva.porReimprimir.isEmpty())
        assertEquals(nueva, recibidas.single())
    }

    @Test
    fun `reimprimirLoQueFalto conserva lo que siga sin salir`() = runTest {
        val sut = armar()
        coEvery { dispatcher.reintentar(trabajo, any(), any()) } returns
            EstadoDeComanda.NoSalio(listOf("Barra"), "sin papel", "N-a", trabajo, algoSalio = false)
        val parcial = ComandaImpresa("a", "N-a", 1_000, salioEnPapel = false, faltaron = listOf("Barra"), porReimprimir = listOf(trabajo))

        val nueva = sut.reimprimirLoQueFalto(parcial)

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

        val nueva = sut.reimprimirLoQueFalto(c)

        assertEquals(listOf("Barra"), nueva.faltaron)
        assertFalse(nueva.salioEnPapel)
        assertTrue(nueva.porReimprimir.isEmpty())
        assertEquals(listOf("Barra"), nueva.sinReimprimir)
    }
}
