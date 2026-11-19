package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.payment.data.BloqueadorDeCobro
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.CancelacionesDeCobroEnTexto
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.cart.PrefsDeDosCapas
import com.avoqado.pos.pos.presentation.cart.Restauracion
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.data.ResultadoLegado
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * 🔴 F1 · cierre android, bloque 2 («Folio y saldo del carrito»), `plan-android.md` · «La prueba de modelo».
 *
 * Los `PaymentFlowViewModel` y `CartViewModel` REALES, unidos por los mismos eventos que usa `CheckoutScreen` ([Caja]). El
 * servidor simulado ([Servidor]) separa cuándo ACEPTA una operación (crea la orden, recibe el cobro) de qué contesta. El
 * reinicio reconstruye los dos ViewModels sobre el MISMO almacenamiento (carritos guardados en disco y la lista durable de
 * tarjetas). La prueba empieza en «Cobrar»: nadie le entrega A al carrito a mano.
 *
 * Venta A y venta B llevan los MISMOS artículos y clientes distintos: dos compras iguales son dos ventas (invariante 1).
 * Los bloques siguientes (3–6) agregan eventos a [Caja] y a [Servidor].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class F1FolioDelCarritoModeloTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pizza = Product(id = "prod-pizza", name = "Pizza", priceValue = 150.0)
    private val refresco = Product(id = "prod-refresco", name = "Refresco", priceValue = 30.0)

    /** Lo que el SERVIDOR (y la lista durable de tarjetas del aparato) recuerda: sobrevive al reinicio de la app. */
    private inner class Servidor {
        val ordenesCreadas = mutableListOf<Pair<String, String?>>() // (folio, cliente)
        val envios = mutableListOf<String?>() // folio de cada tarjeta mandada a la terminal
        /** La lista durable `PendientesDeTarjeta` del aparato: folio → intento en duda. */
        val pendientesLocales = mutableMapOf<String, String>()
        /** El aviso de `TerminalPaymentService.folios`: el intento de un folio dejó de estar en duda en esta tablet. */
        val foliosDeTarjeta = MutableSharedFlow<com.avoqado.pos.payment.data.DesenlaceDeFolio>(extraBufferCapacity = 16)
        /** Lo que el servidor corrige al crear la orden (r2 #1): total en pesos y precios por renglón. */
        var totalAlCrear: Double? = null
        var cambiosAlCrear: List<com.avoqado.pos.pos.data.model.CambioDePrecio> = emptyList()
        /** El aparato pide propina (la cajera captura [propinaCents] en la pantalla del cliente). */
        var conPropina = false
        /** Qué contesta el próximo envío a la terminal (null = la tarjeta queda en duda). */
        var proximoEnvio: ((folio: String?, requestId: String) -> TerminalPaymentResult)? = null

        /** Lo que contesta `GET …/orders/:id` por folio (estado, pago y saldo). Ausente = sin respuesta. */
        val saldos = mutableMapOf<String, OrderRepository.SaldoDeLaOrden>()

        val orderRepository = mockk<OrderRepository>(relaxed = true).also { repo ->
            coEvery { repo.createOrder(any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } answers {
                val folio = "order-${'a' + ordenesCreadas.size}"
                ordenesCreadas += folio to thirdArg<String?>()
                Result.success(CreateOrderResponse(success = true, data = OrderData(id = folio, total = totalAlCrear, priceChanges = cambiosAlCrear)))
            }
            coEvery { repo.saldoDeLaOrden(any()) } answers { saldos[firstArg()] }
        }

        /** La cancelación durable (vive fuera de las pantallas): la terminal y el borrado de la orden los contesta [transporte]. */
        val transporte = CancelacionTransportFalso()
        val coordinador = CancelacionDeCobroCoordinator(
            store = CancelacionesDeCobroEnTexto(AlmacenQuePuedeFallar()),
            transporte = transporte,
            conectado = MutableStateFlow(true),
            servidorAlcanzable = MutableStateFlow(true),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            reloj = { 0L },
            esperasDeSondeo = listOf(1_000L, 2_000L),
        )

        val terminal: TerminalPaymentService = mockk<TerminalPaymentService>(relaxed = true).also { t ->
            coEvery { t.fetchOnlineTerminals(any()) } returns
                TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1")))
            every { t.pendienteDeLaVenta(any()) } answers { firstArg<String?>()?.let { pendientesLocales[it] } }
            every { t.sigueEnDuda(any()) } answers { pendientesLocales.containsKey(firstArg()) }
            every { t.folios } returns foliosDeTarjeta
            every { t.pendientesDeOtrasVentas(any()) } returns emptyList()
            every { t.intentoEnVuelo() } returns null
            every { t.contextoDe(any()) } returns null
            coEvery { t.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                val folio = arg<String?>(4)
                envios += folio
                folio?.let { pendientesLocales[it] }?.let { return@answers TerminalPaymentResult.Undetermined("x", it, inherited = true) }
                val requestId = "req-${envios.size}"
                proximoEnvio?.invoke(folio, requestId)
                    ?: TerminalPaymentResult.Undetermined("x", requestId).also { folio?.let { pendientesLocales[it] = requestId } }
            }
            coEvery { t.resolveOutcome(any(), any(), any()) } answers { TerminalPaymentResult.Undetermined("x", firstArg()) }
        }
    }

    /** Lo que guarda el aparato en disco (carritos guardados). [reiniciar] = la app muere y vuelve a abrir. */
    private var disco = PrefsDeDosCapas()

    private fun crearCarrito(guardados: SavedCartsRepository, servidor: Servidor): CartViewModel {
        val productsRepository = mockk<ProductsRepository>(relaxed = true)
        val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
        val authRepository = mockk<AuthRepository>(relaxed = true)
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        val staffRepository = mockk<StaffRepository>(relaxed = true)
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        every { secureStorage.venueId } returns "venue-1"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
        return CartViewModel(
            productsRepository = productsRepository,
            discountsRepository = discountsRepository,
            savedCartsRepository = guardados,
            authRepository = authRepository,
            secureStorage = secureStorage,
            activeCartState = mockk<ActiveCartState>(relaxed = true),
            orderRepository = servidor.orderRepository,
            staffRepository = staffRepository,
            classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true).also { every { it.consume() } returns null },
            validateReferralUseCase = mockk(relaxed = true),
            captureReferralUseCase = mockk(relaxed = true),
            planManager = PlanManager(secureStorage),
            tableSession = TableSession(),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = mockk(relaxed = true),
            walletScanRepository = mockk(relaxed = true),
            cancelacionDeCobro = servidor.coordinador,
            terminalPaymentService = servidor.terminal,
        )
    }

    private fun crearCobro(servidor: Servidor): PaymentFlowViewModel {
        val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
        every { tpvSettingsRepository.getCurrentSettings() } answers { TpvSettings(showReviewScreen = false, showTipScreen = servidor.conPropina) }
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns "venue-1"
        val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns CashPaymentResult.Success(changeCents = 0)
        val printerService = mockk<PrinterService>(relaxed = true)
        coEvery { printerService.autoPrintReceipt(any()) } returns Unit
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())
        val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
        every { areaTicketRepository.session.current() } returns null
        val kdsRepository = mockk<KDSRepository>(relaxed = true)
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } returns null
        return PaymentFlowViewModel(
            tableSession = TableSession(),
            syncOutbox = mockk(relaxed = true),
            orderRepository = servidor.orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = servidor.terminal,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = mockk<PaymentSyncService>(relaxed = true),
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = kdsRepository,
            kdsOrderBus = mockk<KDSOrderBus>(relaxed = true),
            printerService = printerService,
            secureStorage = secureStorage,
            comandaDispatcher = ComandaDispatcher(
                printConfigRepository,
                ReintentoDeComanda(mockk<ComandaPrinter>(relaxed = true), reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
                printerService,
            ),
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = servidor.coordinador,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    /**
     * La caja: los dos ViewModels unidos como en `CheckoutScreen` (`proceedToPayment`, `PaymentFlowScreen` y su salida). Si
     * `CheckoutScreen` cambia el cableado, aquí cambia igual.
     */
    private inner class Caja(val servidor: Servidor) {
        val guardados = SavedCartsRepository(disco.context)
        val carrito = crearCarrito(guardados, servidor)
        val cobro = crearCobro(servidor)

        /** «Cobrar»: las puertas de `proceedToPayment` que tocan el folio, y el cobro abierto con la orden congelada. */
        fun cobrar(parteCents: Int? = null, propinaCents: Int? = null): Boolean {
            if (carrito.detenerCobroDeVentaCambiada()) return false
            if (!carrito.prepararCobroDelCarritoGuardado()) return false
            carrito.resolvePendingSplitOrderForCharge()
            carrito.abrirCobro()
            val cliente = carrito.selectedCustomer.value
            if (parteCents != null) cobro.setSplitConfig("CUSTOMAMOUNT", customAmountCents = parteCents) else cobro.setSplitConfig("FULLPAYMENT")
            cobro.startPaymentFlow(
                carrito.getCartForPayment(), customerId = cliente?.id, customerName = cliente?.name,
                resumeOrderId = carrito.chargingAgainstOrderId, apertura = UUID.randomUUID().toString(),
            )
            propinaCents?.let(cobro::submitTip)
            return true
        }

        fun tarjeta() {
            cobro.selectPaymentMethod(PaymentMethod.CARD)
            cobro.selectTerminalAndPay("t1")
        }

        /** [Seguir] en la pantalla de la duda, y lo que `PaymentFlowScreen` hace al salir (`debeSalir`). */
        fun seguir() {
            cobro.seguirDesdeLaDuda()
            if (!cobro.debeSalir.value) return
            cobro.consumirSalida()
            cobro.consumirFolioEnDuda()?.let(carrito::ligarTarjetaEnDuda)
            carrito.cerrarCobro()
            carrito.releaseChargingOrder()
        }

        fun guardar(): Boolean = carrito.saveCurrentCart("Cuenta")

        fun retomar(): Restauracion {
            val copia = guardados.savedCarts.value.single()
            return carrito.restoreSavedCart(copia).let {
                if (it is Restauracion.PideConfirmacion) carrito.restoreSavedCart(copia, confirmado = true) else it
            }
        }

        /** «Retomar» la copia del folio [orderId] (con otras guardadas en la lista). */
        fun retomarFolio(orderId: String): Restauracion {
            val copia = guardados.savedCarts.value.first { it.folio?.orderId == orderId }
            return carrito.restoreSavedCart(copia).let {
                if (it is Restauracion.PideConfirmacion) carrito.restoreSavedCart(copia, confirmado = true) else it
            }
        }

        fun armar(clienteId: String, nombre: String) {
            carrito.setSelectedCustomer(clienteId, nombre)
            carrito.addProduct(pizza)
            carrito.addProduct(refresco)
        }
    }

    /** La app muere y vuelve a abrir: lo de memoria se pierde; lo que está en disco y en el servidor, no. */
    private fun reiniciar(servidor: Servidor): Caja {
        disco = disco.reiniciarProceso()
        return Caja(servidor)
    }

    private fun TestScope.tarjetaDeAEnDuda(caja: Caja) {
        caja.armar("cli-ana", "Ana")
        assertTrue(caja.cobrar())
        caja.tarjeta()
        advanceUntilIdle()
        assertTrue("montaje: la tarjeta de A queda en duda", caja.cobro.state.value is PaymentFlowState.Undetermined)
        assertEquals("montaje: A existe una vez", listOf("order-a"), caja.servidor.ordenesCreadas.map { it.first })
    }

    // MARK: - Fila 2 de la prueba de modelo: A ligada o guardada

    @Test
    fun seguir_guardar_reiniciar_retomar_conservaA() = runTest {
        val servidor = Servidor()
        val caja = Caja(servidor)
        tarjetaDeAEnDuda(caja)
        val identidadDeA = caja.carrito.cartState.value.identidadDeVenta

        caja.seguir()
        assertEquals("tras [Seguir] la cajera sigue frente a A", "Ana", caja.carrito.selectedCustomer.value?.name)
        assertEquals(2, caja.carrito.cartState.value.items.size)

        assertTrue("Guardar deja libre la caja para B", caja.guardar())
        assertTrue(caja.carrito.cartState.value.isEmpty)
        assertNull(caja.carrito.selectedCustomer.value)
        assertNotEquals("B nace con identidad propia", identidadDeA, caja.carrito.cartState.value.identidadDeVenta)

        val despues = reiniciar(servidor)
        assertEquals(Restauracion.Hecha, despues.retomar())
        assertEquals("Retomar devuelve el cliente de A", "cli-ana", despues.carrito.selectedCustomer.value?.id)
        assertEquals("y su identidad de venta", identidadDeA, despues.carrito.cartState.value.identidadDeVenta)

        assertTrue(despues.cobrar())
        assertEquals("el cobro vuelve contra el folio de A", "order-a", despues.carrito.chargingAgainstOrderId)
        despues.tarjeta()
        advanceUntilIdle()
        assertEquals("un solo folio para esta compra", listOf("order-a"), servidor.ordenesCreadas.map { it.first })
        assertTrue("mientras dure la duda, la terminal no recibe otra tarjeta",
            despues.cobro.state.value is PaymentFlowState.Undetermined)
    }

    @Test
    fun ordenRemota_seGuardaSinPendienteLocal() = runTest {
        val servidor = Servidor()
        // Otra caja alcanzó a mandar primero una tarjeta sobre A: el servidor contesta 409 con alcance ORDER.
        servidor.proximoEnvio = { _, requestId ->
            TerminalPaymentResult.Error("Esta venta ya tiene un cobro pendiente", requestId = requestId, noSeCreo = true,
                bloqueador = BloqueadorDeCobro("req-otra-caja", "ORDER"))
        }
        val caja = Caja(servidor)
        caja.armar("cli-ana", "Ana")
        assertTrue(caja.cobrar())
        caja.tarjeta()
        advanceUntilIdle()
        assertEquals("montaje: «Revisar ese cobro»", "req-otra-caja", (caja.cobro.state.value as? PaymentFlowState.Error)?.revisarCobroId)

        caja.cobro.revisarCobroQueOcupa()
        advanceUntilIdle()
        caja.seguir()

        assertTrue("A se puede guardar aunque esta tablet nunca mandó ese cobro", caja.guardar())
        assertTrue("ni se fabricó un pendiente local", servidor.pendientesLocales.isEmpty())
        val despues = reiniciar(servidor)
        assertEquals(Restauracion.Hecha, despues.retomar())
        assertTrue(despues.cobrar())
        assertEquals("Retomar vuelve contra A, no abre otro folio", "order-a", despues.carrito.chargingAgainstOrderId)
        assertEquals(listOf("order-a"), servidor.ordenesCreadas.map { it.first })
    }

    // MARK: - Fila 1: A recién creada con la tarjeta en duda

    @Test
    fun mismosArticulos_otroCliente_requiereGuardar() = runTest {
        val servidor = Servidor()
        val caja = Caja(servidor)
        tarjetaDeAEnDuda(caja)
        caja.seguir()
        val carritoDeA = caja.carrito.cartState.value

        // Llega B con lo mismo: cambiar el cliente sobre A cargaría a A el cobro de B (Codex android r2 #4).
        caja.carrito.setSelectedCustomer("cli-beto", "Beto")
        assertEquals("el cliente de A no cambia", "cli-ana", caja.carrito.selectedCustomer.value?.id)
        assertEquals(com.avoqado.pos.pos.presentation.cart.CUENTA_LIGADA_GUARDALA, caja.carrito.avisoDeVales.value)
        caja.carrito.addProduct(refresco)
        caja.carrito.vaciar()
        assertEquals("ni sus artículos", carritoDeA.items, caja.carrito.cartState.value.items)

        assertTrue(caja.guardar())
        caja.armar("cli-beto", "Beto")
        assertTrue(caja.cobrar())
        assertNull("B, con los mismos artículos, es otra venta", caja.carrito.chargingAgainstOrderId)
        caja.tarjeta()
        advanceUntilIdle()
        assertEquals(listOf("order-a" to "cli-ana", "order-b" to "cli-beto"), servidor.ordenesCreadas)
    }

    // MARK: - Fila 3: A se resuelve con B abierto

    /** A en duda → la cajera pide cancelar → [Seguir] → Guardar → llega B (mismos artículos, otro cliente). */
    private fun TestScope.aGuardadaConBEnPantalla(caja: Caja): CartStateDeB {
        tarjetaDeAEnDuda(caja)
        caja.cobro.cancelarVenta()
        advanceUntilIdle()
        caja.seguir()
        assertTrue("montaje: A se guarda con su folio", caja.guardar())
        assertEquals("order-a", caja.guardados.savedCarts.value.single().folio?.orderId)
        caja.armar("cli-beto", "Beto")
        return CartStateDeB(caja.carrito.cartState.value, caja.carrito.selectedCustomer.value?.id)
    }

    private data class CartStateDeB(val carrito: com.avoqado.pos.pos.presentation.cart.CartState, val cliente: String?)

    /** En segundo plano la terminal confirma que no hubo cargo y el coordinador cierra la cancelación de A. */
    private fun TestScope.terminaLaCancelacionDeA(servidor: Servidor, estadoDeA: OrderRepository.SaldoDeLaOrden, borrado: com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden) {
        servidor.pendientesLocales.remove("order-a")
        servidor.saldos["order-a"] = estadoDeA
        servidor.transporte.programarEstados(com.avoqado.pos.payment.domain.ChargeStatusProbe.Known("CANCELLED", inProgress = false, cancelDisposition = "ACCEPTED"))
        servidor.transporte.programarBorrados(borrado)
        servidor.coordinador.procesarAhora(servidor.coordinador.pendientes.value.single().id)
        advanceUntilIdle()
        assertTrue("montaje: la cancelación de A terminó", servidor.coordinador.pendientes.value.isEmpty())
    }

    private fun assertBIntacta(caja: Caja, b: CartStateDeB) {
        assertEquals("B no se toca", b.carrito, caja.carrito.cartState.value)
        assertEquals(b.cliente, caja.carrito.selectedCustomer.value?.id)
    }

    @Test
    fun cancelacionEnFondo_sueltaSoloASinDinero() = runTest {
        val servidor = Servidor()
        val caja = Caja(servidor)
        val b = aGuardadaConBEnPantalla(caja)
        val identidadDeA = caja.guardados.savedCarts.value.single().folio!!.identidadDeVenta

        terminaLaCancelacionDeA(servidor, OrderRepository.SaldoDeLaOrden(18_000, "PENDING", "CANCELLED"),
            com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden.Cancelada)

        assertBIntacta(caja, b)
        val copia = caja.guardados.savedCarts.value.single()
        assertNull("A se canceló sin dinero: su copia ya no lleva folio", copia.folio)
        assertEquals("los artículos se conservan como borrador", 2, copia.items.size)

        // Repetir (reinicio, que relee): nada cambia. Retomar da un borrador editable con identidad nueva y sin folio.
        val despues = reiniciar(servidor)
        assertEquals(copia, despues.guardados.savedCarts.value.single())
        assertEquals(Restauracion.Hecha, despues.retomar())
        assertNotEquals(identidadDeA, despues.carrito.cartState.value.identidadDeVenta)
        despues.carrito.setSelectedCustomer("cli-ana", "Ana")
        despues.carrito.addProduct(refresco)
        assertEquals("ya se edita", 2, despues.carrito.cartState.value.items.size)
        assertEquals(3, despues.carrito.cartState.value.items.sumOf { it.quantity })
        assertTrue(despues.cobrar())
        assertNull("y se cobra en un folio nuevo", despues.carrito.chargingAgainstOrderId)
    }

    @Test
    fun aSeResuelveConBAbierto_losCuatroDesenlaces() = runTest {
        val conDinero = com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden.RechazoDeNegocio(400, null, "La cuenta tiene pagos")
        data class Caso(val nombre: String, val estado: OrderRepository.SaldoDeLaOrden, val borrado: com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden)
        val casos = listOf(
            Caso("cancelada sin dinero", OrderRepository.SaldoDeLaOrden(18_000, "PENDING", "CANCELLED"), com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden.Cancelada),
            Caso("negativo con A abierta", OrderRepository.SaldoDeLaOrden(18_000, "PENDING", "PENDING"), conDinero),
            Caso("con abono", OrderRepository.SaldoDeLaOrden(6_000, "PARTIAL", "PENDING"), conDinero),
            Caso("pagada completa", OrderRepository.SaldoDeLaOrden(0, "PAID", "COMPLETED"), conDinero),
        )
        for (caso in casos) {
            disco = PrefsDeDosCapas()
            val servidor = Servidor()
            val caja = Caja(servidor)
            val b = aGuardadaConBEnPantalla(caja)
            terminaLaCancelacionDeA(servidor, caso.estado, caso.borrado)
            assertBIntacta(caja, b)
            val copias = caja.guardados.savedCarts.value
            when (caso.nombre) {
                "cancelada sin dinero" -> assertNull(caso.nombre, copias.single().folio)
                "negativo con A abierta" -> {
                    assertEquals(caso.nombre, false, copias.single().folio?.enDuda)
                    assertEquals(2, copias.single().items.size)
                }
                "con abono" -> {
                    assertEquals(caso.nombre, listOf("Saldo pendiente" to 6_000), copias.single().items.map { it.name to it.unitPrice })
                    assertEquals(false, copias.single().folio?.enDuda)
                }
                "pagada completa" -> assertTrue("sólo A sale", copias.isEmpty())
            }
            // Repetir el resultado (reinicio que relee): igual.
            val despues = reiniciar(servidor)
            assertEquals(caso.nombre, copias, despues.guardados.savedCarts.value)
            if (caso.nombre == "negativo con A abierta" || caso.nombre == "con abono") {
                assertEquals(Restauracion.Hecha, despues.retomar())
                assertTrue(despues.cobrar())
                assertEquals("${caso.nombre}: se cobra otra vez sobre A", "order-a", despues.carrito.chargingAgainstOrderId)
            }
        }
    }

    // MARK: - r2 #1: precio corregido y abono tardío (ejemplo numérico del plan)

    @Test
    fun precioCorregido_yAbonoTardio_repartenElSaldo() = runTest {
        val servidor = Servidor().apply { conPropina = true }
        val caja = Caja(servidor)
        caja.carrito.setSelectedCustomer("cli-ana", "Ana")
        caja.carrito.addProduct(Product(id = "prod-paquete", name = "Paquete", priceValue = 120.0))
        assertEquals("el carrito estima $120", 12_000, caja.carrito.cartState.value.totalCents)
        val linea = caja.carrito.cartState.value.items.single().id
        servidor.totalAlCrear = 100.0
        servidor.cambiosAlCrear = listOf(com.avoqado.pos.pos.data.model.CambioDePrecio(
            externalId = linea, productId = "prod-paquete", displayedUnitPriceCents = 12_000, unitPriceCents = 10_000,
        ))

        // Se cobran $40 + $5 de propina con tarjeta y quedan en duda.
        assertTrue(caja.cobrar(parteCents = 4_000, propinaCents = 500))
        caja.tarjeta()
        advanceUntilIdle()
        assertEquals("en duda se ven $45", 4_500, (caja.cobro.state.value as? PaymentFlowState.Undetermined)?.totalAmount)

        caja.seguir()
        assertEquals("A queda con el precio del servidor", 10_000, caja.carrito.cartState.value.totalCents)
        assertTrue(caja.guardar())
        assertEquals(10_000, caja.guardados.savedCarts.value.single().items.single().unitPrice)
        caja.armar("cli-beto", "Beto")
        val b = CartStateDeB(caja.carrito.cartState.value, caja.carrito.selectedCustomer.value?.id)

        // Llega la aprobación de los $40 mientras se atiende a B: el servidor ya descuenta la parte (sin la propina).
        servidor.pendientesLocales.remove("order-a")
        servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(6_000, "PARTIAL", "PENDING")
        servidor.foliosDeTarjeta.emit(com.avoqado.pos.payment.data.DesenlaceDeFolio("venue-1", "order-a", "req-1"))
        servidor.foliosDeTarjeta.emit(com.avoqado.pos.payment.data.DesenlaceDeFolio("venue-1", "order-a", "req-1")) // repetido
        advanceUntilIdle()
        assertBIntacta(caja, b)
        assertEquals("la copia de A queda con su resto, una vez", listOf("Saldo pendiente" to 6_000),
            caja.guardados.savedCarts.value.single().items.map { it.name to it.unitPrice })

        // Al retomar A quedan $60 y dividir entre dos ofrece $30.
        assertTrue(caja.guardar())
        assertEquals(Restauracion.Hecha, caja.retomarFolio("order-a"))
        assertEquals("quedan $60", 6_000, caja.carrito.cartState.value.totalCents)
        assertEquals("dividir entre dos ofrece $30", 3_000, caja.carrito.cartState.value.totalCents / 2)
        assertTrue(caja.cobrar())
        assertEquals("el resto se cobra sobre A", "order-a", caja.carrito.chargingAgainstOrderId)
        assertEquals(listOf("order-a"), servidor.ordenesCreadas.map { it.first })
    }
}
