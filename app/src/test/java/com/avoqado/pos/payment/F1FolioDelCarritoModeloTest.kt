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
        /** Qué contesta el próximo envío a la terminal (null = la tarjeta queda en duda). */
        var proximoEnvio: ((folio: String?, requestId: String) -> TerminalPaymentResult)? = null

        val orderRepository = mockk<OrderRepository>(relaxed = true).also { repo ->
            coEvery { repo.createOrder(any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } answers {
                val folio = "order-${'a' + ordenesCreadas.size}"
                ordenesCreadas += folio to thirdArg<String?>()
                Result.success(CreateOrderResponse(success = true, data = OrderData(id = folio)))
            }
        }

        fun terminalPaymentService(): TerminalPaymentService = mockk<TerminalPaymentService>(relaxed = true).also { t ->
            coEvery { t.fetchOnlineTerminals(any()) } returns
                TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1")))
            every { t.pendienteDeLaVenta(any()) } answers { firstArg<String?>()?.let { pendientesLocales[it] } }
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

    private fun crearCarrito(guardados: SavedCartsRepository): CartViewModel {
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
            orderRepository = mockk<OrderRepository>(relaxed = true),
            staffRepository = staffRepository,
            classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true).also { every { it.consume() } returns null },
            validateReferralUseCase = mockk(relaxed = true),
            captureReferralUseCase = mockk(relaxed = true),
            planManager = PlanManager(secureStorage),
            tableSession = TableSession(),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = mockk(relaxed = true),
            walletScanRepository = mockk(relaxed = true),
        )
    }

    private fun crearCobro(servidor: Servidor): PaymentFlowViewModel {
        val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
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
        val coordinador = CancelacionDeCobroCoordinator(
            store = CancelacionesDeCobroEnTexto(AlmacenQuePuedeFallar()),
            transporte = CancelacionTransportFalso(),
            conectado = MutableStateFlow(true),
            servidorAlcanzable = MutableStateFlow(true),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            reloj = { 0L },
            esperasDeSondeo = listOf(1_000L, 2_000L),
        )
        return PaymentFlowViewModel(
            tableSession = TableSession(),
            syncOutbox = mockk(relaxed = true),
            orderRepository = servidor.orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = servidor.terminalPaymentService(),
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
            cancelacionDeCobro = coordinador,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    /**
     * La caja: los dos ViewModels unidos como en `CheckoutScreen` (`proceedToPayment`, `PaymentFlowScreen` y su salida). Si
     * `CheckoutScreen` cambia el cableado, aquí cambia igual.
     */
    private inner class Caja(val servidor: Servidor) {
        val guardados = SavedCartsRepository(disco.context)
        val carrito = crearCarrito(guardados)
        val cobro = crearCobro(servidor)

        /** «Cobrar»: las puertas de `proceedToPayment` que tocan el folio, y el cobro abierto con la orden congelada. */
        fun cobrar(): Boolean {
            if (carrito.detenerCobroDeVentaCambiada()) return false
            if (!carrito.prepararCobroDelCarritoGuardado()) return false
            carrito.resolvePendingSplitOrderForCharge()
            carrito.abrirCobro()
            val cliente = carrito.selectedCustomer.value
            cobro.startPaymentFlow(
                carrito.getCartForPayment(), customerId = cliente?.id, customerName = cliente?.name,
                resumeOrderId = carrito.chargingAgainstOrderId, apertura = UUID.randomUUID().toString(),
            )
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
}
