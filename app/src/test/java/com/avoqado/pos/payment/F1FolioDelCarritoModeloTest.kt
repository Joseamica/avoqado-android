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
import kotlinx.coroutines.test.runCurrent
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
        /** El `expectedRemainingCents` de cada envío (el saldo que vio la caja). */
        val saldosEsperados = mutableListOf<Int?>()
        /** El `amountCents` (base, sin propina) de cada envío. */
        val montos = mutableListOf<Int>()
        /** El aparato pide propina (la cajera captura [propinaCents] en la pantalla del cliente). */
        var conPropina = false
        /** Qué contesta el próximo envío a la terminal (null = la tarjeta queda en duda). */
        var proximoEnvio: ((folio: String?, requestId: String) -> TerminalPaymentResult)? = null

        /** Folios con un intento que SÍ pasó según la lista durable («SÍ pasó» marcado, cabo del bloque 2). */
        val cobrados = mutableSetOf<String>()
        /** requestId → folio de cada tarjeta mandada (para que «Entendido» vuelva a anunciar su folio, como `quitar`). */
        val intentos = mutableMapOf<String, String>()
        /**
         * Bloque 3: consultas de terminales que se quedan colgadas hasta que la prueba las suelta, por tipo (`true` = la
         * disponibilidad de fondo, `false` = la lista del selector). Sin colgada, contesta [listaDeTerminales].
         */
        val listasColgadas = mutableMapOf<Boolean, ArrayDeque<kotlinx.coroutines.CompletableDeferred<TerminalListResult>>>()
        var listaDeTerminales: List<OnlineTerminal> = listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1"))
        /** Bloque 3: lo que contesta la consulta de un intento (null = «no se sabe»). */
        val desenlaces = mutableMapOf<String, TerminalPaymentResult>()
        /** Codex final r2 #5: la consulta de ese intento se queda colgada hasta que la prueba la suelta. */
        val consultasColgadas = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
        /** Lo que de verdad llegó al lector (la base de cada envío admitido por [admision]). */
        val admitidos = mutableListOf<Int>()

        /**
         * El SERVIDOR (contrato §1): con `expectedRemainingCents`, al centavo (saldo esperado == real y monto ≤ real) o 412; sin él, el
         * margen de $1 de las apps viejas cuando la cuenta ya tiene abono. Admitido = la tarjeta queda en duda en el lector.
         */
        fun admision(requestId: String, saldoReal: Int, conAbono: Boolean): TerminalPaymentResult {
            val esperado = saldosEsperados.last()
            val monto = montos.last()
            val rechazo = if (esperado != null) esperado != saldoReal || monto > saldoReal else conAbono && monto > saldoReal + 100
            return if (rechazo) TerminalPaymentResult.Error("El cobro no cuadra", requestId = requestId, noSeCreo = true, saldoVigenteCents = saldoReal)
            else TerminalPaymentResult.Undetermined("x", requestId).also { admitidos += monto }
        }

        /** Bloque 3: la creación de la orden y la lectura del saldo se quedan colgadas hasta que la prueba las suelta. */
        var creacionColgada: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var saldoColgado: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        /** Lo que contesta `GET …/orders/:id` por folio (estado, pago y saldo). Ausente = sin respuesta. */
        val saldos = mutableMapOf<String, OrderRepository.SaldoDeLaOrden>()

        val orderRepository = mockk<OrderRepository>(relaxed = true).also { repo ->
            coEvery { repo.createOrder(any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers {
                creacionColgada?.await()
                val folio = "order-${'a' + ordenesCreadas.size}"
                ordenesCreadas += folio to thirdArg<String?>()
                Result.success(CreateOrderResponse(success = true, data = OrderData(id = folio, total = totalAlCrear, priceChanges = cambiosAlCrear)))
            }
            coEvery { repo.saldoDeLaOrden(any()) } coAnswers { saldoColgado?.await(); saldos[firstArg()] }
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
            coEvery { t.fetchOnlineTerminals(any()) } coAnswers {
                listasColgadas[firstArg<Boolean>()]?.removeFirstOrNull()?.await() ?: TerminalListResult.Success(listaDeTerminales)
            }
            every { t.cobradoDelFolio(any()) } answers { firstArg<String>() in cobrados }
            every { t.reconocerCobro(any()) } answers {
                val requestId = firstArg<String>()
                intentos[requestId]?.let { foliosDeTarjeta.tryEmit(com.avoqado.pos.payment.data.DesenlaceDeFolio("venue-1", it, requestId)) }
            }
            every { t.pendienteDeLaVenta(any()) } answers { firstArg<String?>()?.let { pendientesLocales[it] } }
            every { t.sigueEnDuda(any()) } answers { pendientesLocales.containsKey(firstArg()) }
            every { t.dudaDeLaVenta(any()) } answers { firstArg<String?>()?.let { pendientesLocales[it] } }
            every { t.saldoEsperadoDelProximoEnvio = any() } answers { saldosEsperados += firstArg<Int?>() }
            every { t.folios } returns foliosDeTarjeta
            every { t.pendientesDeOtrasVentas(any()) } returns emptyList()
            every { t.intentoEnVuelo() } returns null
            every { t.contextoDe(any()) } returns null
            coEvery { t.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                val folio = arg<String?>(4)
                envios += folio
                montos += arg<Int>(1)
                folio?.let { pendientesLocales[it] }?.let { return@answers TerminalPaymentResult.Undetermined("x", it, inherited = true) }
                val requestId = "req-${envios.size}"
                folio?.let { intentos[requestId] = it }
                proximoEnvio?.invoke(folio, requestId)
                    ?: TerminalPaymentResult.Undetermined("x", requestId).also { folio?.let { pendientesLocales[it] = requestId } }
            }
            coEvery { t.resolveOutcome(any(), any(), any()) } coAnswers {
                consultasColgadas[firstArg()]?.await()
                desenlaces[firstArg()] ?: TerminalPaymentResult.Undetermined("x", firstArg())
            }
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
                antesDeLaTarjeta = carrito::guardarFolioAntesDeLaTarjeta,
                saldoDelFolio = carrito.saldoDelFolioConocido(),
            )
            propinaCents?.let(cobro::submitTip)
            return true
        }

        fun tarjeta() {
            cobro.selectPaymentMethod(PaymentMethod.CARD)
            cobro.selectTerminalAndPay("t1")
        }

        /** El 412 de saldo distinto: lo que hace `PaymentFlowScreen` (`onSaldoVigente` y cerrar el cobro). */
        fun atenderSaldoVigente() {
            val saldo = cobro.saldoVigente.value ?: return
            cobro.consumirSaldoVigente()
            carrito.tomarSaldoVigente(saldo)
            carrito.cerrarCobro()
            carrito.releaseChargingOrder()
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

        /** La X del cobro (o Esc): `cancelarVenta` y, si el ViewModel decide salir, lo que hace `CheckoutScreen.onCancel`. */
        fun salir() {
            cobro.cancelarVenta()
            if (!cobro.debeSalir.value) return
            cobro.consumirSalida()
            carrito.cerrarCobro()
            carrito.releaseChargingOrder()
        }

        /** Lo que B tiene a la vista en su selector: lo que una respuesta de A no puede cambiar. */
        fun selectorDeB() = listOf(cobro.state.value, cobro.onlineTerminals.value, cobro.terminalAvailability.value,
            cobro.splitType.value, carrito.cartState.value, carrito.selectedCustomer.value?.id)

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

        /** «Cuentas abiertas» (`CobroDeCuentaScreen`): el cobro de lo que la pantalla enseña de [cuenta], con el saldo que le pasa al cobro. */
        fun cobrarCuentaAbierta(cuenta: com.avoqado.pos.tables.data.OrderDetail) {
            val cents = com.avoqado.pos.tables.data.restanteCents(cuenta)!!
            cobro.setSplitConfig("FULLPAYMENT")
            cobro.startPaymentFlow(
                com.avoqado.pos.pos.presentation.cart.CartState(items = listOf(com.avoqado.pos.pos.data.model.CartItem(
                    type = com.avoqado.pos.pos.data.model.CartItemType.CustomAmount, name = "Saldo pendiente", unitPrice = cents))),
                resumeOrderId = cuenta.id, apertura = UUID.randomUUID().toString(),
                saldoDelFolio = com.avoqado.pos.payment.presentation.saldoDelFolioDeLaCuenta(cuenta),
            )
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

    /**
     * Codex final r1 #5: sobre A, esta caja recibió `409 ORDER` por la tarjeta de $40 de la caja 1 y guardó A. Al retomar se consulta
     * el intento BLOQUEADOR guardado: mientras siga en duda, la cuenta sigue frenada; ya resuelto (la caja 1 abonó), toma el saldo del
     * SERVIDOR ($60) y suelta la duda. Antes se quedaba con la duda «por ser de otra caja» y los $180 en el carrito.
     */
    @Test
    fun segundaCaja_tras409_primeraAbona_alRetomarTomaElSaldoYSueltaLaDuda() = runTest {
        val servidor = Servidor()
        servidor.proximoEnvio = { _, requestId ->
            TerminalPaymentResult.Error("Esta venta ya tiene un cobro pendiente", requestId = requestId, noSeCreo = true,
                bloqueador = BloqueadorDeCobro("req-otra-caja", "ORDER"))
        }
        val caja = Caja(servidor)
        caja.armar("cli-ana", "Ana")
        assertTrue(caja.cobrar())
        caja.tarjeta()
        advanceUntilIdle()
        caja.cobro.revisarCobroQueOcupa()
        advanceUntilIdle()
        caja.seguir()
        assertTrue(caja.guardar())
        val identidadDeA = caja.guardados.savedCarts.value.single().folio!!.identidadDeVenta
        servidor.proximoEnvio = null

        // La caja 1 ya registró un abono en A, pero su tarjeta sigue en duda: el freno se conserva.
        servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(6_000, "PARTIAL", "PENDING")
        assertEquals(Restauracion.Hecha, caja.retomar())
        advanceUntilIdle()
        assertEquals("en duda: la cuenta no cambia", 18_000, caja.carrito.cartState.value.totalCents)
        caja.carrito.addProduct(refresco)
        assertEquals("ni se edita", com.avoqado.pos.pos.presentation.cart.CUENTA_LIGADA_GUARDALA, caja.carrito.avisoDeVales.value)
        assertTrue(caja.guardar())

        // La caja 1 termina su abono de $40: el intento bloqueador ya pasó.
        servidor.desenlaces["req-otra-caja"] = TerminalPaymentResult.Success(paymentId = "pay-caja-1", requestId = "req-otra-caja")
        assertEquals(Restauracion.Hecha, caja.retomar())
        advanceUntilIdle()
        assertEquals("toma el saldo del SERVIDOR", 6_000, caja.carrito.cartState.value.totalCents)
        assertEquals(identidadDeA, caja.carrito.cartState.value.identidadDeVenta)
        assertEquals("y su copia también", listOf("Saldo pendiente" to 6_000),
            caja.guardados.savedCarts.value.single().items.map { it.name to it.unitPrice })
        assertTrue("sin la duda, se cobra", caja.cobrar())
        assertEquals("sobre A", "order-a", caja.carrito.chargingAgainstOrderId)
        caja.tarjeta()
        advanceUntilIdle()
        assertEquals("con el saldo que se enseña", 6_000, servidor.saldosEsperados.last())
        assertEquals(listOf("order-a"), servidor.ordenesCreadas.map { it.first })
    }

    /** El `409 ORDER` del intento [intento] de otra caja sobre la cuenta que se manda. */
    private fun Servidor.ocupadaPor(intento: String) {
        proximoEnvio = { _, requestId ->
            TerminalPaymentResult.Error("Esta venta ya tiene un cobro pendiente", requestId = requestId, noSeCreo = true,
                bloqueador = BloqueadorDeCobro(intento, "ORDER"))
        }
    }

    /** A se manda, otra caja ya tenía [intento] sobre ella: «Revisar ese cobro» → [Seguir]. A queda en pantalla, en duda ajena. */
    private fun TestScope.aEnDudaDeOtraCaja(caja: Caja, intento: String) {
        caja.servidor.ocupadaPor(intento)
        caja.armar("cli-ana", "Ana")
        assertTrue(caja.cobrar())
        caja.tarjeta()
        advanceUntilIdle()
        caja.cobro.revisarCobroQueOcupa()
        advanceUntilIdle()
        caja.seguir()
        assertEquals("montaje: A en pantalla con sus $180", 18_000, caja.carrito.cartState.value.totalCents)
    }

    /**
     * Codex final r2 #1: tras el `409 ORDER` la cajera da [Seguir] y deja A en pantalla (no la guarda ni la retoma). La caja 1 termina su
     * abono de $0.50. «Cobrar» desde esa misma pantalla manda el saldo que enseña ($180): el SERVIDOR contesta 412 y la cuenta relee A
     * (el intento ajeno y su saldo). Antes salía sin `expectedRemainingCents` y el margen de $1 admitía $180 cuando faltaban $179.50.
     */
    @Test
    fun segundaCaja_seguirSinGuardar_primeraAbona_cobrarMandaElSaldoQueEnsena() = runTest {
        val servidor = Servidor()
        val caja = Caja(servidor)
        aEnDudaDeOtraCaja(caja, "req-otra-caja")

        // La caja 1 termina su abono de $0.50: su intento ya pasó y A debe $179.50.
        servidor.desenlaces["req-otra-caja"] = TerminalPaymentResult.Success(paymentId = "pay-caja-1", requestId = "req-otra-caja")
        servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(17_950, "PARTIAL", "PENDING")
        servidor.proximoEnvio = { _, requestId -> servidor.admision(requestId, saldoReal = 17_950, conAbono = true) }
        assertTrue(caja.cobrar())
        assertEquals("order-a", caja.carrito.chargingAgainstOrderId)
        caja.tarjeta()
        advanceUntilIdle()

        assertEquals("viaja el saldo que enseña la pantalla", 18_000, servidor.saldosEsperados.last())
        assertTrue("nada llegó al lector", servidor.admitidos.isEmpty())
        caja.atenderSaldoVigente()
        advanceUntilIdle()
        assertEquals("la cuenta relee A: queda lo que de verdad falta", 17_950, caja.carrito.cartState.value.totalCents)
        assertEquals("y sigue siendo A", listOf("order-a"), servidor.ordenesCreadas.map { it.first })
        assertEquals("su copia sigue (el cierre del cobro no la suelta)", "order-a" to 17_950,
            caja.guardados.savedCarts.value.single().let { it.folio?.orderId to it.folio?.saldoCents })

        assertTrue(caja.cobrar())
        assertEquals("order-a", caja.carrito.chargingAgainstOrderId)
        caja.tarjeta()
        advanceUntilIdle()
        assertEquals(17_950, servidor.saldosEsperados.last())
        assertEquals("el lector sólo recibe lo que falta", listOf(17_950), servidor.admitidos)
    }

    /**
     * Codex final r2 #5: una relectura del intento X de A sale y se queda esperando. Mientras, la caja recibe `409 ORDER` por un intento
     * NUEVO Y de otra caja sobre A y lo deja en duda. Cuando por fin vuelve la respuesta de X (con su abono), no puede borrar Y: ni de la
     * pantalla ni de la copia guardada. Antes se aplicaba a la duda vigente sin mirar que fuera la misma.
     */
    @Test
    fun respuestaTardiaDeX_noBorraLaDudaNuevaY() = runTest {
        val servidor = Servidor()
        val caja = Caja(servidor)
        aEnDudaDeOtraCaja(caja, "req-x")

        // X ya pasó (abono de $120) y una relectura de A sale; su consulta de X se queda colgada.
        servidor.desenlaces["req-x"] = TerminalPaymentResult.Success(paymentId = "pay-x", requestId = "req-x")
        servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(6_000, "PARTIAL", "PENDING")
        val consultaDeX = kotlinx.coroutines.CompletableDeferred<Unit>()
        servidor.consultasColgadas["req-x"] = consultaDeX
        servidor.foliosDeTarjeta.emit(com.avoqado.pos.payment.data.DesenlaceDeFolio("venue-1", "order-a", "req-x"))
        runCurrent()

        // Mientras tanto otra caja manda Y sobre A: la cajera lo revisa y da [Seguir].
        servidor.ocupadaPor("req-y")
        assertTrue(caja.cobrar())
        caja.tarjeta()
        advanceUntilIdle()
        caja.cobro.revisarCobroQueOcupa()
        advanceUntilIdle()
        caja.seguir()
        assertEquals("montaje: la copia ya es de Y", "req-y", caja.guardados.savedCarts.value.single().folio?.requestId)

        // Vuelve la respuesta de X.
        consultaDeX.complete(Unit)
        advanceUntilIdle()

        assertEquals("Y sigue en pantalla: la cuenta no cambia", 18_000, caja.carrito.cartState.value.totalCents)
        caja.carrito.addProduct(refresco)
        assertEquals("ni se edita", com.avoqado.pos.pos.presentation.cart.CUENTA_LIGADA_GUARDALA, caja.carrito.avisoDeVales.value)
        val copia = caja.guardados.savedCarts.value.single().folio
        assertEquals("la copia sigue en duda por Y", "req-y" to true, copia?.requestId to copia?.enDuda)

        // Y se resuelve: su propia relectura sí decide.
        servidor.desenlaces["req-y"] = TerminalPaymentResult.Error("Rechazada", requestId = "req-y")
        assertTrue(caja.guardar())
        assertEquals(Restauracion.Hecha, caja.retomar())
        advanceUntilIdle()
        assertEquals("con Y resuelto, A toma su saldo", 6_000, caja.carrito.cartState.value.totalCents)
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

    // MARK: - Paso 3: la copia durable ANTES de la tarjeta

    @Test
    fun muereTrasSeguirSinGuardar_alAbrirAEstaConSuFolio() = runTest {
        val servidor = Servidor()
        val caja = Caja(servidor)
        tarjetaDeAEnDuda(caja)
        val identidadDeA = caja.carrito.cartState.value.identidadDeVenta
        caja.seguir()
        // La app muere aquí, sin «Guardar».

        val despues = reiniciar(servidor)
        assertEquals("A está en «Carritos guardados» con su folio", "order-a", despues.guardados.savedCarts.value.single().folio?.orderId)
        assertEquals(Restauracion.Hecha, despues.retomar())
        assertEquals("cli-ana", despues.carrito.selectedCustomer.value?.id)
        assertEquals(identidadDeA, despues.carrito.cartState.value.identidadDeVenta)
        assertTrue(despues.cobrar())
        assertEquals("order-a", despues.carrito.chargingAgainstOrderId)
        assertEquals(listOf("order-a"), servidor.ordenesCreadas.map { it.first })
    }

    @Test
    fun bloqueadorDeOtraCaja_muereSinSeguir_alAbrirAEstaConSuFolio() = runTest {
        val servidor = Servidor()
        servidor.proximoEnvio = { _, requestId ->
            TerminalPaymentResult.Error("Esta venta ya tiene un cobro pendiente", requestId = requestId, noSeCreo = true,
                bloqueador = BloqueadorDeCobro("req-otra-caja", "ORDER"))
        }
        val caja = Caja(servidor)
        caja.armar("cli-ana", "Ana")
        assertTrue(caja.cobrar())
        caja.tarjeta()
        advanceUntilIdle()
        // La app muere con «Terminal ocupada» en pantalla.

        val despues = reiniciar(servidor)
        val folio = despues.guardados.savedCarts.value.single().folio
        assertEquals("order-a", folio?.orderId)
        assertEquals("req-otra-caja", folio?.requestId)
        assertEquals("OTRA_CAJA", folio?.origen)
        assertTrue("sin pendiente local fabricado", servidor.pendientesLocales.isEmpty())
        assertEquals(Restauracion.Hecha, despues.retomar())
        assertTrue(despues.cobrar())
        assertEquals("order-a", despues.carrito.chargingAgainstOrderId)
    }

    @Test
    fun sinDuda_cancelarElCobro_noDejaCopia() = runTest {
        val servidor = Servidor()
        servidor.proximoEnvio = { _, requestId -> TerminalPaymentResult.Error("Tarjeta rechazada", requestId = requestId) }
        val caja = Caja(servidor)
        caja.armar("cli-ana", "Ana")
        assertTrue(caja.cobrar())
        caja.tarjeta()
        advanceUntilIdle()
        caja.carrito.cerrarCobro()
        caja.carrito.releaseChargingOrder()

        assertTrue("una tarjeta rechazada no deja una cuenta fantasma", caja.guardados.savedCarts.value.isEmpty())
    }

    // MARK: - expectedRemainingCents y el 412

    @Test
    fun otraCajaAbona_entreLecturaYEnvio_noCobraDeMas() = runTest {
        val servidor = Servidor()
        val caja = Caja(servidor)
        // A con un abono confirmado: su copia guardada dice que faltan $60 (lo leyó del SERVIDOR).
        tarjetaDeAEnDuda(caja)
        caja.seguir()
        assertTrue(caja.guardar())
        servidor.pendientesLocales.remove("order-a")
        servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(6_000, "PARTIAL", "PENDING")
        servidor.foliosDeTarjeta.emit(com.avoqado.pos.payment.data.DesenlaceDeFolio("venue-1", "order-a", "req-1"))
        advanceUntilIdle()
        assertEquals(Restauracion.Hecha, caja.retomarFolio("order-a"))
        assertEquals("montaje: la caja ve $60", 6_000, caja.carrito.cartState.value.totalCents)

        // Otra caja abona $30 entre la lectura y el envío. El SERVIDOR sólo admite el saldo real, al centavo.
        servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(3_000, "PARTIAL", "PENDING")
        servidor.proximoEnvio = { _, requestId ->
            val esperado = servidor.saldosEsperados.last()
            if (esperado == 3_000) TerminalPaymentResult.Undetermined("x", requestId)
            else TerminalPaymentResult.Error("El cobro no cuadra", requestId = requestId, noSeCreo = true, saldoVigenteCents = 3_000)
        }
        val enviosAntes = servidor.envios.size
        assertTrue(caja.cobrar())
        caja.tarjeta()
        advanceUntilIdle()
        caja.atenderSaldoVigente()

        assertEquals("viajó lo que vio la caja", 6_000, servidor.saldosEsperados.last())
        assertEquals("un solo envío: no se reenvía solo", enviosAntes + 1, servidor.envios.size)
        assertEquals("la caja queda con lo que de verdad falta", 3_000, caja.carrito.cartState.value.totalCents)
        assertEquals(com.avoqado.pos.payment.data.avisoDeSaldoCambiado(3_000), caja.carrito.avisoDeVales.value)
        assertEquals("y sigue siendo A", listOf("order-a"), servidor.ordenesCreadas.map { it.first })

        // El nuevo reparto sale del saldo vigente.
        assertTrue(caja.cobrar())
        assertEquals("order-a", caja.carrito.chargingAgainstOrderId)
        caja.tarjeta()
        advanceUntilIdle()
        assertEquals(3_000, servidor.saldosEsperados.last())
    }

    /**
     * Codex final r1 #4 (regresión): tras el 412 la pantalla ya decía $30, pero la limpieza de la copia de antes del envío la
     * guardaba con `folio = null`. La app muere ahí: «Retomar» tiene que abrir A con el saldo vigente, nunca una venta nueva.
     */
    @Test
    fun saldoCambio_412_reinicio_retomarAbreAConSuFolio() = runTest {
        val servidor = Servidor()
        val caja = Caja(servidor)
        tarjetaDeAEnDuda(caja)
        caja.seguir()
        assertTrue(caja.guardar())
        servidor.pendientesLocales.remove("order-a")
        servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(6_000, "PARTIAL", "PENDING")
        servidor.foliosDeTarjeta.emit(com.avoqado.pos.payment.data.DesenlaceDeFolio("venue-1", "order-a", "req-1"))
        advanceUntilIdle()
        assertEquals(Restauracion.Hecha, caja.retomarFolio("order-a"))
        val identidadDeA = caja.carrito.cartState.value.identidadDeVenta
        assertEquals("montaje: la caja ve $60", 6_000, caja.carrito.cartState.value.totalCents)

        // Otra caja abona $30 antes del envío: 412 con lo que de verdad falta.
        servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(3_000, "PARTIAL", "PENDING")
        servidor.proximoEnvio = { _, requestId ->
            if (servidor.saldosEsperados.last() == 3_000) TerminalPaymentResult.Undetermined("x", requestId)
            else TerminalPaymentResult.Error("El cobro no cuadra", requestId = requestId, noSeCreo = true, saldoVigenteCents = 3_000)
        }
        assertTrue(caja.cobrar())
        caja.tarjeta()
        advanceUntilIdle()
        caja.atenderSaldoVigente()
        assertEquals("montaje: la pantalla ya dice $30", 3_000, caja.carrito.cartState.value.totalCents)

        // La app muere aquí.
        val despues = reiniciar(servidor)
        val copia = despues.guardados.savedCarts.value.single()
        assertEquals("la copia sigue ligada a A", "order-a", copia.folio?.orderId)
        assertEquals("con su identidad", identidadDeA, copia.folio?.identidadDeVenta)
        assertEquals(Restauracion.Hecha, despues.retomar())
        assertEquals("Retomar abre A con el saldo vigente", 3_000, despues.carrito.cartState.value.totalCents)
        assertEquals(identidadDeA, despues.carrito.cartState.value.identidadDeVenta)
        assertTrue(despues.cobrar())
        assertEquals("nunca una venta nueva", "order-a", despues.carrito.chargingAgainstOrderId)
        despues.tarjeta()
        advanceUntilIdle()
        assertEquals("y manda el saldo vigente", 3_000, servidor.saldosEsperados.last())
        assertEquals(listOf("order-a"), servidor.ordenesCreadas.map { it.first })
    }

    /**
     * Codex final r1 #1: «Cuentas abiertas» enseña $100 y otra caja abona $0.50 antes del envío. Sin `expectedRemainingCents` el
     * SERVIDOR aplica el margen de $1 de las apps viejas y el lector cobra $100 de una cuenta que debe $99.50. Con el saldo que se
     * enseña, 412: nada llega al lector y el siguiente cobro sale del saldo vigente (como el carrito).
     */
    @Test
    fun cuentasAbiertas_otraCajaAbona50centavos_412_noCobraDeMas() = runTest {
        val servidor = Servidor()
        var saldoReal = 10_000
        // El SERVIDOR (contrato §1): con el saldo esperado, al centavo; sin él, el margen de $1 de las apps viejas (ya hay abono).
        servidor.proximoEnvio = { _, requestId ->
            val esperado = servidor.saldosEsperados.last()
            val monto = servidor.montos.last()
            val rechazo = if (esperado != null) esperado != saldoReal || monto > saldoReal else monto > saldoReal + 100
            if (rechazo) TerminalPaymentResult.Error("El cobro no cuadra", requestId = requestId, noSeCreo = true, saldoVigenteCents = saldoReal)
            else TerminalPaymentResult.Undetermined("x", requestId)
        }
        val caja = Caja(servidor)
        val cuenta = com.avoqado.pos.tables.data.OrderDetail(id = "order-a", status = "PENDING", paymentStatus = "PENDING", total = 100.0,
            preBillBalance = com.avoqado.pos.tables.data.PreBillBalance(10_000, 0, 10_000))
        caja.cobrarCuentaAbierta(cuenta)
        saldoReal = 9_950 // otra caja abona $0.50 entre la lectura y el envío
        caja.tarjeta()
        advanceUntilIdle()

        assertEquals("viaja el saldo que se enseña", 10_000, servidor.saldosEsperados.last())
        assertTrue("nada llegó al lector", caja.cobro.state.value is PaymentFlowState.Error)
        assertEquals("el saldo vigente, como en el carrito", 9_950, caja.cobro.saldoVigente.value?.cents)

        // La cuenta se relee y el nuevo cobro sale de lo que de verdad falta.
        caja.cobro.consumirSaldoVigente()
        caja.cobrarCuentaAbierta(cuenta.copy(paymentStatus = "PARTIAL", preBillBalance = com.avoqado.pos.tables.data.PreBillBalance(10_000, 50, 9_950)))
        caja.tarjeta()
        advanceUntilIdle()
        assertEquals(9_950, servidor.saldosEsperados.last())
        assertEquals(9_950, servidor.montos.last())
        assertTrue(caja.cobro.state.value is PaymentFlowState.Undetermined)
    }

    // MARK: - Cabos del bloque 2: «Saldo por confirmar» y «Entendido»

    @Test
    fun constaDinero_sinSaldo_diceSaldoPorConfirmar_yNoCobraConElTotalViejo() = runTest {
        val servidor = Servidor()
        val caja = Caja(servidor)
        tarjetaDeAEnDuda(caja)
        caja.seguir()
        val totalDeA = caja.carrito.cartState.value.totalCents

        // Llega la aprobación de A, pero sin red no se sabe cuánto falta (GET de la orden sin respuesta).
        servidor.pendientesLocales.remove("order-a")
        servidor.cobrados += "order-a"
        servidor.foliosDeTarjeta.emit(com.avoqado.pos.payment.data.DesenlaceDeFolio("venue-1", "order-a", "req-1"))
        advanceUntilIdle()
        assertEquals(com.avoqado.pos.pos.presentation.cart.SALDO_POR_CONFIRMAR, caja.carrito.avisoDeVales.value)
        assertEquals("nada se inventa: el carrito sigue igual", totalDeA, caja.carrito.cartState.value.totalCents)

        val enviosAntes = servidor.envios.size
        assertTrue("con el total viejo no se abre el cobro", !caja.cobrar())
        assertEquals(enviosAntes, servidor.envios.size)

        // Vuelve la red: el siguiente intento relee y la cuenta queda con su resto real.
        servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(6_000, "PARTIAL", "PENDING")
        caja.cobrar()
        advanceUntilIdle()
        assertEquals("ahora sí: lo que falta según el servidor", 6_000, caja.carrito.cartState.value.totalCents)
        assertTrue(caja.cobrar())
        assertEquals("order-a", caja.carrito.chargingAgainstOrderId)
    }

    @Test
    fun entendido_soloQuitaElAviso_conBAbierto() = runTest {
        val servidor = Servidor()
        val caja = Caja(servidor)
        tarjetaDeAEnDuda(caja)
        caja.seguir()
        assertTrue(caja.guardar())
        caja.armar("cli-beto", "Beto")
        val b = CartStateDeB(caja.carrito.cartState.value, caja.carrito.selectedCustomer.value?.id)
        servidor.pendientesLocales.remove("order-a")
        servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(6_000, "PARTIAL", "PENDING")
        servidor.foliosDeTarjeta.emit(com.avoqado.pos.payment.data.DesenlaceDeFolio("venue-1", "order-a", "req-1"))
        advanceUntilIdle()
        val copiaDeA = caja.guardados.savedCarts.value.single()

        // «Entendido» (en el aviso o en «Cobro anterior resuelto») acaba en `reconocerCobro`, que vuelve a anunciar el folio.
        servidor.terminal.reconocerCobro("req-1")
        servidor.terminal.reconocerCobro("req-1")
        advanceUntilIdle()

        assertBIntacta(caja, b)
        assertEquals("la copia de A no cambia: el resto se aplicó una vez", copiaDeA, caja.guardados.savedCarts.value.single())
    }

    // MARK: - Bloque 3: respuestas de A durante B

    private enum class Llamada(val deFondo: Boolean) { LISTA(false), DISPONIBILIDAD(true) }

    /** A abre su selector con [llamada] colgada; sale; B divide y abre el suyo. Devuelve la respuesta colgada de A. */
    private fun TestScope.bEnSuSelectorConRespuestaDeAEnVuelo(caja: Caja, llamada: Llamada): kotlinx.coroutines.CompletableDeferred<TerminalListResult> {
        val tarde = kotlinx.coroutines.CompletableDeferred<TerminalListResult>()
        caja.servidor.listasColgadas[llamada.deFondo] = ArrayDeque(listOf(tarde))
        caja.armar("cli-ana", "Ana")
        assertTrue(caja.cobrar())
        caja.cobro.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        caja.salir()
        caja.carrito.vaciar()
        caja.armar("cli-beto", "Beto")
        assertTrue(caja.cobrar(parteCents = 9_000))
        caja.cobro.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertEquals("montaje: B en su selector con su parte", 9_000, (caja.cobro.state.value as? PaymentFlowState.SelectingTerminal)?.totalAmount)
        assertEquals("montaje: B ya puede elegir su lector", listOf("t1"), caja.cobro.onlineTerminals.value.map { it.terminalId })
        return tarde
    }

    @Test
    fun respuestaTardiaDeA_conservaB() = runTest {
        val resultados = mapOf(
            "con datos" to TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t-a", name = "La de A"))),
            "vacía" to TerminalListResult.Success(emptyList()),
            "error" to TerminalListResult.Error("Sin conexión", sinRed = true),
        )
        for (llamada in Llamada.values()) for ((nombre, resultado) in resultados) {
            disco = PrefsDeDosCapas()
            val caja = Caja(Servidor())
            val tarde = bEnSuSelectorConRespuestaDeAEnVuelo(caja, llamada)
            val b = caja.selectorDeB()

            tarde.complete(resultado)
            advanceUntilIdle()

            assertEquals("$llamada $nombre: B conserva su selector, su parte y su lector", b, caja.selectorDeB())
        }

        // Creación de la orden de A: la cajera sale mientras se crea; la orden nace tarde con B en su selector.
        run {
            disco = PrefsDeDosCapas()
            val servidor = Servidor()
            val caja = Caja(servidor)
            val creacion = kotlinx.coroutines.CompletableDeferred<Unit>().also { servidor.creacionColgada = it }
            caja.armar("cli-ana", "Ana")
            assertTrue(caja.cobrar())
            caja.tarjeta()
            runCurrent()
            caja.salir()
            servidor.creacionColgada = null
            val b = bAbreSuSelector(caja)

            creacion.complete(Unit)
            advanceUntilIdle()
            assertEquals("creación tardía de A: B intacta", b, caja.selectorDeB())
            assertTrue("y A no viaja a la terminal", servidor.envios.isEmpty())
        }

        // Lectura del saldo tras la tarjeta de A (abono parcial aprobado): la cajera sale y empieza B antes de que conteste.
        run {
            disco = PrefsDeDosCapas()
            val servidor = Servidor()
            servidor.proximoEnvio = { _, requestId -> TerminalPaymentResult.Success(paymentId = "pay-a", requestId = requestId) }
            val caja = Caja(servidor)
            val saldo = kotlinx.coroutines.CompletableDeferred<Unit>().also { servidor.saldoColgado = it }
            servidor.saldos["order-a"] = OrderRepository.SaldoDeLaOrden(9_000, "PARTIAL", "PENDING")
            caja.armar("cli-ana", "Ana")
            assertTrue(caja.cobrar(parteCents = 9_000))
            caja.tarjeta()
            runCurrent()
            caja.salir()
            if (caja.cobro.state.value !is PaymentFlowState.SelectingPaymentMethod) caja.seguir()
            servidor.saldoColgado = null
            val b = bAbreSuSelector(caja, avanzar = false)

            saldo.complete(Unit)
            runCurrent()
            assertEquals("saldo tardío de A: B intacta", b, caja.selectorDeB())
            assertTrue("cero éxito atribuido a B", caja.cobro.state.value !is PaymentFlowState.Success)
        }

        // Cancelación de A: termina en segundo plano con B dividida en su selector.
        run {
            disco = PrefsDeDosCapas()
            val servidor = Servidor()
            val caja = Caja(servidor)
            aGuardadaConBEnPantalla(caja)
            assertTrue(caja.cobrar(parteCents = 9_000))
            caja.cobro.selectPaymentMethod(PaymentMethod.CARD)
            advanceUntilIdle()
            val b = caja.selectorDeB()

            terminaLaCancelacionDeA(servidor, OrderRepository.SaldoDeLaOrden(18_000, "PENDING", "CANCELLED"),
                com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden.Cancelada)
            assertEquals("cancelación de A: B intacta", b, caja.selectorDeB())
        }
    }

    /** Tras salir de A: carrito nuevo de B (mismos artículos, otro cliente), su parte y su selector. Devuelve lo que B ve. */
    private fun TestScope.bAbreSuSelector(caja: Caja, avanzar: Boolean = true): List<Any?> {
        caja.carrito.vaciar()
        caja.armar("cli-beto", "Beto")
        assertTrue("montaje: B abre su cobro", caja.cobrar(parteCents = 7_000))
        caja.cobro.selectPaymentMethod(PaymentMethod.CARD)
        if (avanzar) advanceUntilIdle() else runCurrent()
        assertEquals("montaje: B en su selector", 7_000, (caja.cobro.state.value as? PaymentFlowState.SelectingTerminal)?.totalAmount)
        return caja.selectorDeB()
    }

    @Test
    fun respuestaTardiaDeA_consulta_conservaB() = runTest {
        // La consulta de A («Ver» del aviso) contesta tarde con cada desenlace: sale a su intento, no a la pantalla de B.
        for (desenlace in listOf<TerminalPaymentResult>(
            TerminalPaymentResult.Success(paymentId = "pay-a", requestId = "req-a"),
            TerminalPaymentResult.Error("No se cobró", requestId = "req-a"),
            TerminalPaymentResult.Undetermined("x", "req-a"),
        )) {
            disco = PrefsDeDosCapas()
            val servidor = Servidor()
            val caja = Caja(servidor)
            val tarde = kotlinx.coroutines.CompletableDeferred<TerminalPaymentResult>()
            coEvery { servidor.terminal.resolveOutcome("req-a", any(), any()) } coAnswers { tarde.await() }
            caja.armar("cli-beto", "Beto")
            assertTrue(caja.cobrar(parteCents = 9_000))
            caja.cobro.selectPaymentMethod(PaymentMethod.CARD)
            advanceUntilIdle()
            caja.cobro.revisarCobroDeOtraVenta("req-a")
            advanceUntilIdle()
            caja.seguir() // [Seguir] en la revisión de A: B vuelve a su selector
            advanceUntilIdle()
            val b = caja.selectorDeB()
            assertEquals("montaje", 9_000, (b[0] as? PaymentFlowState.SelectingTerminal)?.totalAmount)

            tarde.complete(desenlace)
            advanceUntilIdle()
            assertEquals("$desenlace: B intacta", b, caja.selectorDeB())
            assertTrue("cero éxito atribuido a B", caja.cobro.state.value !is PaymentFlowState.Success)
        }
    }

    @Test
    fun aprobacionAjena_consultada_conservaDivisionYLectorDeB() = runTest {
        val servidor = Servidor()
        servidor.listaDeTerminales = listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1"), OnlineTerminal(terminalId = "t2", name = "Terminal 2"))
        // Otra caja mandó A a la terminal t1 y la ocupa: el envío de B recibe 409 TERMINAL con el bloqueador.
        servidor.proximoEnvio = { _, requestId ->
            TerminalPaymentResult.Error("Terminal ocupada", requestId = requestId, noSeCreo = true,
                bloqueador = BloqueadorDeCobro("req-otra-caja", "TERMINAL"))
        }
        servidor.desenlaces["req-otra-caja"] = TerminalPaymentResult.Success(paymentId = "pay-a", requestId = "req-otra-caja")
        val caja = Caja(servidor)
        caja.armar("cli-beto", "Beto")
        assertTrue(caja.cobrar(parteCents = 9_000))
        caja.tarjeta()
        advanceUntilIdle()
        assertEquals("montaje: «Revisar ese cobro»", "req-otra-caja", (caja.cobro.state.value as? PaymentFlowState.Error)?.revisarCobroId)

        caja.cobro.revisarCobroQueOcupa()
        advanceUntilIdle()

        assertTrue("B no se cierra", !caja.cobro.debeSalir.value)
        assertEquals("B vuelve a su selector con su parte", 9_000, (caja.cobro.state.value as? PaymentFlowState.SelectingTerminal)?.totalAmount)
        assertNull((caja.cobro.state.value as PaymentFlowState.SelectingTerminal).sinLista)
        assertEquals("con lectores que sí puede elegir", listOf("t1", "t2"), caja.cobro.onlineTerminals.value.map { it.terminalId })
        val aviso = caja.cobro.avisoDeOtroCobro.value
        assertEquals("y ve que el cobro de A sí pasó", "req-otra-caja", aviso?.requestId)
        assertTrue(aviso!!.yaCobrado)

        // «Entendido» sólo quita el aviso.
        val b = caja.selectorDeB()
        caja.cobro.descartarAvisoDeOtroCobro("req-otra-caja")
        assertNull(caja.cobro.avisoDeOtroCobro.value)
        assertEquals(b, caja.selectorDeB())
    }
}
