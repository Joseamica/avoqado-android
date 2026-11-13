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

    private fun armar(deviceId: String? = "dev-1"): ImpresionDeComandasDeReparto {
        every { repo.venueIdActual() } returns "v1"
        if (deviceId != null) every { cola.deviceId } returns deviceId else every { cola.deviceId } throws IllegalStateException("sin id")
        every { printConfig.getCurrentConfig() } answers { config }
        coEvery { repo.reclamarImpresion(any(), any()) } returns true
        coEvery { dispatcher.reintentar(match { it.orderType.startsWith("EMPAQUE") }, any(), any()) } returns EstadoDeComanda.Salio
        return ImpresionDeComandasDeReparto(repo, dispatcher, printConfig, Provider { cola }, pizarra)
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
    fun `la que pierde la reclamacion no se imprime ni se apunta`() = runTest(UnconfinedTestDispatcher()) {
        val sut = armar()
        coEvery { repo.reclamarImpresion("a", any()) } returns false

        sut.imprimirPendientes(listOf(pedido("a")))

        coVerify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any(), any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { dispatcher.reintentar(any(), any(), any()) }
        assertFalse(pizarra.avisos.value.single().salioEnPapel)
        assertTrue(pizarra.avisos.value.single().porReimprimir.isEmpty())
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
        coEvery { dispatcher.reintentar(match { it.orderType.startsWith("EMPAQUE") }, any(), any()) } returns
            EstadoDeComanda.NoSalio(listOf("Empaque"), "sin papel", "N-a", trabajoEmpaque)
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
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any(), any(), any(), any(), any(), any(), any()) }
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
}
