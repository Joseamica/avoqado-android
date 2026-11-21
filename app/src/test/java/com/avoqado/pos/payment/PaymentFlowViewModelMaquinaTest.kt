package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.giftcards.domain.aCartState
import com.avoqado.pos.giftcards.domain.aFoto
import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.maquina.AjustesDeLaMaquinaTest
import com.avoqado.pos.maquina.BovedaDeLaMaquina
import com.avoqado.pos.maquina.CierresDeLaMaquinaApi
import com.avoqado.pos.maquina.CobroEnMaquinaSobreSqlite
import com.avoqado.pos.maquina.Desenlace
import com.avoqado.pos.maquina.EstadoDelCobro
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
import com.avoqado.pos.maquina.resolverConElEco
import com.avoqado.pos.maquina.ticketDeLaFila
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
    /** La comanda real, espiada: la venta de la máquina NO la manda en la 1a (recorte R3). */
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
    /** El `deviceId` de esta PC para el SERVIDOR (lo aprendió su primer POST, r3 #7). */
    private val estaPc = "cterm00000000000000esta1"
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
        prefs.recordarDispositivo(estaPc)
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
        JSONObject().put("success", true).put("ack", "TERMINADO").put("deviceId", estaPc).put("referenceCC", ref).put("outcome", outcome)
            .put("resolvedBy", "GERENTE").put("dueCents", due).put("saleCents", due).put("tipCents", 0)
            .put("creditCents", credit).put("changeDispensedCents", change).put("owedCents", 0).apply(extra).toString()

    /**
     * Recorte R2: lo que hace la subida. La duda llega al SERVIDOR (RECIBIDO, con la evidencia de la fila), el gerente la resuelve en el
     * panel y la consulta trae cómo terminó.
     */
    private suspend fun elPanelResuelve(ref: String, eco: String): Boolean {
        val duda = dao.fila(ref)!!
        dao.marcarDudaAbierta(ref, 1, duda.mensajesCrudos, duda.creditCents)
        return dao.resolverConElEco(dao.fila(ref)!!, JSONObject(eco), eco)
    }

    private fun ningunaComanda() = io.mockk.coVerify(exactly = 0) {
        comandas.dispatch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
    }

    /** Abre el cobro y lo manda a la máquina; devuelve el folio. */
    private fun TestScope.mandarALaMaquina(vm: PaymentFlowViewModel, cart: CartState = CartState(nacioEnEstaCaja = true, items = listOf(cafe))): String {
        vm.startPaymentFlow(cart)
        runCurrent()
        vm.cobrarConMaquina()
        runCurrent()
        return requireNotNull(canal.paycash()) { "no salió el paycash" }.getString("referenceCC")
    }

    /**
     * Codex código POS r5 #2 (sigue abierto) y r6 (nuevo, b) — decisión A: un carrito traído con «Restaurar» nunca va a la máquina. Los dos
     * casos de Codex, de $80: (1) la tarjeta la mandó la versión ANTERIOR (su pendiente no trae la llave de la venta) y tras actualizar se
     * acepta «Restaurar de todos modos»; (2) la tarjeta se APROBÓ con la versión nueva, la PC se cerró antes de retirar la copia y se restaura
     * 11 minutos después (el aviso de aprobación ya venció; la marca permanente sólo dice la orden). En los dos la lista de tarjetas no dice
     * nada de esta copia (eso devuelve el servicio real) y la máquina habría cobrado otros $80. Ahora no: la regla es «restaurado».
     */
    @Test
    fun `P1 Codex POS r5 #2 y r6 un carrito restaurado no se cobra en la maquina, diga lo que diga la lista de tarjetas`() = runTest {
        val pan = CartItem(id = "pan", type = CartItemType.ProductItem("prod-pan"), name = "Pan", unitPrice = 8_000)
        val restaurado = CartState(items = listOf(pan), carritoGuardadoId = "guardado-80", vinoDeCarritoGuardado = true, llaveDeLaMaquina = "carrito:guardado-80")
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        val vm = vm()

        vm.startPaymentFlow(restaurado)
        assertFalse("se lee al tocar, aunque la vista vaya un cuadro atrás", vm.maquina!!.apartar())
        runCurrent()
        val fila = vm.maquina!!.vista.value!!.fila
        assertFalse(fila.habilitada)
        assertEquals(com.avoqado.pos.maquina.TEXTO_VENTA_RESTAURADA, fila.motivo)
        vm.cobrarConMaquina()
        runCurrent()

        assertNull("no salió el paycash", canal.paycash())
        assertTrue("nada quedó en disco", dao.todas().isEmpty())
        coVerify(exactly = 0) { api.cotizar(any(), any()) }

        // Un carrito NUEVO con lo mismo sí se cobra en la máquina: la regla es «restaurado», no el producto.
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(pan)))
        runCurrent()
        assertTrue(vm.maquina!!.vista.value!!.fila.habilitada)
    }

    /**
     * Codex código POS r6 (nuevo, b) — decisión B: el carrito ligado a una reserva (o a una clase) deja la fila de la máquina en gris con su
     * razón, con internet y sin él; tocarla no cotiza ni manda nada. Uno de catálogo sigue como hoy.
     */
    @Test
    fun `P1 Codex POS r6 la reserva deja la fila de la maquina en gris con su razon`() = runTest {
        val vm = vm()

        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe), reservationId = "res-1"))
        runCurrent()
        val fila = vm.maquina!!.vista.value!!.fila
        assertFalse(fila.habilitada)
        assertEquals(com.avoqado.pos.maquina.TEXTO_RESERVA_EN_CAJA, fila.motivo)
        vm.cobrarConMaquina()
        runCurrent()
        assertNull(canal.paycash())
        coVerify(exactly = 0) { api.cotizar(any(), any()) }

        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe)))
        runCurrent()
        assertTrue("el carrito de catálogo sí", vm.maquina!!.vista.value!!.fila.habilitada)
    }

    /**
     * Codex código POS r7 #1: la cajera manda la venta de $80 a la tarjeta, elige «Salir, queda pendiente» y conserva el carrito; la tarjeta se
     * aprueba. Al volver a «Cobrar» con el MISMO carrito, la máquina estaba bloqueada al minuto 9 y habilitada al 11: vencía el aviso y el
     * carrito en memoria ya no traía la orden del cobro. Si el cliente depositaba, pagaba $160 por $80. Regla pegajosa del coordinador: la
     * venta que mandó una tarjeta a la terminal nunca se cobra en la máquina (nace al mandarla y no vence).
     */
    @Test
    fun `P1 Codex POS r7 #1 la venta que mando una tarjeta nunca va a la maquina, tampoco al minuto 11 con el carrito abierto`() = runTest {
        val pan = CartItem(id = "pan", type = CartItemType.ProductItem("prod-pan"), name = "Pan", unitPrice = 8_000)
        // El carrito de CartViewModel: el cobro le avisa que salió una tarjeta (como `marcarIntentoConTarjeta`).
        var carrito = CartState(nacioEnEstaCaja = true, items = listOf(pan))
        val alMandarTarjeta = { id: String -> if (carrito.identidadDeVenta == id) carrito = carrito.copy(intentoConTarjeta = true) }
        val vm = vm()
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Success(listOf(com.avoqado.pos.payment.data.OnlineTerminal(terminalId = "t1", name = "Terminal 1", isOnline = true, hasSocket = true)))
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(com.avoqado.pos.payment.data.model.CreateOrderResponse(success = true, data = com.avoqado.pos.payment.data.model.OrderData(id = "orden-80", total = 80.0)))
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            com.avoqado.pos.payment.data.TerminalPaymentResult.Undetermined("Cobro sin confirmar", "req-80")

        vm.startPaymentFlow(carrito, onIntentoConTarjeta = alMandarTarjeta)
        runCurrent()
        assertTrue("antes de la tarjeta, la máquina sí", vm.maquina!!.vista.value!!.fila.habilitada)
        vm.selectPaymentMethod(PaymentMethod.CARD)
        runCurrent()
        vm.selectTerminalAndPay("t1")
        runCurrent()
        coVerify(exactly = 1) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue("la marca nace al mandar la tarjeta", carrito.intentoConTarjeta)
        vm.salirDejandoPendiente()

        advanceTimeBy(11 * 60_000L)
        runCurrent()
        // La tarjeta se aprobó y su aviso ya venció; el carrito en memoria no trae la orden: la lista de tarjetas no dice nada de esta venta.
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        vm.startPaymentFlow(carrito, onIntentoConTarjeta = alMandarTarjeta)
        runCurrent()

        val fila = vm.maquina!!.vista.value!!.fila
        assertFalse(fila.habilitada)
        assertEquals(com.avoqado.pos.maquina.TEXTO_VENTA_CON_TARJETA, fila.motivo)
        assertFalse("se lee al tocar", vm.maquina!!.apartar())
        vm.cobrarConMaquina()
        runCurrent()
        assertNull("no salió el paycash", canal.paycash())
        assertTrue("nada quedó en disco", dao.todas().isEmpty())
        coVerify(exactly = 0) { api.cotizar(any(), any()) }

        // Un carrito NUEVO con lo mismo sí se cobra en la máquina: la regla es «esta venta mandó una tarjeta», no el producto.
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(pan)))
        runCurrent()
        assertTrue(vm.maquina!!.vista.value!!.fila.habilitada)
    }

    /**
     * Codex código POS r8 #2 (F3): antes de actualizar, la cajera deja pendiente una tarjeta de $80; sobre ese carrito intenta saldo de regalo
     * y el rechazo deja una foto recuperable. Tras actualizar, «Venta por recuperar» la restaura con el formato de la versión anterior (sin la
     * marca de tarjeta) y la máquina quedaba habilitada: si la tarjeta se aprobó, $160 por $80. Todo lo que no es un carrito nuevo tecleado
     * aquí es «restaurado», venga de donde venga y en el formato que sea.
     */
    @Test
    fun `P1 Codex POS r8 #2 una venta por recuperar con el formato de la version anterior nunca va a la maquina`() = runTest {
        val pan = CartItem(id = "pan", type = CartItemType.ProductItem("prod-pan"), name = "Pan", unitPrice = 8_000)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val foto = com.avoqado.pos.giftcards.domain.FotoDeLaVenta.serializer()
        val deHoy = json.encodeToJsonElement(foto, CartState(items = listOf(pan), intentoConTarjeta = true).aFoto(null))
        val deAntes = kotlinx.serialization.json.JsonObject(
            (deHoy as kotlinx.serialization.json.JsonObject).filterKeys { it != "intentoConTarjeta" },
        )
        val recuperada = json.decodeFromJsonElement(foto, deAntes).aCartState()
        assertFalse("el formato de antes no trae la marca", recuperada.intentoConTarjeta)
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        val vm = vm()

        vm.startPaymentFlow(recuperada)
        assertFalse("se lee al tocar", vm.maquina!!.apartar())
        runCurrent()
        val fila = vm.maquina!!.vista.value!!.fila
        assertFalse(fila.habilitada)
        assertEquals(com.avoqado.pos.maquina.TEXTO_VENTA_RESTAURADA, fila.motivo)
        vm.cobrarConMaquina()
        runCurrent()

        assertNull("no salió el paycash", canal.paycash())
        assertTrue("nada quedó en disco", dao.todas().isEmpty())
        coVerify(exactly = 0) { api.cotizar(any(), any()) }
    }

    /** Codex código POS r7 #1: la marca viaja con la copia guardada y con la foto de la venta, y vuelve al restaurar. */
    @Test
    fun `P1 Codex POS r7 #1 la marca de tarjeta viaja con la foto de la venta`() {
        val conTarjeta = CartState(nacioEnEstaCaja = true, items = listOf(cafe), intentoConTarjeta = true)
        assertTrue(conTarjeta.aFoto(null).aCartState().intentoConTarjeta)
        assertFalse(CartState(items = listOf(cafe)).aFoto(null).aCartState().intentoConTarjeta)
    }

    @Test
    fun `sin la maquina activa todo queda como hoy`() = runTest {
        val vm = vm(activa = false)
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe)))
        runCurrent()

        assertNull(vm.maquina!!.vista.value)
        vm.confirmCashPreset(20_000)
        verify { cashPaymentRepository.processCashPayment(any(), 20_000) }
    }

    @Test
    fun `P1 con la maquina activa el efectivo a mano, Personalizado y Ya pago de otra forma no cobran`() = runTest {
        val vm = vm()
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe)))
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
        val cart = CartState(nacioEnEstaCaja = true, items = listOf(cafe))

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
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe)))
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
        val cart = CartState(nacioEnEstaCaja = true, items = listOf(cafe))
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
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe)))
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
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe)))   // A
        runCurrent()
        vm.cobrarConMaquina(); runCurrent()

        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe.copy(id = "te", name = "Té"))))   // Esc, y la cajera abre B
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
    fun `P1 Codex r2 H5 el PAGADO de A que llega con B en pantalla no cobra B, y el papel de A sale de su fila sin comanda`() = runTest {
        coEvery { printerService.imprimirOriginal(any()) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        val refA = mandarALaMaquina(vm)
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe.copy(id = "te", name = "Té"))))   // B
        runCurrent()

        canal.llega(status(refA, 18_000)); runCurrent()
        canal.llega(status(refA, 18_000, credit = 20_000, change = 2_000, cobrando = false, estado = "Pago Completado")); runCurrent()

        assertTrue("B sigue eligiendo método", vm.state.value is PaymentFlowState.SelectingPaymentMethod)
        assertEquals(EstadoDelIntento.PAGADO, dao.fila(refA)!!.estado)
        val b = vm.maquina!!.vista.value!!
        assertNull(b.cobro)
        // Su papel sale de SU fila, por el reclamo, una vez. Sin comanda a cocina en la 1a (recorte R3).
        coVerify(exactly = 1) { printerService.imprimirOriginal(match { it.orderNumber == refA }) }
        assertEquals(TicketDelCobro.IMPRESO, dao.fila(refA)!!.ticketImpreso)
        canal.llega(status(refA, 18_000, credit = 20_000, change = 2_000, cobrando = false, estado = "Pago Completado")); runCurrent()
        coVerify(exactly = 1) { printerService.imprimirOriginal(any()) }
        ningunaComanda()
    }

    @Test
    fun `Codex r1 #1 si la cotizacion no deja cobrar en la maquina, la venta se suelta y la tarjeta vuelve`() = runTest {
        val vm = vm()
        coEvery { api.cotizar("venue-1", any()) } returns RespuestaDelServidor(
            200, JSONObject().put("cobrableEnMaquina", false).put("motivo", "CENTAVOS").toString(),
        )
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe)))
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
    fun `P1 recorte R2 tras reiniciar, la pantalla recupera la duda de esta venta y cuando el panel dice PAGADO la venta queda cobrada`() = runTest {
        coEvery { printerService.imprimirOriginal(any()) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        val cart = CartState(nacioEnEstaCaja = true, items = listOf(cafe))
        // Lo que dejó el proceso anterior: la venta quedó «Esperando a la máquina» y la app se cerró.
        dao.insertar(
            com.avoqado.pos.maquina.CobroEnMaquinaEntity(
                "AQ000000000007", "venue-1", cart.llaveParaLaMaquina, "{}", 18_000, 0, 18_000, null, "q-1", 1, EstadoDelIntento.ESPERANDO,
            ),
        )

        vm.startPaymentFlow(cart)
        runCurrent()

        assertEquals(EstadoDelCobro.Esperando("AQ000000000007", 18_000), vm.maquina!!.vista.value!!.cobro)

        elPanelResuelve("AQ000000000007", ecoDelGerente("AQ000000000007"))
        runCurrent()

        assertEquals(18_000 to 2_000, (vm.state.value as PaymentFlowState.Success).let { it.totalAmount to it.changeAmount })
        coVerify(exactly = 1) { printerService.imprimirOriginal(match { it.orderNumber == "AQ000000000007" }) }
        ningunaComanda()
    }

    @Test
    fun `R4-4 y recorte R2 la caja dice que el panel lo resuelve, y cuando el panel dice PAGADO la venta queda cobrada`() = runTest {
        val vm = vm()
        val ref = mandarALaMaquina(vm)
        advanceTimeBy(3_001)
        runCurrent()
        assertTrue(vm.maquina!!.vista.value!!.cobro is EstadoDelCobro.Esperando)

        elPanelResuelve(ref, ecoDelGerente(ref))
        runCurrent()

        val exito = vm.state.value as PaymentFlowState.Success
        assertEquals(18_000 to 2_000, exito.totalAmount to exito.changeAmount)
    }

    @Test
    fun `recorte R2 cuando el panel dice CANCELADO la pantalla lo dice y con Listo la tarjeta vuelve`() = runTest {
        val vm = vm()
        val ref = mandarALaMaquina(vm)
        advanceTimeBy(3_001)
        runCurrent()

        elPanelResuelve(ref, ecoDelGerente(ref, outcome = "CANCELADO", credit = 0, change = 0))
        runCurrent()

        assertEquals(Desenlace.CANCELADO, vm.maquina!!.vista.value!!.resuelto)
        assertNull("se ve lo que decidió el panel, no la duda", vm.maquina!!.vista.value!!.cobro)
        assertTrue("CANCELADO no cobra la venta", vm.state.value is PaymentFlowState.SelectingPaymentMethod)
        vm.maquina!!.listo(); runCurrent()
        vm.selectPaymentMethod(PaymentMethod.CARD); runCurrent()
        assertTrue(vm.state.value is PaymentFlowState.SelectingTerminal)
    }

    @Test
    fun `P1 Codex r3 #5 Esc mientras cotiza cierra la apertura - la respuesta ya no enciende la maquina y la venta se suelta`() = runTest {
        val vm = vm()
        val respuesta = kotlinx.coroutines.CompletableDeferred<RespuestaDelServidor>()
        coEvery { api.cotizar("venue-1", any()) } coAnswers { respuesta.await() }
        val carrito = CartState(nacioEnEstaCaja = true, items = listOf(cafe))
        vm.startPaymentFlow(carrito); runCurrent()
        vm.cobrarConMaquina(); runCurrent()
        assertTrue("apartada mientras cotiza", vm.maquina!!.maquina.apartada(carrito.llaveParaLaMaquina))

        vm.cancel(); runCurrent()   // Esc: el cliente se fue y nadie abre otra venta

        assertFalse("salir suelta el apartado en el mismo paso", vm.maquina!!.maquina.apartada(carrito.llaveParaLaMaquina))
        respuesta.complete(
            RespuestaDelServidor(200, JSONObject().put("quoteId", "q-1").put("saleCents", 18_000).put("tipCents", 0)
                .put("totalCents", 18_000).put("cobrableEnMaquina", true).toString()),
        )
        runCurrent()
        assertNull("nadie ve ya esta venta: su paycash no sale", canal.paycash())
        assertTrue(dao.todas().isEmpty())
    }

    @Test
    fun `P1 Codex r3 #5 salir entre la escritura en disco y el envio tampoco enciende la maquina`() = runTest {
        val vm = vm()
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe))); runCurrent()
        dao.alInsertar = { vm.cancel() }   // la PC guarda el intento y, antes del paycash, la cajera sale

        vm.cobrarConMaquina(); runCurrent()

        assertNull(canal.paycash())
        assertTrue("no quedó fila: nada salió", dao.todas().isEmpty())
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
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe), pendingStampReward = PremioPorAplicar("premio-1", "Café gratis", "FREE_PRODUCT", null)))
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
        coEvery { printerService.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
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

        // Codex código r3 #11: «Imprimir recibo» de la pantalla de éxito pasa por el reclamo del folio: el original ya salió, así que
        // sale una COPIA marcada, nunca un segundo original.
        val copias = mutableListOf<ReceiptData>()
        coEvery { printerService.manualPrintReceipt(capture(copias)) } returns PrinterService.PrintOutcome.Printed(1)
        vm.reprintReceipt()
        runCurrent()
        assertEquals("un solo original", 1, impresos.size)
        val copia = copias.single()
        assertTrue("la reimpresión sale marcada como copia", copia.reprintedAt != null)
        assertEquals("con la misma foto", impresos[0], copia.copy(reprintedAt = null))
    }

    @Test
    fun `P1 Codex r3 #11 si el original no salio, Imprimir recibo saca ESE original una vez, y luego copias`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.OutOfPaper andThen PrinterService.PrintOutcome.Printed(1)
        val copias = mutableListOf<ReceiptData>()
        coEvery { printerService.manualPrintReceipt(capture(copias)) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        val ref = pagar(vm)
        assertEquals(TicketDelCobro.PENDIENTE, dao.fila(ref)!!.ticketImpreso)

        vm.reprintReceipt(); runCurrent()
        assertEquals("el original, por el reclamo de la fila", 2, impresos.size)
        assertEquals(TicketDelCobro.IMPRESO, dao.fila(ref)!!.ticketImpreso)
        assertEquals("Ajustes ya no lo ofrece", 0, vm.maquina!!.vista.value!!.ticketsSinImprimir)
        assertEquals(0, vm.maquina!!.maquina.imprimirTicketsPendientes())

        vm.reprintReceipt(); runCurrent()
        assertEquals("después, sólo copias", 2, impresos.size)
        assertTrue(copias.single().reprintedAt != null)
    }

    @Test
    fun `P1 Codex r3 #12 si el servidor ya dejo el cobro como incidencia, el primer papel es el comprobante del dinero, no un ticket de venta`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        val ref = mandarALaMaquina(vm)
        canal.llega(status(ref, 18_000)); runCurrent()
        // La subida guardó el 202 definitivo (incidencia) en cuanto se cerró, ANTES de que saliera el primer papel.
        dao.alCerrar = { r ->
            kotlinx.coroutines.runBlocking {
                val f = dao.fila(r)!!
                dao.marcarSubido(
                    r, "Registrado como incidencia: la venta ya no se pudo armar", """{"ack":"TERMINADO","incident":"NO_SE_PUDO_REGISTRAR"}""",
                    f.mensajesCrudos, f.creditCents,
                )
            }
        }
        canal.llega(status(ref, 18_000, credit = 20_000, change = 2_000, cobrando = false, estado = "Pago Completado")); runCurrent()

        val papel = impresos.single()
        assertEquals(KIND_COMPROBANTE_DE_LA_MAQUINA, papel.kind)
        assertTrue("sin renglones de venta", papel.items.isEmpty())
    }

    @Test
    fun `C7 con cambio que la maquina no dio el ticket dice cuanto le debemos`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)

        pagar(vm(), credit = 20_000, change = 1_500)   // tenía que dar $20.00: dio $15.00

        assertEquals(500L, impresos.single().cashMachine!!.owedCents)
    }

    @Test
    fun `C7 sin papel ni impresora el ticket no queda como impreso`() = runTest {
        coEvery { printerService.imprimirOriginal(any()) } returns PrinterService.PrintOutcome.NoPrinter

        val ref = pagar(vm())

        assertEquals(TicketDelCobro.PENDIENTE, dao.fila(ref)!!.ticketImpreso)
    }

    @Test
    fun `P1 C7 un cierre repetido no reimprime ni vuelve a cobrar la venta`() = runTest {
        coEvery { printerService.imprimirOriginal(any()) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        val ref = pagar(vm)
        val exito = vm.state.value

        canal.llega(status(ref, 18_000, credit = 20_000, change = 2_000, cobrando = false, estado = "Pago Completado"))
        runCurrent()

        coVerify(exactly = 1) { printerService.imprimirOriginal(any()) }
        assertSame(exito, vm.state.value)
    }

    @Test
    fun `R5#9 el ticket hecho sin red dice precio por confirmar`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
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
        coVerify(exactly = 0) { printerService.imprimirOriginal(any()) }
        coVerify(exactly = 0) { printerService.autoPrintReceipt(any()) }
    }

    @Test
    fun `R3#11 un CANCELADO que no devolvio todo imprime su comprobante con el folio, una vez`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
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
    fun `P1 Codex r1 #11 un PAGADO del panel que quedo como incidencia no cobra la venta ni imprime su ticket`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        val ref = mandarALaMaquina(vm)
        advanceTimeBy(3_001)
        runCurrent()

        elPanelResuelve(
            ref,
            ecoDelGerente(ref, change = 0) {
                put("incident", "NO_SE_PUDO_REGISTRAR").put("incidentId", "cinc000000000000000000006").put("motivo", "VENTA_RECHAZADA")
                    .put("owedCents", 2_000)
            },
        )
        runCurrent()

        assertTrue("no hay venta", vm.state.value is PaymentFlowState.SelectingPaymentMethod)
        assertEquals("Registrado como incidencia: la venta ya no se pudo armar", vm.maquina!!.vista.value!!.incidencia)
        assertNull("se ve el resultado, no la duda", vm.maquina!!.vista.value!!.cobro)
        val papel = impresos.single()
        assertEquals("sólo el comprobante del dinero, con su folio", KIND_COMPROBANTE_DE_LA_MAQUINA, papel.kind)
        assertEquals(ref, papel.orderNumber)
        // Codex código POS r7 (decisión del coordinador): la máquina nunca confirmó el cambio (sólo lo tecleó el gerente): sin cifra de
        // deuda en papel, «Revisar en el panel», donde está la incidencia con sus $20.
        assertNull(papel.cashMachine!!.owedCents)
        assertTrue(papel.cashMachine!!.revisarEnElPanel)
    }

    @Test
    fun `P1 Codex r1 #14 el ticket sale con los renglones que cotizo el servidor, no con los del carrito`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
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
    fun `P1 recorte R3 la venta de la maquina no manda comanda a cocina`() = runTest {
        coEvery { printerService.imprimirOriginal(any()) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()

        pagar(vm)
        runCurrent()

        assertTrue(vm.state.value is PaymentFlowState.Success)
        ningunaComanda()
    }

    @Test
    fun `P1 Codex r1 #12 un ticket que no salio se ofrece en Cobrar con su boton`() = runTest {
        coEvery { printerService.imprimirOriginal(any()) } returns PrinterService.PrintOutcome.NoPrinter andThen PrinterService.PrintOutcome.Printed(1)
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
    fun `P1 Codex r4 #11 el ticket armado de la fila sale con el mismo IVA y quien atendio que el de la caja`() = runTest {
        val impresos = mutableListOf<ReceiptData>()
        coEvery { printerService.imprimirOriginal(capture(impresos)) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        val cart = CartState(nacioEnEstaCaja = true, items = listOf(cafe), orderTaxPercent = 16, selectedStaffId = "staff-ana", selectedStaffName = "Ana")
        val ref = mandarALaMaquina(vm, cart)
        canal.llega(status(ref, 18_000)); runCurrent()
        canal.llega(status(ref, 18_000, credit = 20_000, change = 2_000, cobrando = false, estado = "Pago Completado")); runCurrent()

        val caja = impresos.single()
        val deLaFila = ticketDeLaFila(dao.fila(ref)!!, "La Galeterie")

        assertEquals("Ana", caja.cashierName)
        assertTrue("la caja imprime el IVA aparte", caja.taxAmount > 0)
        assertEquals(
            listOf<Any?>(caja.taxAmount, caja.cashierName, caja.subtotal, caja.discountAmount, caja.total, caja.tipAmount),
            listOf<Any?>(deLaFila.taxAmount, deLaFila.cashierName, deLaFila.subtotal, deLaFila.discountAmount, deLaFila.total, deLaFila.tipAmount),
        )
    }

    @Test
    fun `P1 Codex r4 #12 un PAGADO que llega mientras Cobrar todavia lee su fila cobra la venta y saca su ticket una vez`() = runTest {
        coEvery { printerService.imprimirOriginal(any()) } returns PrinterService.PrintOutcome.Printed(1)
        val vm = vm()
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe))); runCurrent()
        var lecturaLenta = false
        // La máquina acepta y cierra en el mismo instante en que sale el `paycash`…
        canal.alEnviar = { json ->
            val o = JSONObject(json)
            if (o.getString("comando") == "paycash") {
                val ref = o.getString("referenceCC")
                canal.llega(status(ref, 18_000))
                canal.llega(status(ref, 18_000, credit = 20_000, change = 2_000, cobrando = false, estado = "Pago Completado"))
                lecturaLenta = true
            }
        }
        // …y la primera lectura de disco que sigue tarda: el cierre se atiende mientras tanto.
        dao.alLeer = { if (lecturaLenta) { lecturaLenta = false; repeat(100) { kotlinx.coroutines.yield() } } }

        vm.cobrarConMaquina(); runCurrent()

        val exito = vm.state.value as PaymentFlowState.Success
        assertEquals(18_000 to 2_000, exito.totalAmount to exito.changeAmount)
        coVerify(exactly = 1) { printerService.imprimirOriginal(any()) }
    }

    @Test
    fun `una venta dividida no se cobra en la maquina`() = runTest {
        val vm = vm()
        vm.setSplitConfig("EQUALPARTS", numberOfParts = 2)
        vm.startPaymentFlow(CartState(nacioEnEstaCaja = true, items = listOf(cafe)))
        runCurrent()

        assertEquals(TEXTO_SOLO_VENTA_COMPLETA, vm.maquina!!.vista.value!!.fila.motivo)
        vm.cobrarConMaquina()
        runCurrent()
        assertNull(canal.paycash())
    }
}
