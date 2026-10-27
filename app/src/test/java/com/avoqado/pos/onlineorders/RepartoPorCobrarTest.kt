package com.avoqado.pos.onlineorders

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.PlanTier
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.ListaPorCobrar
import com.avoqado.pos.onlineorders.domain.PedidoPorCobrar
import com.avoqado.pos.onlineorders.presentation.EntradaPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.OnlineOrdersViewModel
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.carritoDelCobro
import com.avoqado.pos.onlineorders.presentation.desgloseDe
import com.avoqado.pos.onlineorders.presentation.entradaDePedidosEnLinea
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.domain.OrigenDelCobro
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
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
 * Rappi 2b (founder «A» y «D2-a», 23-oct): «Rappi · por cobrar» dentro de «Pedidos en línea». La puerta se abre con tienda web O con
 * reparto; sin tienda sólo sale esta sección; la lista sale del aparato sin red; y su «Cobrar» pasa por las MISMAS guardas que el de
 * un pedido web, con su propio origen (sólo efectivo, sin propina). Lo que pasa DENTRO del cobro se prueba en `PaymentFlowViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepartoPorCobrarTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<OnlineOrdersRepository>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private val cola = CashPaymentRepository(secureStorage, dao)
    private var filas: List<PendingPaymentEntity> = emptyList()

    /** Lo que contesta el servidor a `delivery-cash`, por cursor (null = página 1). */
    private val paginas = mutableMapOf<String?, Result<ListaPorCobrar>>()
    private var guardada: ListaPorCobrar? = null
    private val lecturas = mutableListOf<String?>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { secureStorage.venueId } returns "venue-1"
        coEvery { dao.forVenue("venue-1") } answers { filas }
        coEvery { repo.inbox(any(), any(), any()) } returns Result.success(
            Inbox(Instant.parse("2026-10-23T18:00:00Z"), 0, 0, 0, emptyList(), storeOff = false, truncated = false, nextCursor = null),
        )
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun rappi(id: String = "r1", saldo: Long = 20500) = PedidoPorCobrar(
        orderId = id, orderNumber = "881655210", provider = "RAPPI", customerName = "Ana", createdAt = Instant.parse("2026-10-23T18:00:00Z"),
        remainingBalanceCents = saldo, saleCents = 17500, tipCents = 2000, thirdPartyCents = 1000, thirdPartyLabel = "Rappi",
    )

    private fun lista(vararg p: PedidoPorCobrar, total: Int = p.size, cursor: String? = null) =
        ListaPorCobrar(p.toList(), total, truncated = cursor != null, nextCursor = cursor)

    private fun armar(tienePlan: Boolean = false, hayAbiertos: Boolean = false, canal: Boolean = true) = OnlineOrdersViewModel(
        repositorio = repo,
        permisoActualizar = { true },
        permisoCancelar = { true },
        tienePlan = { tienePlan },
        hayPedidosAbiertos = { hayAbiertos },
        ventanaALaVista = MutableStateFlow(true),
        efectivoDelPedido = { cola.efectivoDelPedido(it) },
        efectivoYaSubido = { cola.huboEfectivoSubido(it) },
        leerPorCobrar = { cursor -> lecturas += cursor; paginas[cursor] ?: Result.failure(IOException("sin red")) },
        porCobrarGuardado = { guardada },
        tieneCanalDeReparto = { canal },
    )

    // MARK: - La puerta (D2)

    @Test
    fun `the entry opens with delivery even without the web store`() {
        assertEquals(EntradaPedidosEnLinea.ABIERTA, entradaDePedidosEnLinea(puedeLeer = true, tienePlan = false, hayPedidosAbiertos = false, tieneReparto = true))
        assertEquals(EntradaPedidosEnLinea.TEASER, entradaDePedidosEnLinea(puedeLeer = true, tienePlan = false, hayPedidosAbiertos = false, tieneReparto = false))
        assertEquals(EntradaPedidosEnLinea.OCULTA, entradaDePedidosEnLinea(puedeLeer = false, tienePlan = false, hayPedidosAbiertos = false, tieneReparto = true))
    }

    @Test
    fun `PlanManager mirrors DELIVERY_CHANNELS as PREMIUM like the server`() {
        assertEquals(PlanTier.PREMIUM, PlanManager.FEATURE_REQUIRED_TIER["DELIVERY_CHANNELS"])
    }

    @Test
    fun `delivery only venue is not a teaser and never reads the web tray`() = runTest(dispatcher) {
        paginas[null] = Result.success(lista(rappi()))
        val vm = armar(tienePlan = false, hayAbiertos = false, canal = true)
        assertFalse(vm.esTeaser)
        assertFalse(vm.muestraTienda)

        vm.refrescar()

        assertEquals(listOf("r1"), vm.porCobrar.value!!.pedidos.map { it.orderId })
        assertFalse("sin tienda no se pinta «No se pudo leer la bandeja»", vm.noSePudoLeer.value)
        coVerify(exactly = 0) { repo.inbox(any(), any(), any()) }
    }

    @Test
    fun `web store without delivery does not read the delivery list in the loop`() = runTest(dispatcher) {
        val vm = armar(tienePlan = true, canal = false)
        vm.refrescar()
        assertTrue(lecturas.isEmpty())
        coVerify(exactly = 1) { repo.inbox("all", any(), any()) }
    }

    // MARK: - Descubrir y sin red

    @Test
    fun `discovery from the teaser finds pending orders with an expired plan and no saved list`() = runTest(dispatcher) {
        // Codex plan r1 #4: plan vencido, sin tienda web y nada guardado en ESTE aparato.
        paginas[null] = Result.success(lista(rappi()))
        val vm = armar(tienePlan = false, hayAbiertos = false, canal = false)
        assertTrue(vm.esTeaser)

        vm.descubrirPorCobrar()

        assertFalse("el teaser se quita solo al encontrar pedidos por cobrar", vm.esTeaser)
        assertEquals(1, vm.porCobrar.value!!.pedidos.size)
    }

    @Test
    fun `cold start without network shows the saved list, not an empty one`() = runTest(dispatcher) {
        guardada = lista(rappi())
        val vm = armar(canal = false)

        vm.descubrirPorCobrar()

        assertEquals(listOf("r1"), vm.porCobrar.value!!.pedidos.map { it.orderId })
        assertFalse(vm.esTeaser)
        assertTrue("sin red es un estado normal con su texto", vm.sinConexion.value)
    }

    @Test
    fun `a failed read never wipes the last list`() = runTest(dispatcher) {
        paginas[null] = Result.success(lista(rappi()))
        val vm = armar(tienePlan = true)
        vm.refrescar()
        paginas[null] = Result.failure(IllegalStateException("500"))
        coEvery { repo.inbox(any(), any(), any()) } returns Result.failure(IllegalStateException("500"))

        vm.refrescar()

        assertEquals(1, vm.porCobrar.value!!.pedidos.size)
    }

    @Test
    fun `an old server without the route hides the section`() = runTest(dispatcher) {
        paginas[null] = Result.success(OnlineOrdersViewModel.LISTA_NO_DISPONIBLE)
        val vm = armar(tienePlan = true)
        vm.refrescar()
        assertFalse(vm.porCobrar.value!!.disponible)
    }

    @Test
    fun `load more appends the next page and keeps it on the next refresh`() = runTest(dispatcher) {
        paginas[null] = Result.success(lista(rappi("r1"), total = 2, cursor = "c1"))
        paginas["c1"] = Result.success(lista(rappi("r2"), total = 2))
        val vm = armar()
        vm.refrescar()

        vm.cargarMasPorCobrar()
        advanceUntilIdle()
        vm.refrescar()

        assertEquals(listOf("r1", "r2"), vm.porCobrar.value!!.pedidos.map { it.orderId })
        assertNull(vm.porCobrar.value!!.nextCursor)
    }

    // MARK: - Cobrar

    @Test
    fun `cobrarDeReparto opens a cash only charge with the server balance`() = runTest(dispatcher) {
        coEvery { repo.saldoCents("r1") } returns Result.success(20500L)
        val vm = armar()

        vm.cobrarDeReparto(rappi())
        advanceUntilIdle()

        val cobro = vm.cobroAbierto.value!!
        assertEquals(OrigenDelCobro.PEDIDO_DE_PLATAFORMA_EFECTIVO, cobro.origen)
        assertEquals(20500, cobro.saldoCents)
        assertFalse(cobro.origen.permiteTarjeta)
        assertEquals("Rappi #881655210", carritoDelCobro(cobro).items.single().name)
    }

    @Test
    fun `offline it charges the saved balance as a prior capture`() = runTest(dispatcher) {
        coEvery { repo.saldoCents("r1") } returns Result.failure(IOException("sin red"))
        val vm = armar()

        vm.cobrarDeReparto(rappi(saldo = 10500))
        advanceUntilIdle()

        assertEquals(OrigenDelCobro.PEDIDO_DE_PLATAFORMA_EFECTIVO_SALDO_GUARDADO, vm.cobroAbierto.value?.origen)
        assertEquals(10500, vm.cobroAbierto.value?.saldoCents)
    }

    @Test
    fun `a cash payment of that order queued in this device blocks another`() = runTest(dispatcher) {
        filas = listOf(
            PendingPaymentEntity(
                id = "f1", venueId = "venue-1", staffId = "s1", amountCents = 20500, tipCents = 0, method = "CASH",
                paymentType = "ORDER", orderId = "r1", syncStatus = "PENDING",
            ),
        )
        val vm = armar()

        vm.cobrarDeReparto(rappi())
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.COBRO_EN_EFECTIVO_GUARDADO, vm.aviso.value)
        coVerify(exactly = 0) { repo.saldoCents(any()) }
    }

    @Test
    fun `already paid elsewhere shows Pagado and rereads`() = runTest(dispatcher) {
        coEvery { repo.saldoCents("r1") } returns Result.success(0L)
        paginas[null] = Result.success(lista())
        val vm = armar()

        vm.cobrarDeReparto(rappi())
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.PAGADO, vm.aviso.value)
        assertTrue(lecturas.isNotEmpty())
    }

    // MARK: - Textos

    @Test
    fun `breakdown omits the parts that are zero`() {
        assertEquals("Venta \$175.00 · Propina \$20.00 · Rappi \$10.00", desgloseDe(rappi()))
        assertEquals("Venta \$175.00", desgloseDe(rappi().copy(tipCents = 0, thirdPartyCents = 0)))
    }
}
