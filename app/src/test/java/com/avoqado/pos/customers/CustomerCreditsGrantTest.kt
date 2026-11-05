package com.avoqado.pos.customers

import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.customers.data.DesenlaceDeEntrega
import com.avoqado.pos.customers.data.PendingGrant
import com.avoqado.pos.customers.data.PendingGrantQueue
import com.avoqado.pos.customers.presentation.CustomerCreditsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La entrega de una membresía ocurre DESPUÉS de que el cliente ya pagó.
 * Si falla, tiene que pasar lo doble: quedarse en la cola (para no perder la entrega)
 * y avisarse (para que el mostrador se entere de que hay dinero cobrado sin
 * su contraparte). Silenciar el modal global no puede significar silenciar
 * también al cajero — ése fue el defecto que estas pruebas fijan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerCreditsGrantTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun buildViewModel(
        repo: ArticlesRepository,
        queue: PendingGrantQueue,
    ): CustomerCreditsViewModel {
        val roles: RoleManager = mockk()
        every { roles.canManageCustomers } returns true
        every { roles.canReadCreditPacks } returns true
        every { repo.creditPacks } returns MutableStateFlow(emptyList())
        return CustomerCreditsViewModel(repo, queue, roles)
    }

    @Test
    fun `entrega rechazada tras el cobro se avisa como rechazo`() = runTest {
        val repo: ArticlesRepository = mockk()
        val queue: PendingGrantQueue = mockk(relaxed = true)
        coEvery { queue.entregarVenta("venta-1") } returns listOf(DesenlaceDeEntrega.RECHAZADA)

        val vm = buildViewModel(repo, queue)
        vm.entregarCobradas("venta-1")
        advanceUntilIdle()

        assertEquals(1, vm.undeliveredGrants.value)
        assertFalse(vm.entregaSoloEsperaRed.value)
    }

    @Test
    fun `sin red se dice sin alarma - espera sola`() = runTest {
        val repo: ArticlesRepository = mockk()
        val queue: PendingGrantQueue = mockk(relaxed = true)
        coEvery { queue.entregarVenta("venta-1") } returns listOf(DesenlaceDeEntrega.SIN_RED)

        val vm = buildViewModel(repo, queue)
        vm.entregarCobradas("venta-1")
        advanceUntilIdle()

        assertEquals(1, vm.undeliveredGrants.value)
        assertTrue(vm.entregaSoloEsperaRed.value)
    }

    @Test
    fun `entrega exitosa no avisa`() = runTest {
        val repo: ArticlesRepository = mockk()
        val queue: PendingGrantQueue = mockk(relaxed = true)
        coEvery { queue.entregarVenta("venta-1") } returns listOf(DesenlaceDeEntrega.ENTREGADA)

        val vm = buildViewModel(repo, queue)
        vm.entregarCobradas("venta-1")
        advanceUntilIdle()

        assertEquals(0, vm.undeliveredGrants.value)
    }

    @Test
    fun `avisa solo por las que fallaron cuando el carrito trae varias`() = runTest {
        val repo: ArticlesRepository = mockk()
        val queue: PendingGrantQueue = mockk(relaxed = true)
        coEvery { queue.entregarVenta("venta-1") } returns
            listOf(DesenlaceDeEntrega.ENTREGADA, DesenlaceDeEntrega.RECHAZADA, DesenlaceDeEntrega.SIN_RED)

        val vm = buildViewModel(repo, queue)
        vm.entregarCobradas("venta-1")
        advanceUntilIdle()

        assertEquals(2, vm.undeliveredGrants.value)
        assertFalse(vm.entregaSoloEsperaRed.value)
    }

    @Test
    fun `el aviso se limpia al acusarlo de recibo`() = runTest {
        val repo: ArticlesRepository = mockk()
        val queue: PendingGrantQueue = mockk(relaxed = true)
        coEvery { queue.entregarVenta("venta-1") } returns listOf(DesenlaceDeEntrega.SIN_RED)

        val vm = buildViewModel(repo, queue)
        vm.entregarCobradas("venta-1")
        advanceUntilIdle()
        assertEquals(1, vm.undeliveredGrants.value)

        vm.clearUndeliveredGrants()

        // Se limpia el AVISO, no la cola: descartar el modal nunca puede borrar el dinero.
        assertEquals(0, vm.undeliveredGrants.value)
        verify(exactly = 0) { queue.descartar(any()) }
    }

    // MARK: - La ficha del cliente

    private val pack = CreditPack(id = "pack-1", name = "10 clases", price = 500.0)
    private val fila = PendingGrant(id = "llave-1", venueId = "v", packId = "pack-1", customerId = "cli-1", createdAt = 1)

    @Test
    fun `la ficha guarda antes de mandar y al entregar cierra`() = runTest {
        val repo: ArticlesRepository = mockk(relaxed = true)
        val queue: PendingGrantQueue = mockk(relaxed = true)
        every { queue.guardarVentaDeFicha(pack, "cli-1", "Ana") } returns fila
        coEvery { queue.entregarDeFicha("llave-1") } returns DesenlaceDeEntrega.ENTREGADA
        var cerrada = false

        val vm = buildViewModel(repo, queue)
        vm.sell(pack, "cli-1", "Ana") { cerrada = true }
        advanceUntilIdle()

        coVerifyOrder {
            queue.guardarVentaDeFicha(pack, "cli-1", "Ana")
            queue.entregarDeFicha("llave-1")
        }
        assertTrue(cerrada)
        assertNull(vm.avisoDeVenta.value)
    }

    @Test
    fun `la ficha sin red no cierra ni pierde la venta - y lo dice`() = runTest {
        val repo: ArticlesRepository = mockk(relaxed = true)
        val queue: PendingGrantQueue = mockk(relaxed = true)
        every { queue.guardarVentaDeFicha(pack, "cli-1", "Ana") } returns fila
        coEvery { queue.entregarDeFicha("llave-1") } returns DesenlaceDeEntrega.SIN_RED
        var cerrada = false

        val vm = buildViewModel(repo, queue)
        vm.sell(pack, "cli-1", "Ana") { cerrada = true }
        advanceUntilIdle()

        assertFalse(cerrada)
        assertTrue(vm.avisoDeVenta.value!!.contains("guardada"))
        verify(exactly = 0) { queue.descartar(any()) }
    }

    @Test
    fun `la ficha rechazada ensena el motivo a quien lo toco`() = runTest {
        val repo: ArticlesRepository = mockk(relaxed = true)
        val queue: PendingGrantQueue = mockk(relaxed = true)
        every { queue.guardarVentaDeFicha(pack, "cli-1", "Ana") } returns fila
        coEvery { queue.entregarDeFicha("llave-1") } returns DesenlaceDeEntrega.RECHAZADA
        every { queue.motivoDe("llave-1") } returns "El paquete no está activo"

        val vm = buildViewModel(repo, queue)
        vm.sell(pack, "cli-1", "Ana") { }
        advanceUntilIdle()

        // La cajera ya cobró: lo ve aquí y se queda en la cuarentena aunque cierre la ventana.
        assertTrue(vm.avisoDeVenta.value!!.startsWith("El paquete no está activo"))
        verify(exactly = 0) { queue.descartar(any()) }
    }

    @Test
    fun `doble toque en vender no vende dos veces`() = runTest {
        val repo: ArticlesRepository = mockk(relaxed = true)
        val queue: PendingGrantQueue = mockk(relaxed = true)
        every { queue.guardarVentaDeFicha(pack, "cli-1", "Ana") } returns fila
        coEvery { queue.entregarDeFicha("llave-1") } returns DesenlaceDeEntrega.SIN_RED

        val vm = buildViewModel(repo, queue)
        vm.sell(pack, "cli-1", "Ana") { }
        vm.sell(pack, "cli-1", "Ana") { }
        advanceUntilIdle()

        verify(exactly = 1) { queue.guardarVentaDeFicha(any(), any(), any()) }
    }

    @Test
    fun `la ficha sin disco no manda nada`() = runTest {
        val repo: ArticlesRepository = mockk(relaxed = true)
        val queue: PendingGrantQueue = mockk(relaxed = true)
        every { queue.guardarVentaDeFicha(any(), any(), any()) } returns null

        val vm = buildViewModel(repo, queue)
        vm.sell(pack, "cli-1", "Ana") { }
        advanceUntilIdle()

        coVerify(exactly = 0) { queue.entregarDeFicha(any()) }
        assertTrue(vm.avisoDeVenta.value!!.contains("No se pudo guardar"))
    }
}
