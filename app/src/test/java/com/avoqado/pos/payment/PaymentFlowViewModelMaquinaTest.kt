package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.maquina.AjustesDeLaMaquinaTest
import com.avoqado.pos.maquina.BovedaDeLaMaquina
import com.avoqado.pos.maquina.CierresDeLaMaquinaApi
import com.avoqado.pos.maquina.CobroEnMaquinaSobreSqlite
import com.avoqado.pos.maquina.Desenlace
import com.avoqado.pos.maquina.EstadoDelCobro
import com.avoqado.pos.maquina.ResolucionDelGerente
import com.avoqado.pos.maquina.EstadoDelIntento
import com.avoqado.pos.maquina.MaquinaDeEfectivo
import com.avoqado.pos.maquina.MaquinaDeEfectivoPrefs
import com.avoqado.pos.maquina.MaquinaDeEfectivoTest
import com.avoqado.pos.maquina.RespuestaDelServidor
import com.avoqado.pos.maquina.SubidaDeLaMaquina
import com.avoqado.pos.maquina.TicketDelCobro
import com.avoqado.pos.maquina.TEXTO_CON_PREMIO
import com.avoqado.pos.maquina.TEXTO_SOLO_VENTA_COMPLETA
import com.avoqado.pos.maquina.TurnoDelNegocio
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.ColaDelCobro
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.domain.ManualPaymentChoice
import com.avoqado.pos.payment.domain.ManualPaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.receiptlayout.KIND_COMPROBANTE_DE_LA_MAQUINA
import com.avoqado.pos.printing.receiptlayout.ReceiptCashMachine
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.Random

