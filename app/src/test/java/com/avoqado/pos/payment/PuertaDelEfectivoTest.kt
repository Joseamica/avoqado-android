package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.onlineorders.data.CobroDeLaBandeja
import com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta
import com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.ContextoDeCobro
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.domain.DineroConocido
import com.avoqado.pos.payment.domain.DineroDeLaOrden
import com.avoqado.pos.payment.domain.OrigenDelCobro
import com.avoqado.pos.payment.domain.PuertaDelEfectivo
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * 🔴 D13 (`hz:ce654971:D13`): la puerta del efectivo. Con el cobro de un folio ya abierto (desde la bandeja o desde «Pedidos → detalle»),
 * el efectivo NO se aparta si después de fijar el saldo entró dinero de esa orden que la pantalla no sabe y con él se pasaría de lo que
 * faltaba. Sin red es igual: todo es del aparato. Antecedente: r1 #3, r6 #1, r7 #1 y r8 de
 * `docs/audit/2026-11-11-folio-efectivo-tras-tarjeta-android/`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PuertaDelEfectivoTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val venue = "venue-1"
    // Cada prueba con su orden: las marcas soltadas viven en el proceso (`FoliosCobradosConTarjeta.liberadasDe`).
    private val orden = "orden-${UUID.randomUUID()}"

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val cajaRepo = mockk<CashDrawerRepository>(relaxed = true)

    /** Lo que el aparato tiene de la orden, como lo leería Room en este instante. */
    private var filas: List<DineroDeLaOrden> = emptyList()
    private var tarjetas: List<ContextoDeCobro> = emptyList()
    private var marcasEnDisco: String? = null
    private var lecturaTruena = false
    private val apartadas = mutableListOf<String>()

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        every { terminalPaymentService.cobrosConTarjetaDelNegocio(any()) } answers { tarjetas }
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery { cashPaymentRepository.cobrosDelPedido(any(), any()) } coAnswers {
            if (lecturaTruena) throw IllegalStateException("disco")
            filas
        }
        // Como Room: el apartado queda en la cola de la orden. `yield` abre la ventana que el candado tiene que cerrar.
        coEvery { cashPaymentRepository.reservarCobro(orderRequest = any(), staffId = any(), cashTenderedCents = any(), changeCents = any(),
            rating = any(), orderId = any(), orderExternalId = any(), customerId = any(), idempotencyKey = any(), manualMethod = any(),
            tenderType = any(), amountCents = any(), tipCents = any(), splitType = any(), venueId = any(), capturadaEn = any(),
            cajaDelCobro = any(), enLugarDe = any(), lineasDelTicket = any()) } coAnswers {
            yield()
            val llave = arg<String>(8)
            apartadas += llave
            filas = filas + DineroDeLaOrden(PuertaDelEfectivo.llaveDeEfectivo(llave), arg<Int>(11).toLong(), esTarjeta = false)
        }
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            com.avoqado.pos.payment.data.TerminalPaymentResult.Error("sin terminal")
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns venue
        every { secureStorage.foliosCobradosConTarjetaJson } answers { marcasEnDisco }
        every { areaTicketRepository.session.current() } returns null
    }

    private fun vm() = PaymentFlowViewModel(
        orderRepository = orderRepository,
        cashPaymentRepository = cashPaymentRepository,
        tenderTypeRepository = mockk(relaxed = true),
        terminalPaymentService = terminalPaymentService,
        tpvSettingsRepository = tpvSettingsRepository,
        paymentSyncService = mockk<PaymentSyncService>(relaxed = true),
        cashDrawerRepository = cajaRepo,
        kdsRepository = mockk(relaxed = true),
        kdsOrderBus = mockk(relaxed = true),
        printerService = mockk<PrinterService>(relaxed = true),
        secureStorage = secureStorage,
        comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
        replayDeComandas = mockk(relaxed = true),
        comandaDispatcher = ComandaDispatcher(
            mockk<PrintConfigRepository>(relaxed = true),
            ReintentoDeComanda(mockk<ComandaPrinter>(relaxed = true), reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
            mockk(relaxed = true),
        ),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        syncOutbox = mockk(relaxed = true),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = areaTicketRepository,
        cancelacionDeCobro = mockk(relaxed = true),
        savedStateHandle = androidx.lifecycle.SavedStateHandle(),
    )

    private fun carrito(saldoCents: Int) = CartState(
        items = listOf(CartItem(id = "pedido-en-linea-$orden", type = CartItemType.CustomAmount, name = "Folio W7K3P", unitPrice = saldoCents)),
    )

    /** El cobro de la bandeja, como lo abre `OnlineOrdersScreen`, con la foto que tomó `abrirCobro` antes de leer el saldo. */
    private fun abrirDesdeLaBandeja(vm: PaymentFlowViewModel, saldoCents: Int = 13_400, foto: DineroConocido = fotoDeAhora(), parte: Int? = null) {
        if (parte != null) vm.setSplitConfig(type = "CUSTOMAMOUNT", customAmountCents = parte)
        vm.startPaymentFlow(carrito(saldoCents), resumeOrderId = orden, origen = OrigenDelCobro.PEDIDO_EN_LINEA,
            apertura = UUID.randomUUID().toString(), cobroDeLaBandeja = CobroDeLaBandeja(saldoCents.toLong(), esFolio = true), dineroConocido = foto)
    }

    private fun tarjeta(id: String = "req-1", monto: Int = 13_400, cobrado: Boolean) =
        ContextoDeCobro(id, venueId = venue, terminalId = "t1", orderId = orden, amountCents = monto, cobrado = cobrado)

    /** Un efectivo de la orden de OTRA pantalla; ya subido, con el id del pago que dio su 2xx. */
    private fun efectivoAjeno(id: String, monto: Long = 13_400, confirmado: Boolean = false, pago: String? = null) =
        DineroDeLaOrden(PuertaDelEfectivo.llaveDeEfectivo(id), monto, esTarjeta = false, confirmado = confirmado, pagoDelServidor = pago)

    /**
     * La foto como la toma la bandeja: el efectivo ya subido antes de leer el saldo, lo soltado hasta abrir y, con red, los pagos que
     * trajo la misma respuesta del saldo ([pagos]; null = el saldo salió de la copia, sin red).
     */
    private fun fotoDeAhora(efectivo: List<DineroDeLaOrden> = emptyList(), pagos: Set<String>? = null) =
        DineroConocido.antesDeLeerElSaldo(efectivo, FoliosCobradosConTarjeta.secuencia()).copy(pagosContados = pagos)

    private fun noSeAparto(vm: PaymentFlowViewModel, texto: String) {
        assertTrue("no se aparta nada", apartadas.isEmpty())
        assertEquals(texto, (vm.state.value as? PaymentFlowState.Error)?.message)
    }

    private fun seAparto() = assertEquals("el efectivo se aparta", 1, apartadas.size)

    // MARK: - Caso 1: la tarjeta en duda se confirma con el cobro ya abierto

    @Test
    fun `D13-1 la tarjeta en duda al abrir se confirma despues y el efectivo ya no se aparta`() = runTest {
        tarjetas = listOf(tarjeta(cobrado = false)) // al abrir sigue en duda: abre (founder «B»)
        val vm = vm()
        abrirDesdeLaBandeja(vm)
        tarjetas = listOf(tarjeta(cobrado = true)) // la terminal la confirma con el cobro abierto (sin red: cancelación durable)

        vm.confirmCashCustom(13_400)
        advanceUntilIdle()

        noSeAparto(vm, PuertaDelEfectivo.TEXTO_TARJETA)
    }

    @Test
    fun `D13-1b el positivo ya salio de la lista y dejo su marca - tampoco`() = runTest {
        val vm = vm()
        abrirDesdeLaBandeja(vm)
        marcasEnDisco = FoliosCobradosConTarjeta.agregar(null,
            FolioCobradoConTarjeta(orden, 13_400, 13_400, System.currentTimeMillis(), venueId = venue, requestId = "req-1"))

        vm.confirmCashCustom(13_400)
        advanceUntilIdle()

        noSeAparto(vm, PuertaDelEfectivo.TEXTO_TARJETA)
    }

    @Test
    fun `D13-1c r3 - la marca nacio y la copia ya la solto despues de la foto - tampoco`() = runTest {
        val vm = vm()
        abrirDesdeLaBandeja(vm) // la foto es de antes: el saldo de esta pantalla no cuenta esa tarjeta (se soltará después)
        val marca = FolioCobradoConTarjeta(orden, 13_400, 13_400, System.currentTimeMillis(), venueId = venue, requestId = "req-1")
        marcasEnDisco = FoliosCobradosConTarjeta.agregar(null, marca)
        FoliosCobradosConTarjeta({ marcasEnDisco }, { nueva -> marcasEnDisco = nueva(marcasEnDisco); true }).olvidar(listOf(marca))
        assertTrue("montaje: ya no hay marca ni tarjeta", FoliosCobradosConTarjeta.leer(marcasEnDisco).isEmpty() && tarjetas.isEmpty())

        vm.confirmCashCustom(13_400)
        advanceUntilIdle()

        noSeAparto(vm, PuertaDelEfectivo.TEXTO_TARJETA)
    }

    // MARK: - Caso 2 y 3: efectivo de la misma orden guardado desde otra pantalla

    @Test
    fun `D13-2 con el cobro de la bandeja abierto se guarda efectivo desde el detalle y el de la bandeja ya no se aparta`() = runTest {
        val vm = vm()
        abrirDesdeLaBandeja(vm)
        filas = listOf(efectivoAjeno("detalle-1")) // «Pedidos → detalle → Cobrar → efectivo», sin red: en cola

        vm.confirmCashCustom(13_400)
        advanceUntilIdle()

        noSeAparto(vm, PuertaDelEfectivo.TEXTO_EFECTIVO)
    }

    @Test
    fun `D13-3 r8 - el efectivo ajeno estaba EN_VUELO y pasa a SYNCED durante la lectura - igual frena (por llave, no por estado)`() = runTest {
        val vm = vm()
        abrirDesdeLaBandeja(vm) // la bandeja tomó su foto antes de su primera revisión: ese efectivo no estaba
        // Ya subió (2xx) mientras se leía la copia (sin red, el saldo de esta pantalla es el de la copia: no trae lista de pagos).
        filas = listOf(efectivoAjeno("detalle-1", confirmado = true, pago = "pago-detalle"))

        vm.confirmCashCustom(13_400)
        advanceUntilIdle()

        noSeAparto(vm, PuertaDelEfectivo.TEXTO_EFECTIVO)
    }

    @Test
    fun `r2-1 la tarjeta se confirma MIENTRAS la puerta lee la cola - igual frena`() = runTest {
        tarjetas = listOf(tarjeta(cobrado = false))
        val vm = vm()
        abrirDesdeLaBandeja(vm)
        coEvery { cashPaymentRepository.cobrosDelPedido(any(), any()) } coAnswers {
            yield()
            tarjetas = listOf(tarjeta(cobrado = true)) // la cancelación durable trae el positivo durante la lectura de Room
            filas
        }

        vm.confirmCashCustom(13_400)
        advanceUntilIdle()

        noSeAparto(vm, PuertaDelEfectivo.TEXTO_TARJETA)
    }

    @Test
    fun `una marca soltada ANTES de la foto ya la contaba el saldo - no frena`() = runTest {
        val marca = FolioCobradoConTarjeta(orden, 10_000, 30_000, System.currentTimeMillis(), venueId = venue, requestId = "req-viejo")
        FoliosCobradosConTarjeta({ FoliosCobradosConTarjeta.agregar(null, marca) }, { true }).olvidar(listOf(marca))
        val vm = vm()
        abrirDesdeLaBandeja(vm, saldoCents = 20_000)

        vm.confirmCashCustom(20_000)
        advanceUntilIdle()

        seAparto()
    }

    // MARK: - Lo que NO debe frenar

    @Test
    fun `r2-2 un abono de 200 subio MIENTRAS el servidor calculaba el saldo de 300 - el efectivo de 300 pasa`() = runTest {
        val vm = vm()
        // La foto no lo tiene (subió después de tomarla), pero la respuesta del saldo ya traía su pago.
        abrirDesdeLaBandeja(vm, saldoCents = 30_000, foto = fotoDeAhora(pagos = setOf("pago-A")))
        filas = listOf(efectivoAjeno("detalle-200", monto = 20_000, confirmado = true, pago = "pago-A"))

        vm.confirmCashCustom(30_000)
        advanceUntilIdle()

        seAparto()
    }

    @Test
    fun `r2-2b el mismo abono subido DESPUES de leer un saldo de 500 - un efectivo de 500 frena y uno de 300 pasa`() = runTest {
        filas = listOf(efectivoAjeno("detalle-200", monto = 20_000, confirmado = true, pago = "pago-A"))
        val completo = vm()
        abrirDesdeLaBandeja(completo, saldoCents = 50_000, foto = fotoDeAhora(pagos = emptySet()))
        completo.confirmCashCustom(50_000)
        advanceUntilIdle()
        noSeAparto(completo, PuertaDelEfectivo.TEXTO_EFECTIVO)

        val resto = vm()
        abrirDesdeLaBandeja(resto, saldoCents = 50_000, parte = 30_000, foto = fotoDeAhora(pagos = emptySet()))
        resto.confirmCashCustom(30_000)
        advanceUntilIdle()
        seAparto()
    }

    @Test
    fun `r3-2 reenvio - A ya estaba en el saldo de 300 y B de 100 llego despues - el efectivo de 200 pasa y uno de 300 no`() = runTest {
        // El reenvío de A devolvió el saldo de AHORA (200), no el de justo después de A: por eso cuenta el id del pago, no el saldo.
        filas = listOf(
            efectivoAjeno("A", monto = 20_000, confirmado = true, pago = "pago-A"),
            efectivoAjeno("B", monto = 10_000, confirmado = true, pago = "pago-B"),
        )
        val resto = vm()
        abrirDesdeLaBandeja(resto, saldoCents = 30_000, parte = 20_000, foto = fotoDeAhora(pagos = setOf("pago-A")))
        resto.confirmCashCustom(20_000)
        advanceUntilIdle()
        seAparto()

        apartadas.clear()
        val todo = vm()
        abrirDesdeLaBandeja(todo, saldoCents = 30_000, foto = fotoDeAhora(pagos = setOf("pago-A")))
        todo.confirmCashCustom(30_000)
        advanceUntilIdle()
        noSeAparto(todo, PuertaDelEfectivo.TEXTO_EFECTIVO)
    }

    @Test
    fun `caso 6 - el reintento del mismo cobro no se frena con su propio apartado`() = runTest {
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(),
            importeAceptadoCents = any(), lineasDelTicket = any()) } returns Result.failure(OrderRepository.ServerException(400, "rechazo"))
        val vm = vm()
        abrirDesdeLaBandeja(vm)

        vm.confirmCashCustom(13_400)
        advanceUntilIdle()
        assertEquals("montaje: el primer intento apartó y su fila sigue en la cola", 1, apartadas.size)
        assertEquals(1, filas.size)

        vm.confirmCashCustom(13_400) // «Reintentar»
        advanceUntilIdle()

        assertEquals("el reintento vuelve a apartar (no se cuenta a sí mismo)", 2, apartadas.size)
        assertFalse((vm.state.value as? PaymentFlowState.Error)?.message in
            setOf(PuertaDelEfectivo.TEXTO_EFECTIVO, PuertaDelEfectivo.TEXTO_TARJETA))
    }

    @Test
    fun `control B - una tarjeta que sigue en duda no frena`() = runTest {
        tarjetas = listOf(tarjeta(cobrado = false))
        val vm = vm()
        abrirDesdeLaBandeja(vm)

        vm.confirmCashCustom(13_400)
        advanceUntilIdle()

        seAparto()
    }

    @Test
    fun `lo que la foto ya contaba no frena (efectivo subido antes de leer el saldo)`() = runTest {
        filas = listOf(efectivoAjeno("antes", monto = 6_600, confirmado = true)) // su 2xx no dijo el saldo: lo respalda la foto
        val vm = vm()
        abrirDesdeLaBandeja(vm, saldoCents = 6_800, foto = fotoDeAhora(filas))

        vm.confirmCashCustom(6_800)
        advanceUntilIdle()

        seAparto()
    }

    @Test
    fun `abono que cabe - un positivo de 200 llega con el cobro abierto y un efectivo de 300 de 500 pasa, uno de 500 no`() = runTest {
        val vm = vm()
        abrirDesdeLaBandeja(vm, saldoCents = 50_000, parte = 30_000)
        tarjetas = listOf(tarjeta(monto = 20_000, cobrado = true))

        vm.confirmCashCustom(30_000)
        advanceUntilIdle()
        seAparto()

        apartadas.clear()
        filas = emptyList()
        val completo = vm()
        abrirDesdeLaBandeja(completo, saldoCents = 50_000)
        completo.confirmCashCustom(50_000)
        advanceUntilIdle()
        noSeAparto(completo, PuertaDelEfectivo.TEXTO_TARJETA)
    }

    @Test
    fun `una marca de SALDO que nace despues de la foto no frena (A1, el servidor ya la cuenta)`() = runTest {
        val vm = vm()
        abrirDesdeLaBandeja(vm, saldoCents = 30_000)
        marcasEnDisco = FoliosCobradosConTarjeta.agregar(null,
            FolioCobradoConTarjeta(orden, 20_000, 50_000, System.currentTimeMillis(), venueId = venue, requestId = "saldo-1", conSaldo = true))

        vm.confirmCashCustom(30_000)
        advanceUntilIdle()

        seAparto()
    }

    @Test
    fun `sin foto (mesa, partes 2 a N) no hay puerta - como hoy`() = runTest {
        val vm = vm()
        vm.startPaymentFlow(carrito(30_000), resumeOrderId = orden, apertura = UUID.randomUUID().toString())
        filas = listOf(efectivoAjeno("abono-1", monto = 20_000)) // el abono anterior de la mesa, en cola

        vm.confirmCashCustom(30_000)
        advanceUntilIdle()

        seAparto()
    }

    // MARK: - Falla cerrado y el candado

    @Test
    fun `si la lectura truena no se aparta`() = runTest {
        val vm = vm()
        abrirDesdeLaBandeja(vm)
        lecturaTruena = true

        vm.confirmCashCustom(13_400)
        advanceUntilIdle()

        noSeAparto(vm, PuertaDelEfectivo.TEXTO_NO_SE_PUDO)
    }

    @Test
    fun `dos pantallas confirman efectivo de la misma orden a la vez - solo una aparta`() = runTest {
        val bandeja = vm()
        val detalle = vm()
        abrirDesdeLaBandeja(bandeja)
        abrirDesdeLaBandeja(detalle)

        bandeja.confirmCashCustom(13_400)
        detalle.confirmCashCustom(13_400)
        advanceUntilIdle()

        assertEquals("una sola aparta", 1, apartadas.size)
        assertEquals("y la otra se frena", 1, listOf(bandeja, detalle).count {
            (it.state.value as? PaymentFlowState.Error)?.message == PuertaDelEfectivo.TEXTO_EFECTIVO })
    }

    @Test
    fun `r6-1 un apartado SIN foto de la misma orden espera a que termine la revision de la puerta`() = runTest {
        val lectura = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { cashPaymentRepository.cobrosDelPedido(any(), any()) } coAnswers { lectura.await(); filas }
        val bandeja = vm()
        abrirDesdeLaBandeja(bandeja) // con foto: revisa
        val mostrador = vm()
        mostrador.startPaymentFlow(carrito(13_400), resumeOrderId = orden, apertura = UUID.randomUUID().toString()) // sin foto

        bandeja.confirmCashCustom(13_400)
        runCurrent() // la bandeja tiene el candado y espera su lectura de la cola
        mostrador.confirmCashCustom(13_400)
        runCurrent()
        assertTrue("el apartado sin foto no se cuela durante la revisión", apartadas.isEmpty())

        lectura.complete(Unit)
        advanceUntilIdle()
        assertEquals("primero el de la puerta y luego el otro (sin puerta, como hoy)", 2, apartadas.size)
    }

    @Test
    fun `r6-1b si el apartado sin foto entra primero, la puerta lo ve`() = runTest {
        val mostrador = vm()
        mostrador.startPaymentFlow(carrito(13_400), resumeOrderId = orden, apertura = UUID.randomUUID().toString())
        val bandeja = vm()
        abrirDesdeLaBandeja(bandeja)

        mostrador.confirmCashCustom(13_400)
        advanceUntilIdle()
        bandeja.confirmCashCustom(13_400)
        advanceUntilIdle()

        assertEquals(1, apartadas.size)
        assertEquals(PuertaDelEfectivo.TEXTO_EFECTIVO, (bandeja.state.value as? PaymentFlowState.Error)?.message)
    }

    @Test
    fun `r8-1 ligar el apartado a la orden recien nacida espera a que termine la revision de la puerta de esa orden`() = runTest {
        // Mostrador: una venta nueva aparta su efectivo SIN orden y espera a que el servidor la cree (S = `orden`).
        val creacion = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers {
            creacion.await()
            Result.success(com.avoqado.pos.payment.data.model.CreateOrderResponse(success = true,
                data = com.avoqado.pos.payment.data.model.OrderData(id = orden, total = 134.0)))
        }
        val mostrador = vm()
        mostrador.startPaymentFlow(CartState(items = listOf(CartItem(id = "e1", type = CartItemType.ProductItem("p1"), name = "Café", unitPrice = 13_400))),
            apertura = UUID.randomUUID().toString())
        mostrador.confirmCashCustom(13_400)
        runCurrent()
        assertEquals("montaje: el mostrador ya apartó (sin orden) y espera la creación", 1, apartadas.size)

        // «Pedidos → detalle» cobra S con foto; su lectura de la cola queda detenida con el candado de S tomado.
        val lectura = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { cashPaymentRepository.cobrosDelPedido(any(), any()) } coAnswers { lectura.await(); emptyList() } // el apartado del mostrador todavía no sabe su orden
        val detalle = vm()
        abrirDesdeLaBandeja(detalle)
        detalle.confirmCashCustom(13_400)
        runCurrent()

        creacion.complete(Unit) // S llega al mostrador: quiere ligar su apartado a S
        runCurrent()
        coVerify(exactly = 0) { cashPaymentRepository.actualizarApartado(any(), any(), any(), any(), any()) }

        lectura.complete(Unit)
        advanceUntilIdle()
        coVerify(exactly = 1) { cashPaymentRepository.actualizarApartado(any(), any(), any(), orden, any()) }
    }

    @Test
    fun `D14d la marca fuera de hace 2 dias sigue en disco (tambien tras reiniciar) y frena`() = runTest {
        val vm = vm()
        abrirDesdeLaBandeja(vm)
        val haceDosDias = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        marcasEnDisco = FoliosCobradosConTarjeta.agregar(null,
            FolioCobradoConTarjeta(orden, 13_400, 13_400, haceDosDias, venueId = venue, requestId = "req-fuera", esFolio = false, fueraDeLaBandeja = true))
        // Se escribe otra marca: la de 2 días no se cae (vive 7 en disco; un reinicio no la borra).
        marcasEnDisco = FoliosCobradosConTarjeta.agregar(marcasEnDisco,
            FolioCobradoConTarjeta("otra-${UUID.randomUUID()}", 100, 100, System.currentTimeMillis(), venueId = venue, requestId = "req-otra"))

        vm.confirmCashCustom(13_400)
        advanceUntilIdle()

        noSeAparto(vm, PuertaDelEfectivo.TEXTO_TARJETA)
    }

    @Test
    fun `la fila ya subida trae el id del pago de su 2xx y la que no ha subido no`() {
        fun fila(estado: String) = com.avoqado.pos.core.data.local.database.PendingPaymentEntity(id = "k1", venueId = venue, staffId = "s",
            amountCents = 500, tipCents = 0, method = "CASH", paymentType = "ORDER", orderId = orden, syncStatus = estado,
            q15ResultJson = """{"paymentId":"pay-1","orderId":"$orden","saldoCents":0}""")
        val subida = com.avoqado.pos.payment.data.dineroDeLaFila(fila("SYNCED"))
        assertEquals(DineroDeLaOrden(PuertaDelEfectivo.llaveDeEfectivo("k1"), 500, esTarjeta = false, confirmado = true, pagoDelServidor = "pay-1"), subida)
        val enVuelo = com.avoqado.pos.payment.data.dineroDeLaFila(fila("EN_VUELO"))
        assertEquals("misma llave en cualquier estado", subida.llave, enVuelo.llave)
        assertEquals(null, enVuelo.pagoDelServidor)
        assertFalse(enVuelo.confirmado)
    }

    // MARK: - Codex r4: otra venta arranca en la misma pantalla mientras el efectivo espera

    /** La caja abierta tarda (la primera espera del efectivo): cada consulta queda detenida hasta soltarla. */
    private fun cajaQueEspera(): MutableList<kotlinx.coroutines.CompletableDeferred<Unit>> {
        val puertas = mutableListOf<kotlinx.coroutines.CompletableDeferred<Unit>>()
        coEvery { cajaRepo.getOpenSession() } coAnswers { kotlinx.coroutines.CompletableDeferred<Unit>().also { puertas += it }.await(); null }
        return puertas
    }

    private fun relevo(vm: PaymentFlowViewModel) = vm.startPaymentFlow(carrito(5_000), resumeOrderId = "otra-${UUID.randomUUID()}",
        origen = OrigenDelCobro.PEDIDO_EN_LINEA, apertura = UUID.randomUUID().toString(), dineroConocido = fotoDeAhora())

    @Test
    fun `r4-1 otra venta arranca en la misma pantalla mientras el efectivo espera - el efectivo sigue con SU foto`() = runTest {
        val puertas = cajaQueEspera()
        tarjetas = listOf(tarjeta(cobrado = false))
        val vm = vm()
        abrirDesdeLaBandeja(vm)
        vm.confirmCashCustom(13_400)
        runCurrent()
        relevo(vm) // la foto de la pantalla ya es de otra orden
        tarjetas = listOf(tarjeta(cobrado = true)) // el positivo de la tarjeta de ESTA orden llega

        puertas.single().complete(Unit)
        advanceUntilIdle()

        assertTrue("el efectivo de la orden ya cobrada con tarjeta no se aparta", apartadas.isEmpty())
    }

    @Test
    fun `r4-2 el freno del efectivo anterior no desarma el doble toque de la venta nueva`() = runTest {
        val puertas = cajaQueEspera()
        val vm = vm()
        abrirDesdeLaBandeja(vm)
        vm.confirmCashCustom(13_400)
        runCurrent()
        relevo(vm)
        vm.confirmCashCustom(5_000) // la venta nueva empieza a cobrar
        runCurrent()
        assertEquals("montaje: los dos intentos esperan la caja", 2, puertas.size)
        tarjetas = listOf(tarjeta(cobrado = true)) // el intento anterior se va a frenar

        puertas[0].complete(Unit)
        runCurrent()
        vm.confirmCashCustom(5_000) // segundo toque de la venta nueva, con su primer intento en vuelo
        runCurrent()

        assertEquals("el segundo toque no entra", 2, puertas.size)
        puertas.forEach { it.complete(Unit) }
        advanceUntilIdle()
    }

    // MARK: - D14 en la pantalla de cobro

    @Test
    fun `D14 la tarjeta de un folio cobrado desde el detalle lleva su contexto para dejar la marca`() = runTest {
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        val vm = vm()
        vm.startPaymentFlow(carrito(13_400), resumeOrderId = orden, apertura = UUID.randomUUID().toString(), dineroConocido = DineroConocido.NADA)
        vm.selectPaymentMethod(com.avoqado.pos.payment.data.model.PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        coVerify { terminalPaymentService.sendPaymentToTerminal(terminalId = "t1", amountCents = 13_400, tipCents = any(), rating = any(),
            orderId = orden, processedByStaffId = any(), customerId = any(),
            deLaBandeja = CobroDeLaBandeja(13_400, esFolio = false, fueraDeLaBandeja = true)) }
    }
}