/**
 * C5 (R4-5, R5#4, R2#1, R3#1): «Cobrar» en la PC con la máquina. Con la casilla y la credencial, el punto común de cobro sólo
 * ofrece la máquina y la tarjeta; la orden NO nace en el SERVIDOR antes del cierre; no pide caja ni abre el cajón. Sin la máquina
 * todo queda como hoy. La máquina, con el canal falso y el DAO sobre SQLite de verdad.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentFlowViewModelMaquinaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)

    private val canal = MaquinaDeEfectivoTest.CanalFalso()
    /** Codex r1 #17: la comanda real, espiada para ver con qué folio sale. */
    private val comandas by lazy {
        io.mockk.spyk(
            ComandaDispatcher(
                printConfigRepository,
                ReintentoDeComanda(mockk<ComandaPrinter>(relaxed = true), reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
                printerService,
            ),
        )
    }
    private val dao = CobroEnMaquinaSobreSqlite()
    private val prefs = MaquinaDeEfectivoPrefs(AjustesDeLaMaquinaTest.contextoConPreferencias())
    private val boveda = object : BovedaDeLaMaquina {
        override fun guardar(credencial: String) = Unit
        override fun leer(): String? = "cred-1"
        override fun borrar() = Unit
    }
    private val api = mockk<CierresDeLaMaquinaApi>()
    private val turno = mockk<TurnoDelNegocio> {
        coEvery { refrescar() } returns "turno-1"
        every { turnoActual() } returns "turno-1"
    }

    private val cafe = CartItem(id = "cafe", type = CartItemType.ProductItem("prod-cafe"), name = "Café", unitPrice = 18_000)

    @After fun cerrar() = dao.close()

    private fun TestScope.vm(activa: Boolean = true): PaymentFlowViewModel {
        prefs.guardar("192.168.1.50", activa, venueId = "venue-1")
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        every { secureStorage.venueName } returns "La Galeterie"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.venuePermissions } returns listOf("payments:create")
        every { areaTicketRepository.session.current() } returns null
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        coEvery { api.cotizar("venue-1", any()) } answers {
            val p = secondArg<JSONObject>()
            val sale = p.getJSONArray("items").getJSONObject(0).optInt("quantity", 1) * 18_000
            RespuestaDelServidor(200, JSONObject().put("quoteId", "q-1").put("saleCents", sale).put("tipCents", p.optInt("tip"))
                .put("totalCents", sale + p.optInt("tip")).put("cobrableEnMaquina", true).toString())
        }
        val maquina = MaquinaDeEfectivo(
            prefs, boveda, dao, api, mockk<SubidaDeLaMaquina>(relaxed = true) { every { aviso } returns kotlinx.coroutines.flow.MutableStateFlow(null) }, turno, RoleManager(secureStorage), printerService,
            secureStorage, canal, backgroundScope, { testScheduler.currentTime }, Random(7), monotonico = { testScheduler.currentTime },
        )
        return PaymentFlowViewModel(
            orderRepository = orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = terminalPaymentService,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = mockk<PaymentSyncService>(relaxed = true),
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = mockk(relaxed = true),
            kdsOrderBus = mockk(relaxed = true),
            printerService = printerService,
            secureStorage = secureStorage,
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true),
            comandaDispatcher = comandas,
            tableSession = TableSession(),
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = mockk(relaxed = true),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            maquinaDeEfectivo = maquina,
        )
    }

    private fun status(ref: String, due: Int, credit: Int = 0, change: Int = 0, cobrando: Boolean = true, estado: String? = null) =
        JSONObject().put("comando", "status").put("cobrando", cobrando).put("Status", estado ?: JSONObject.NULL)
            .put("referenceCC", ref).put("Credit", credit / 100.0).put("Due", due / 100.0)
            .put("changeDispensed", change / 100.0).put("changeBar", "022220222000").toString()

    private fun ecoDelGerente(ref: String, outcome: String = "PAGADO", credit: Int = 20_000, change: Int = 2_000, due: Int = 18_000, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("success", true).put("ack", "TERMINADO").put("referenceCC", ref).put("outcome", outcome)
            .put("resolvedBy", "GERENTE").put("dueCents", due).put("saleCents", due).put("tipCents", 0)
            .put("creditCents", credit).put("changeDispensedCents", change).put("owedCents", 0).apply(extra).toString()

    /** Abre el cobro y lo manda a la máquina; devuelve el folio. */
    private fun TestScope.mandarALaMaquina(vm: PaymentFlowViewModel, cart: CartState = CartState(items = listOf(cafe))): String {
        vm.startPaymentFlow(cart)
        runCurrent()
        vm.cobrarConMaquina()
        runCurrent()
        return requireNotNull(canal.paycash()) { "no salió el paycash" }.getString("referenceCC")
    }

    @Test
    fun `sin la maquina activa todo queda como hoy`() = runTest {
        val vm = vm(activa = false)
        vm.startPaymentFlow(CartState(items = listOf(cafe)))
        runCurrent()

        assertNull(vm.maquina!!.vista.value)
        vm.confirmCashPreset(20_000)
        verify { cashPaymentRepository.processCashPayment(any(), 20_000) }
    }

    @Test
    fun `P1 con la maquina activa el efectivo a mano, Personalizado y Ya pago de otra forma no cobran`() = runTest {
        val vm = vm()
        vm.startPaymentFlow(CartState(items = listOf(cafe)))
        runCurrent()
        assertTrue(vm.maquina!!.vista.value!!.fila.habilitada)

        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.confirmCashPreset(20_000)
        vm.confirmCashCustom(18_000)
        vm.confirmManualChoice(ManualPaymentChoice.Fixed(ManualPaymentMethod.entries.first()))
        runCurrent()

        verify(exactly = 0) { cashPaymentRepository.processCashPayment(any(), any()) }
        assertTrue(vm.state.value is PaymentFlowState.SelectingPaymentMethod)
    }

    @Test
    fun `P1 la maquina congela el carrito antes del paycash y la orden NO nace en el servidor`() = runTest {
        val vm = vm()
        val cart = CartState(items = listOf(cafe))

        val ref = mandarALaMaquina(vm, cart)

        val fila = dao.fila(ref)!!
        assertEquals(EstadoDelIntento.EN_CURSO, fila.estado)
        assertEquals(cart.identidadDeVenta, fila.llaveDeVenta)
        assertEquals("prod-cafe", JSONObject(fila.pedidoJson).getJSONArray("items").getJSONObject(0).getString("productId"))
        assertEquals(18_000, fila.dueCents)
        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any()) }
        verify(exactly = 0) { cashPaymentRepository.processCashPayment(any(), any()) }
    }

    @Test
    fun `P1 el PAGADO de la maquina cobra la venta en efectivo, sin caja y sin abrir el cajon`() = runTest {
        val vm = vm()
        val ref = mandarALaMaquina(vm)

        canal.llega(status(ref, 18_000))
        runCurrent()
        canal.llega(status(ref, 18_000, credit = 20_000, change = 2_000, cobrando = false, estado = "Pago Completado"))
        runCurrent()

        val exito = vm.state.value as PaymentFlowState.Success
        assertEquals(18_000, exito.totalAmount)
        assertEquals(PaymentMethod.CASH, exito.method)
        assertEquals(2_000, exito.changeAmount)
        assertEquals(ColaDelCobro.Maquina(ref), exito.colaDelCobro)
        assertEquals(0, vm.consumeCompletion()!!.remainingBalanceCents)
        coVerify(exactly = 0) { printerService.openCashDrawer(any()) }
        coVerify(exactly = 0) { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) }
    }

    @Test
    fun `P1 R2#1 con la venta esperando a la maquina la tarjeta no sale`() = runTest {
        val vm = vm()
        val ref = mandarALaMaquina(vm)
        advanceTimeBy(3_001)   // el acuse nunca llegó
        runCurrent()
        assertEquals(EstadoDelIntento.ESPERANDO, dao.fila(ref)!!.estado)
        assertTrue(vm.maquina!!.vista.value!!.ventaEnDuda)

        vm.selectPaymentMethod(PaymentMethod.CARD)
        runCurrent()

        assertTrue(vm.state.value is PaymentFlowState.SelectingPaymentMethod)
    }

    @Test
    fun `P1 Codex r1 #1 mientras la maquina cotiza, F4 no saca la tarjeta aunque la fila todavia no exista`() = runTest {
        val vm = vm()
        val respuesta = kotlinx.coroutines.CompletableDeferred<RespuestaDelServidor>()
        coEvery { api.cotizar("venue-1", any()) } coAnswers { respuesta.await() }
        vm.startPaymentFlow(CartState(items = listOf(cafe)))
        runCurrent()

        vm.cobrarConMaquina()
        vm.selectPaymentMethod(PaymentMethod.CARD)   // F4 en el mismo instante
        runCurrent()

        assertTrue(vm.state.value is PaymentFlowState.SelectingPaymentMethod)
        assertTrue(dao.todas().isEmpty())
        assertTrue(vm.maquina!!.vista.value!!.ventaEnDuda)
        respuesta.complete(
            RespuestaDelServidor(200, JSONObject().put("quoteId", "q-1").put("saleCents", 18_000).put("tipCents", 0)
                .put("totalCents", 18_000).put("cobrableEnMaquina", true).toString()),
        )
        runCurrent()
        assertTrue("el primer toque sigue y manda el cobro a la máquina", canal.paycash() != null)
        vm.selectPaymentMethod(PaymentMethod.CARD)
        runCurrent()
        assertTrue(vm.state.value is PaymentFlowState.SelectingPaymentMethod)
    }

    @Test
    fun `P1 Codex r2 H1 con la maquina desemparejada, la venta con su efectivo en duda no se cobra con tarjeta ni a mano`() = runTest {
        val vm = vm(activa = false)   // la PC tuvo máquina (su IP se queda) y el gerente la desemparejó
        val cart = CartState(items = listOf(cafe))
        dao.insertar(
            com.avoqado.pos.maquina.CobroEnMaquinaEntity(
                "AQ000000000007", "venue-1", cart.llaveParaLaMaquina, "{}", 18_000, 0, 18_000, null, "q-1", 1, EstadoDelIntento.ESPERANDO,
                dudaSubida = true, versionDeLaDuda = 1,
            ),
        )
        vm.startPaymentFlow(cart)
        runCurrent()
        assertNull("sin la máquina activa no hay su pantalla…", vm.maquina!!.vista.value)
        assertTrue("…pero Cobrar dice que la venta espera a la máquina", vm.maquina!!.bloqueadaSinMaquina.value)

        vm.selectPaymentMethod(PaymentMethod.CARD)   // F4
        runCurrent()
        assertTrue("la tarjeta no sale", vm.state.value is PaymentFlowState.SelectingPaymentMethod)

        vm.selectPaymentMethod(PaymentMethod.CASH)
        runCurrent()
        assertTrue("el efectivo a mano tampoco", vm.state.value is PaymentFlowState.SelectingPaymentMethod)
        vm.processCashPayment(20_000)   // «Personalizado» o un billete sugerido
        runCurrent()
        assertTrue("ni con el monto a mano", vm.state.value is PaymentFlowState.SelectingPaymentMethod)
        verify(exactly = 0) { cashPaymentRepository.processCashPayment(any(), any()) }
    }

    @Test
    fun `Codex r2 H1 con la maquina desemparejada, una venta sin nada en la maquina se cobra como siempre`() = runTest {
        val vm = vm(activa = false)
        vm.startPaymentFlow(CartState(items = listOf(cafe)))
        runCurrent()
        assertFalse(vm.maquina!!.bloqueadaSinMaquina.value)

        vm.selectPaymentMethod(PaymentMethod.CASH)
        runCurrent()

        assertTrue(vm.state.value is PaymentFlowState.CollectingCashAmount)
    }

    @Test
    fun `P1 Codex r2 H5 la cotizacion de A que regresa con B en pantalla no manda el cobro ni escribe en B`() = runTest {
        val vm = vm()
        val respuesta = kotlinx.coroutines.CompletableDeferred<RespuestaDelServidor>()
        coEvery { api.cotizar("venue-1", any()) } coAnswers { respuesta.await() }
        vm.startPaymentFlow(CartState(items = listOf(cafe)))   // A
        runCurrent()
        vm.cobrarConMaquina(); runCurrent()

        vm.startPaymentFlow(CartState(items = listOf(cafe.copy(id = "te", name = "Té"))))   // Esc, y la cajera abre B
        runCurrent()
        respuesta.complete(
            RespuestaDelServidor(200, JSONObject().put("quoteId", "q-1").put("saleCents", 18_000).put("tipCents", 0)
                .put("totalCents", 18_000).put("cobrableEnMaquina", true).toString()),
        )
        runCurrent()

        assertNull("nadie ve ya la venta A: su paycash no sale", canal.paycash())
        assertTrue(dao.todas().isEmpty())
        val b = vm.maquina!!.vista.value!!
        assertNull(b.cobro)
        assertNull("lo de A no se escribe en B", b.aviso)
        assertFalse(b.ventaEnDuda)
    }

    @Test
    fun `P1 Codex r2 H5 el PAGADO de A que llega con B en pantalla no cobra B, y A queda con su ticket pendiente`() = runTest {
        val vm = vm()
        val refA = mandarALaMaquina(vm)
        vm.startPaymentFlow(CartState(items = listOf(cafe.copy(id = "te", name = "Té"))))   // B
        runCurrent()

        canal.llega(status(refA, 18_000)); runCurrent()
        canal.llega(status(refA, 18_000, credit = 20_000, change = 2_000, cobrando = false, estado = "Pago Completado")); runCurrent()

        assertTrue("B sigue eligiendo método", vm.state.value is PaymentFlowState.SelectingPaymentMethod)
        assertEquals(EstadoDelIntento.PAGADO, dao.fila(refA)!!.estado)
        val b = vm.maquina!!.vista.value!!
        assertNull(b.cobro)
        assertEquals("el ticket de A se ofrece con su botón", 1, b.ticketsSinImprimir)
        coVerify(exactly = 0) { printerService.manualPrintReceipt(any()) }
    }

    @Test
    fun `Codex r1 #1 si la cotizacion no deja cobrar en la maquina, la venta se suelta y la tarjeta vuelve`() = runTest {
        val vm = vm()
        coEvery { api.cotizar("venue-1", any()) } returns RespuestaDelServidor(
            200, JSONObject().put("cobrableEnMaquina", false).put("motivo", "CENTAVOS").toString(),
        )
        vm.startPaymentFlow(CartState(items = listOf(cafe)))
        runCurrent()

        vm.cobrarConMaquina()
        runCurrent()

        assertNull(canal.paycash())
        assertFalse(vm.maquina!!.vista.value!!.ventaEnDuda)
        vm.selectPaymentMethod(PaymentMethod.CARD)
        runCurrent()
        assertTrue(vm.state.value is PaymentFlowState.SelectingTerminal)
    }

    @Test
    fun `P1 Codex r1 #7 tras reiniciar, la pantalla de la maquina recupera la duda de esta venta y el gerente la resuelve`() = runTest {
        val vm = vm()
        every { secureStorage.venuePermissions } returns listOf("payments:create", "cash-machine:resolve")
        coEvery { api.resolverDuda("venue-1", "AQ000000000007", any()) } returns RespuestaDelServidor(200, ecoDelGerente("AQ000000000007"))
        val cart = CartState(items = listOf(cafe))
        // Lo que dejó el proceso anterior: la venta quedó «Esperando a la máquina» y la app se cerró.
        dao.insertar(
            com.avoqado.pos.maquina.CobroEnMaquinaEntity(
                "AQ000000000007", "venue-1", cart.llaveParaLaMaquina, "{}", 18_000, 0, 18_000, null, "q-1", 1, EstadoDelIntento.ESPERANDO,
            ),
        )

        vm.startPaymentFlow(cart)
        runCurrent()

        assertEquals(EstadoDelCobro.Esperando("AQ000000000007", 18_000), vm.maquina!!.vista.value!!.cobro)
        vm.maquina!!.resolver(ResolucionDelGerente(Desenlace.PAGADO, 20_000, 2_000, null, null, "Ticket 0042"))
        runCurrent()
        assertEquals(18_000 to 2_000, (vm.state.value as PaymentFlowState.Success).let { it.totalAmount to it.changeAmount })
    }

    @Test
    fun `Codex r1 #7 las otras dudas de esta PC se ven en Cobrar y resolverlas no cobra la venta en pantalla`() = runTest {
        val vm = vm()
        every { secureStorage.venuePermissions } returns listOf("payments:create", "cash-machine:resolve")
        coEvery { api.resolverDuda("venue-1", "AQ000000000008", any()) } returns
            RespuestaDelServidor(200, ecoDelGerente("AQ000000000008", credit = 5_000, change = 0, due = 5_000))
        dao.insertar(
            com.avoqado.pos.maquina.CobroEnMaquinaEntity(
                "AQ000000000008", "venue-1", "otra-venta", "{}", 5_000, 0, 5_000, null, null, 1, EstadoDelIntento.ESPERANDO,
            ),
        )
        vm.startPaymentFlow(CartState(items = listOf(cafe)))
        runCurrent()

        assertEquals(listOf("AQ000000000008"), vm.maquina!!.vista.value!!.enEspera.map { it.referenceCC })
        assertTrue(vm.maquina!!.resolverOtra("AQ000000000008", ResolucionDelGerente(Desenlace.PAGADO, 5_000, 0, null, null, "x"))
            is com.avoqado.pos.maquina.ResultadoDeResolver.Resuelto)
        runCurrent()
        assertTrue("la venta en pantalla sigue sin cobrar", vm.state.value is PaymentFlowState.SelectingPaymentMethod)
    }

    @Test
    fun `R4-4 el gerente resuelve con su sesion que la maquina cobro y la venta queda cobrada`() = runTest {
        val vm = vm()
        every { secureStorage.venuePermissions } returns listOf("payments:create", "cash-machine:resolve")
        val ref = mandarALaMaquina(vm)
        coEvery { api.resolverDuda("venue-1", ref, any()) } returns RespuestaDelServidor(200, ecoDelGerente(ref))
        advanceTimeBy(3_001)
        runCurrent()
        assertTrue(vm.maquina!!.vista.value!!.cobro is EstadoDelCobro.Esperando)

        vm.maquina!!.resolver(ResolucionDelGerente(Desenlace.PAGADO, 20_000, 2_000, null, null, "Ticket 0042"))
        runCurrent()

        val exito = vm.state.value as PaymentFlowState.Success
        assertEquals(18_000 to 2_000, exito.totalAmount to exito.changeAmount)
    }

    @Test
    fun `el cliente cancela, recoge su dinero y la venta se puede pagar con tarjeta`() = runTest {
        val vm = vm()
        val ref = mandarALaMaquina(vm)
        canal.llega(status(ref, 18_000))
        runCurrent()
        canal.llega(status(ref, 18_000, credit = 5_000))
        runCurrent()

        vm.maquina!!.cancelarPorElCliente()
        runCurrent()
        assertEquals("1", JSONObject(canal.enviados.last()).getString("cancelvalue"))
        canal.llega(status(ref, 18_000, credit = 5_000, change = 5_000, cobrando = false, estado = "Transacción Cancelada"))
        runCurrent()
        assertEquals(Desenlace.CANCELADO, (vm.maquina!!.vista.value!!.cobro as EstadoDelCobro.Cerrado).desenlace)
        assertTrue("CANCELADO no cobra la venta", vm.state.value is PaymentFlowState.SelectingPaymentMethod)

        vm.maquina!!.listo()
        runCurrent()
        assertNull(vm.maquina!!.vista.value!!.cobro)
        assertTrue(!vm.maquina!!.vista.value!!.ventaEnDuda)
        vm.selectPaymentMethod(PaymentMethod.CARD)
        runCurrent()
        assertTrue(vm.state.value is PaymentFlowState.SelectingTerminal)
    }

    @Test
    fun `R5#4 un carrito con premio de sellos no se manda a la maquina y lo dice`() = runTest {
        val vm = vm()
        vm.startPaymentFlow(CartState(items = listOf(cafe), pendingStampReward = PremioPorAplicar("premio-1", "Café gratis", "FREE_PRODUCT", null)))
        runCurrent()

        vm.cobrarConMaquina()
        runCurrent()

        assertEquals(TEXTO_CON_PREMIO, vm.maquina!!.vista.value!!.aviso)
        assertTrue(dao.todas().isEmpty())
        assertNull(canal.paycash())
    }

    // MARK: - El ticket (C7)

    private fun TestScope.pagar(vm: PaymentFlowViewModel, credit: Int = 20_000, change: Int = 2_000): String {
        val ref = mandarALaMaquina(vm)
        canal.llega(status(ref, 18_000))
        runCurrent()
        canal.llega(status(ref, 18_000, credit = credit, change = change, cobrando = false, estado = "Pago Completado"))
        runCurrent()
        return ref
    }

    @Test
    fun `P1 C7 el primer PAGADO imprime una vez con la foto congelada y marca ticketImpreso con el resultado real`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.manualPrintReceipt(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()

        val ref = pagar(vm)

        val r = impresos.single()
        assertEquals("el folio de la máquina: la orden todavía no existe", ref, r.orderNumber)
        assertEquals("Efectivo", r.paymentMethod)
        assertEquals(20_000 to 2_000, r.cashTendered to r.changeAmount)
        assertEquals(18_000, r.total)
        assertEquals(ReceiptCashMachine(ref, owedCents = null, priceToConfirm = false), r.cashMachine)
        assertEquals(TicketDelCobro.IMPRESO, dao.fila(ref)!!.ticketImpreso)
        coVerify(exactly = 0) { printerService.autoPrintReceipt(any()) }

        vm.reprintReceipt()
        runCurrent()
        assertEquals("la reimpresión usa la misma foto", impresos[0], impresos[1])
    }

    @Test
    fun `C7 con cambio que la maquina no dio el ticket dice cuanto le debemos`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.manualPrintReceipt(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)

        pagar(vm(), credit = 20_000, change = 1_500)   // tenía que dar $20.00: dio $15.00

        assertEquals(500L, impresos.single().cashMachine!!.owedCents)
    }

    @Test
    fun `C7 sin papel ni impresora el ticket no queda como impreso`() = runTest {
        coEvery { printerService.manualPrintReceipt(any()) } returns PrinterService.PrintOutcome.NoPrinter

        val ref = pagar(vm())

        assertEquals(TicketDelCobro.PENDIENTE, dao.fila(ref)!!.ticketImpreso)
    }

    @Test
    fun `P1 C7 un cierre repetido no reimprime ni vuelve a cobrar la venta`() = runTest {
        coEvery { printerService.manualPrintReceipt(any()) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        val ref = pagar(vm)
        val exito = vm.state.value

        canal.llega(status(ref, 18_000, credit = 20_000, change = 2_000, cobrando = false, estado = "Pago Completado"))
        runCurrent()

        coVerify(exactly = 1) { printerService.manualPrintReceipt(any()) }
        assertSame(exito, vm.state.value)
    }

    @Test
    fun `R5#9 el ticket hecho sin red dice precio por confirmar`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.manualPrintReceipt(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        coEvery { api.cotizar(any(), any()) } throws IOException("sin red")

        pagar(vm)

        assertTrue(impresos.single().cashMachine!!.priceToConfirm)
    }

    @Test
    fun `C7 GUARDADO no imprime nada`() = runTest {
        val vm = vm()
        val ref = mandarALaMaquina(vm)
        canal.llega(status(ref, 18_000))
        runCurrent()
        canal.llega(status(ref, 18_000, credit = 5_000))
        runCurrent()
        advanceTimeBy(120_001)   // «¿Sigues ahí?»
        runCurrent()
        advanceTimeBy(30_001)    // nadie contestó: el abandono guarda lo que entró
        runCurrent()
        assertEquals("2", JSONObject(canal.enviados.last()).getString("cancelvalue"))

        canal.llega(status(ref, 18_000, credit = 5_000, change = 0, cobrando = false, estado = "Transacción Cancelada"))
        runCurrent()

        assertEquals(Desenlace.GUARDADO, (vm.maquina!!.vista.value!!.cobro as EstadoDelCobro.Cerrado).desenlace)
        coVerify(exactly = 0) { printerService.manualPrintReceipt(any()) }
        coVerify(exactly = 0) { printerService.autoPrintReceipt(any()) }
    }

    @Test
    fun `R3#11 un CANCELADO que no devolvio todo imprime su comprobante con el folio, una vez`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.manualPrintReceipt(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        val ref = mandarALaMaquina(vm)
        canal.llega(status(ref, 18_000))
        runCurrent()
        canal.llega(status(ref, 18_000, credit = 5_000))
        runCurrent()
        vm.maquina!!.cancelarPorElCliente()
        runCurrent()

        val cierre = status(ref, 18_000, credit = 5_000, change = 3_000, cobrando = false, estado = "Transacción Cancelada")
        canal.llega(cierre)
        runCurrent()
        canal.llega(cierre)
        runCurrent()

        val c = impresos.single()
        assertEquals(KIND_COMPROBANTE_DE_LA_MAQUINA, c.kind)
        assertEquals(ref, c.orderNumber)
        assertEquals(2_000L, c.cashMachine!!.owedCents)
        assertEquals(TicketDelCobro.IMPRESO, dao.fila(ref)!!.ticketImpreso)
    }

    @Test
    fun `P1 Codex r1 #11 un PAGADO del gerente que quedo como incidencia no cobra la venta ni imprime su ticket`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.manualPrintReceipt(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        every { secureStorage.venuePermissions } returns listOf("payments:create", "cash-machine:resolve")
        val ref = mandarALaMaquina(vm)
        coEvery { api.resolverDuda("venue-1", ref, any()) } returns RespuestaDelServidor(
            202,
            ecoDelGerente(ref, change = 0) {
                put("incident", "NO_SE_PUDO_REGISTRAR").put("incidentId", "cinc000000000000000000006").put("motivo", "VENTA_RECHAZADA")
                    .put("owedCents", 2_000)
            },
        )
        advanceTimeBy(3_001)
        runCurrent()

        vm.maquina!!.resolver(ResolucionDelGerente(Desenlace.PAGADO, 20_000, 0, null, null, "Ticket 0042"))
        runCurrent()

        assertTrue("no hay venta", vm.state.value is PaymentFlowState.SelectingPaymentMethod)
        assertEquals("Registrado como incidencia: la venta ya no se pudo armar", vm.maquina!!.vista.value!!.incidencia)
        assertNull("se ve el resultado, no la duda", vm.maquina!!.vista.value!!.cobro)
        val papel = impresos.single()
        assertEquals("sólo el comprobante del dinero, con su folio", KIND_COMPROBANTE_DE_LA_MAQUINA, papel.kind)
        assertEquals(ref, papel.orderNumber)
        assertEquals(2_000L, papel.cashMachine!!.owedCents)
    }

    @Test
    fun `P1 Codex r1 #14 el ticket sale con los renglones que cotizo el servidor, no con los del carrito`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.manualPrintReceipt(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        coEvery { api.cotizar("venue-1", any()) } returns RespuestaDelServidor(
            200,
            JSONObject().put("quoteId", "q-1").put("saleCents", 18_000).put("tipCents", 0).put("totalCents", 18_000).put("cobrableEnMaquina", true)
                .put("renglones", org.json.JSONArray().put(JSONObject().put("name", "Café (precio del servidor)").put("quantity", 1).put("unitCents", 18_000).put("totalCents", 18_000)))
                .toString(),
        )

        pagar(vm)

        assertEquals(listOf("Café (precio del servidor)" to 18_000), impresos.single().items.map { it.name to it.totalPrice })
    }

    @Test
    fun `P1 Codex r1 #17 la comanda de la venta de la maquina lleva el folio de su orden para la pantalla de cocina`() = runTest {
        coEvery { printerService.manualPrintReceipt(any()) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()

        val ref = pagar(vm)
        runCurrent()

        coVerify {
            comandas.dispatch(
                any(), any(), any(), any(), any(), any(), any(), any(), false, "sale:maquina-$ref",
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    @Test
    fun `P1 Codex r1 #12 un ticket que no salio se ofrece en Cobrar con su boton`() = runTest {
        coEvery { printerService.manualPrintReceipt(any()) } returns PrinterService.PrintOutcome.NoPrinter andThen PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        pagar(vm)
        assertEquals(TicketDelCobro.PENDIENTE, dao.todas().single().ticketImpreso)

        assertEquals(1, vm.maquina!!.vista.value!!.ticketsSinImprimir)
        vm.maquina!!.imprimirTicketsPendientes()
        runCurrent()

        assertEquals(TicketDelCobro.IMPRESO, dao.todas().single().ticketImpreso)
        assertEquals(0, vm.maquina!!.vista.value!!.ticketsSinImprimir)
    }

    @Test
    fun `una venta dividida no se cobra en la maquina`() = runTest {
        val vm = vm()
        vm.setSplitConfig("EQUALPARTS", numberOfParts = 2)
        vm.startPaymentFlow(CartState(items = listOf(cafe)))
        runCurrent()

        assertEquals(TEXTO_SOLO_VENTA_COMPLETA, vm.maquina!!.vista.value!!.fila.motivo)
        vm.cobrarConMaquina()
        runCurrent()
        assertNull(canal.paycash())
    }
}
