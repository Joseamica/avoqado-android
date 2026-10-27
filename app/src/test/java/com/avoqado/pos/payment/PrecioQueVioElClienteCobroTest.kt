package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.data.model.StampRewardOnOrder
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.RefrescoDelCatalogo
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.CambioDePrecio
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.CartViewModel
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
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * 🔴 DINERO — «se cobra el precio que vio el cliente» (17-oct-2026), la caja obedece lo que decidió el servidor y lo dice.
 *
 * - 201 con `priceChanges`: el carrito del cobro corrige SÓLO las líneas nombradas, el descuento de cuenta que viajó queda fijo,
 *   se cobra lo de la orden y se avisa (en efectivo, con el cambio de más).
 * - 409 `ORDER_PRICE_CHANGED` (sólo en línea; la cola nunca lo recibe): no se cobra; se estrena llave, se corrige SIN congelar,
 *   se pide el catálogo y se dice el total nuevo. 🔴 D2 v7 (24-oct): la reserva del efectivo NO se borra al 409 (recibir un «no»
 *   no es una decisión de la cajera); se borra al guardar el apartado del nuevo intento, en la misma escritura.
 * - Lo derivado del total (pago por artículos, partes, segundo pago de la misma orden) cuadra al centavo con la orden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrecioQueVioElClienteCobroTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val catalogo = "2026-10-17T15:00:00.000Z"
    private fun linea(id: String, precio: Int) =
        CartItem(id = id, type = CartItemType.ProductItem("p1"), name = "Café", unitPrice = precio, catalogAt = catalogo)
    private fun cambio(id: String?, de: Int, a: Int) = CambioDePrecio(id, "p1", "Café", catalogo, de, a)
    private val diezPorCiento = Discount(id = "d10", name = "Diez", value = 10.0, type = "PERCENTAGE", scope = "ORDER")
    private val dosCafes = CartState(items = listOf(linea("e1", 5000), linea("e2", 5000)))

    /** `Result` es una clase inline y no puede ir en un `vararg`: se envuelve. */
    private class Respuesta<T>(val r: Result<T>)

    private fun creada(total: Double, vararg cambios: CambioDePrecio, premio: StampRewardOnOrder? = null) = Respuesta(Result.success(
        CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = total, stampReward = premio, priceChanges = cambios.toList())),
    ))
    private fun rechazo409(vararg cambios: CambioDePrecio) = Respuesta(Result.failure<CreateOrderResponse>(
        OrderRepository.ServerException(409, "El precio de algunos productos cambió. Revisa el total antes de cobrar.", cambios.toList()),
    ))
    private fun sinRed() = Respuesta(Result.failure<CreateOrderResponse>(IOException("sin red")))
    private fun pagado(saldo: Int? = null, estado: String? = null) = Respuesta(Result.success(
        OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null, remainingBalanceCents = saldo, orderPaymentStatus = estado),
    ))

    private fun cuandoSeCreaLaOrden(vararg respuestas: Respuesta<CreateOrderResponse>, pedidos: MutableList<CreateOrderRequest> = mutableListOf(), llaves: MutableList<String> = mutableListOf()) {
        coEvery {
            orderRepository.createOrder(capture(pedidos), any(), any(), any(), capture(llaves), any(), any(), capturedVenueId = any())
        } returnsMany respuestas.map { it.r }
    }

    private fun montoEnLaTerminal(): MutableList<Int> {
        val montos = mutableListOf<Int>()
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), capture(montos), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Error("Terminal timeout")
        return montos
    }

    /** Lo que el VM le dijo al repositorio que el cajero ya aceptó en pantalla (null = nada que aceptar). */
    private val importesAceptados = mutableListOf<Int?>()

    private fun cobradoEnEfectivo(vararg respuestas: Respuesta<OrderRepository.CashPayResult>): MutableList<Int> {
        val cobrados = mutableListOf<Int>()
        coEvery {
            orderRepository.recordCashPayment(any(), capture(cobrados), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = captureNullable(importesAceptados))
        } returnsMany respuestas.map { it.r }.ifEmpty { listOf(pagado().r) }
        return cobrados
    }

    // MARK: - 201 con cambios

    @Test
    fun `tarjeta - el servidor cobró 45 cada uno, se cobran 90 y se avisa`() = runTest {
        cuandoSeCreaLaOrden(creada(90.0, cambio("e1", 5000, 4500), cambio("e2", 5000, 4500)))
        val montos = montoEnLaTerminal()

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals(listOf(9000), montos)
        assertEquals("Café bajó de precio: ahora cuesta \$45.00.", vm.avisoDePrecio.value)
    }

    @Test
    fun `efectivo con 100 pesos - el servidor cobró 45 cada uno, registra 90, cambio 10 y lo dice`() = runTest {
        cuandoSeCreaLaOrden(creada(90.0, cambio("e1", 5000, 4500), cambio("e2", 5000, 4500)))
        val cobrados = cobradoEnEfectivo()

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10000)
        advanceUntilIdle()

        assertEquals(listOf(9000), cobrados)
        assertEquals(1000, (vm.state.value as PaymentFlowState.Success).changeAmount)
        assertEquals("Café bajó de precio: ahora cuesta \$45.00.\nDevuelve \$10.00 más de cambio.", vm.avisoDePrecio.value)
        assertEquals("el cajero ya vio el precio nuevo: no es alerta para el gerente", listOf<Int?>(9000), importesAceptados)
    }

    @Test
    fun `efectivo sin cambios de precio - no se acepta ningún importe distinto`() = runTest {
        cuandoSeCreaLaOrden(creada(100.0))
        cobradoEnEfectivo()

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10000)
        advanceUntilIdle()

        assertEquals(listOf<Int?>(null), importesAceptados)
    }

    @Test
    fun `descuento de cuenta 10 por ciento - tras bajar a 80 se cobra 70, igual que la orden`() = runTest {
        val pedidos = mutableListOf<CreateOrderRequest>()
        cuandoSeCreaLaOrden(creada(70.0, cambio("e1", 10000, 8000)), pedidos = pedidos)
        val montos = montoEnLaTerminal()

        vm.startPaymentFlow(CartState(items = listOf(linea("e1", 10000)), orderDiscount = diezPorCiento))
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals("viajó el 10 % de 100", 1000, pedidos.single().discount)
        assertEquals("80 menos los 10 que viajaron, no menos el 10 % de 80", listOf(7000), montos)
        assertEquals(1000, vm.buildCompletion().descuentoDeCuentaCongelado?.cents)
    }

    // MARK: - 409: no se cobra, se estrena llave y se vuelve a elegir; la reserva vieja se va con el intento nuevo (D2 v7)

    /**
     * 🔴 Actualizada a D2 v7 (24-oct). Antes: el 409 ESPERABA el retiro durable de la reserva antes de volver a elegir (y su DAO
     * falso borraba donde la SQL real conserva, `AND 0`). Ahora la reserva del intento rechazado sigue del flujo hasta que la
     * cajera decide, y el apartado del nuevo intento la borra en la MISMA escritura.
     */
    @Test
    fun `409 en efectivo - vuelve a elegir método con el total nuevo, y el apartado del nuevo intento borra el viejo`() = runTest {
        val f = DaoFalso()
        val caja = vm(f.repositorio())
        val pedidos = mutableListOf<CreateOrderRequest>()
        val llaves = mutableListOf<String>()
        cuandoSeCreaLaOrden(rechazo409(cambio("e1", 5000, 6000), cambio("e2", 5000, 6000)), creada(120.0), pedidos = pedidos, llaves = llaves)
        val cobrados = cobradoEnEfectivo()

        caja.startPaymentFlow(dosCafes)
        caja.selectPaymentMethod(PaymentMethod.CASH)
        caja.processCashPayment(10000)
        advanceUntilIdle()

        val primera = llaves.single()
        assertEquals(PaymentFlowState.SelectingPaymentMethod(12000), caja.state.value)
        assertEquals("el 409 no borra: la reserva sigue del flujo", "EN_VUELO", f.filas[primera]?.syncStatus)
        assertTrue("y no aparece «sin confirmar» mientras la venta vive", f.repositorio().cobrosSinConfirmar().isEmpty())
        verify(exactly = 1) { refresco.refrescarAhora() }
        assertEquals("Café cambió de precio: ahora cuesta \$60.00.\nTotal nuevo: \$120.00.", caja.avisoDePrecio.value)
        assertTrue("no se registra ningún cobro", cobrados.isEmpty())

        caja.processCashPayment(12000)
        advanceUntilIdle()

        assertNotEquals("el reintento es otra creación", primera, llaves[1])
        assertNull("al guardar el apartado nuevo se borró el del intento rechazado", f.filas[primera])
        assertEquals("el pedido nuevo lleva el precio corregido", listOf(6000, 6000), pedidos[1].items.map { it.displayedUnitPrice })
        assertEquals(listOf(12000), cobrados)
    }

    /**
     * 🔴 Actualizada a D2 v7 (24-oct). Antes: el retiro de la reserva al 409 fallaba y no se estrenaba llave. Ya no hay retiro al
     * 409; lo que puede fallar es guardar el apartado del nuevo intento, que borra el viejo en la misma escritura: si falla, no
     * sale nada y el viejo se queda; al volver el disco, el viejo se borra y el cobro sale con la llave nueva.
     */
    @Test
    fun `409 en efectivo y el apartado del nuevo intento no se puede guardar - no sale nada y el viejo se queda`() = runTest {
        val f = DaoFalso()
        val caja = vm(f.repositorio())
        val llaves = mutableListOf<String>()
        cuandoSeCreaLaOrden(rechazo409(cambio("e1", 5000, 6000), cambio("e2", 5000, 6000)), creada(120.0), llaves = llaves)
        val cobrados = cobradoEnEfectivo()

        caja.startPaymentFlow(dosCafes)
        caja.selectPaymentMethod(PaymentMethod.CASH)
        caja.processCashPayment(10000)
        advanceUntilIdle()
        val primera = llaves.single()

        f.fallaAlGuardar = true
        caja.processCashPayment(12000)
        advanceUntilIdle()

        assertTrue((caja.state.value as PaymentFlowState.Error).message.contains("No se pudo guardar el cobro"))
        assertEquals("ni creación ni POST", 1, llaves.size)
        assertTrue(cobrados.isEmpty())
        assertEquals("el viejo sigue, del flujo", "EN_VUELO", f.filas[primera]?.syncStatus)
        assertEquals(1, f.filas.size)

        f.fallaAlGuardar = false
        caja.retry()
        advanceUntilIdle()

        assertNull(f.filas[primera])
        assertEquals(2, llaves.size)
        assertNotEquals(primera, llaves[1])
        assertEquals(listOf(12000), cobrados)
    }

    @Test
    fun `409 en tarjeta - igual, sin mandar nada a la terminal`() = runTest {
        val llaves = mutableListOf<String>()
        cuandoSeCreaLaOrden(rechazo409(cambio("e1", 5000, 6000), cambio("e2", 5000, 6000)), creada(120.0), llaves = llaves)
        val montos = montoEnLaTerminal()

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertTrue(montos.isEmpty())
        assertEquals(PaymentFlowState.SelectingPaymentMethod(12000), vm.state.value)
        assertEquals("Café cambió de precio: ahora cuesta \$60.00.\nTotal nuevo: \$120.00.", vm.avisoDePrecio.value)
        verify(exactly = 1) { refresco.refrescarAhora() }

        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertNotEquals(llaves[0], llaves[1])
        assertEquals(listOf(12000), montos)
    }

    @Test
    fun `un 409 no congela - 10 por ciento de 100 que sube a 125, el siguiente pedido manda 1250 y cobra 112 con 50`() = runTest {
        val pedidos = mutableListOf<CreateOrderRequest>()
        cuandoSeCreaLaOrden(rechazo409(cambio("e1", 10000, 12500)), creada(112.5), pedidos = pedidos)
        val montos = montoEnLaTerminal()

        vm.startPaymentFlow(CartState(items = listOf(linea("e1", 10000)), orderDiscount = diezPorCiento))
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()
        assertEquals(PaymentFlowState.SelectingPaymentMethod(11250), vm.state.value)

        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals(listOf(1000, 1250), pedidos.map { it.discount })
        assertEquals(11250, pedidos[1].total)
        assertEquals(listOf(11250), montos)
        assertNull(vm.buildCompletion().descuentoDeCuentaCongelado)
    }

    @Test
    fun `una cortesía con un 409 sigue en cero`() = runTest {
        val cortesia = Discount(id = "cortesia_1", name = "Cortesía", value = 100.0, type = "PERCENTAGE", scope = "ORDER")
        cuandoSeCreaLaOrden(rechazo409(cambio("e1", 10000, 12500)))
        montoEnLaTerminal()

        vm.startPaymentFlow(CartState(items = listOf(linea("e1", 10000)), orderDiscount = cortesia))
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals(PaymentFlowState.SelectingPaymentMethod(0), vm.state.value)
        assertEquals("Café cambió de precio: ahora cuesta \$125.00.\nTotal nuevo: \$0.00.", vm.avisoDePrecio.value)
    }

    // MARK: - Varias correcciones de la misma venta (P4, P5)

    @Test
    fun `si el 409 y el 201 corrigen la misma línea se dice sólo el último contra lo que vio el cajero`() = runTest {
        cuandoSeCreaLaOrden(rechazo409(cambio("e1", 4500, 5000)), creada(48.0, cambio("e1", 5000, 4800)))
        val montos = montoEnLaTerminal()

        vm.startPaymentFlow(CartState(items = listOf(linea("e1", 4500))))
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals(listOf(4800), montos)
        assertEquals("Café cambió de precio: ahora cuesta \$48.00.\nTotal nuevo: \$48.00.", vm.avisoDePrecio.value)
        assertEquals(listOf(cambio("e1", 4500, 4800)), vm.buildCompletion().cambiosDePrecio)
    }

    @Test
    fun `un cambio que se deshizo deja de decirse`() = runTest {
        cuandoSeCreaLaOrden(rechazo409(cambio("e1", 4500, 5000)), creada(45.0, cambio("e1", 5000, 4500)))
        val montos = montoEnLaTerminal()

        vm.startPaymentFlow(CartState(items = listOf(linea("e1", 4500))))
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()
        assertEquals("Café cambió de precio: ahora cuesta \$50.00.\nTotal nuevo: \$50.00.", vm.avisoDePrecio.value)

        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals(listOf(4500), montos)
        assertNull(vm.avisoDePrecio.value)
    }

    @Test
    fun `el total nuevo es el que de verdad se cobra, con el premio que confirmó el servidor`() = runTest {
        val premio30 = PremioPorAplicar(id = "rw1", etiqueta = "\$30 de premio", tipo = "FIXED_AMOUNT", valor = 30.0)
        cuandoSeCreaLaOrden(
            rechazo409(cambio("e1", 9000, 10000)),
            creada(1.0, premio = StampRewardOnOrder(applied = true, discountAmount = 25.0)),
        )
        val montos = montoEnLaTerminal()

        vm.startPaymentFlow(CartState(items = listOf(linea("e1", 9000)), pendingStampReward = premio30))
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()
        assertEquals("Café cambió de precio: ahora cuesta \$100.00.\nTotal nuevo: \$70.00.", vm.avisoDePrecio.value)

        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals(listOf(7500), montos)
        assertEquals("Café cambió de precio: ahora cuesta \$100.00.\nTotal nuevo: \$75.00.", vm.avisoDePrecio.value)
    }

    // MARK: - Lo derivado del total cuadra con la orden

    @Test
    fun `pago por artículos en dos pagos - las dos partes suman el total de la orden`() = runTest {
        val carrito = carritoConDosProductos()
        val (e1, e2) = carrito.cartState.value.items.map { it.id }
        cuandoSeCreaLaOrden(creada(90.0, CambioDePrecio(e1, "p1", "Café", catalogo, 5000, 4500), CambioDePrecio(e2, "p2", "Té", catalogo, 5000, 4500)))
        val cobrados = cobradoEnEfectivo(pagado(saldo = 4500, estado = "PARTIAL"), pagado(saldo = 0, estado = "PAID"))

        vm.setSplitConfig(type = "BYPRODUCT", selectedItemIds = listOf(e1))
        vm.startPaymentFlow(carrito.getCartForPayment())
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(5000)
        advanceUntilIdle()
        assertEquals("la parte se recalcula", listOf(4500), cobrados)

        val completion = vm.consumeCompletion()!!
        assertEquals(2, completion.cambiosDePrecio.size)
        carrito.aplicar(completion)
        assertEquals(listOf(e2 to 4500), carrito.cartState.value.items.map { it.id to it.unitPrice })

        vm.setSplitType("FULLPAYMENT")
        vm.startPaymentFlow(carrito.getCartForPayment(), resumeOrderId = completion.orderId)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(4500)
        advanceUntilIdle()

        assertEquals(listOf(4500, 4500), cobrados)
        assertEquals("suma = total de la orden", 9000, cobrados.sum())
        coVerify(exactly = 1) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) }
    }

    @Test
    fun `partes iguales en 2 - 45 cada una tras bajar`() = runTest {
        cuandoSeCreaLaOrden(creada(90.0, cambio("e1", 5000, 4500), cambio("e2", 5000, 4500)))
        val cobrados = cobradoEnEfectivo()

        vm.setSplitConfig(type = "EQUALPARTS", numberOfParts = 2)
        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(5000)
        advanceUntilIdle()

        assertEquals(listOf(4500), cobrados)
        assertEquals(4500, vm.consumeCompletion()!!.remainingBalanceCents)
    }

    @Test
    fun `el monto elegido por el cajero se conserva, topado al total nuevo`() = runTest {
        for ((elegido, esperado) in listOf(3000 to 3000, 9500 to 9000)) {
            cuandoSeCreaLaOrden(creada(90.0, cambio("e1", 5000, 4500), cambio("e2", 5000, 4500)))
            val cobrados = cobradoEnEfectivo()
            val caja = vm()

            caja.setSplitConfig(type = "CUSTOMAMOUNT", customAmountCents = elegido)
            caja.startPaymentFlow(dosCafes)
            caja.selectPaymentMethod(PaymentMethod.CASH)
            caja.processCashPayment(elegido)
            advanceUntilIdle()

            assertEquals("eligió $elegido", listOf(esperado), cobrados)
        }
    }

    @Test
    fun `cobro parcial, volver al carrito y cambiar cantidad - sin cambios de precio no se congela nada`() = runTest {
        val carrito = carritoConDosProductos(descuento = diezPorCiento)
        val (e1, e2) = carrito.cartState.value.items.map { it.id }
        cuandoSeCreaLaOrden(creada(90.0))
        // D10 (27-oct): el café paga su parte del 10 % ($45), así que quedan $45 — los mismos que muestra el té.
        cobradoEnEfectivo(pagado(saldo = 4500, estado = "PARTIAL"))

        vm.setSplitConfig(type = "BYPRODUCT", selectedItemIds = listOf(e1))
        vm.startPaymentFlow(carrito.getCartForPayment())
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(5000)
        advanceUntilIdle()
        val completion = vm.consumeCompletion()!!
        assertTrue(completion.cambiosDePrecio.isEmpty())
        carrito.aplicar(completion)

        assertNull(carrito.cartState.value.descuentoDeCuentaCongelado)
        carrito.incrementQuantity(e2)
        assertEquals("el 10 % de siempre", 1000, carrito.cartState.value.orderDiscountCents)
    }

    @Test
    fun `cobro parcial, volver al carrito y cambiar cantidad - con cambios el resto cobra 45 menos 10 hasta que se edita`() = runTest {
        val carrito = carritoConDosProductos(descuento = diezPorCiento)
        val (e1, e2) = carrito.cartState.value.items.map { it.id }
        cuandoSeCreaLaOrden(creada(80.0, CambioDePrecio(e1, "p1", "Café", catalogo, 5000, 4500), CambioDePrecio(e2, "p2", "Té", catalogo, 5000, 4500)))
        cobradoEnEfectivo(pagado(saldo = 3500, estado = "PARTIAL"))

        vm.setSplitConfig(type = "BYPRODUCT", selectedItemIds = listOf(e1))
        vm.startPaymentFlow(carrito.getCartForPayment())
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(5000)
        advanceUntilIdle()
        carrito.aplicar(vm.consumeCompletion()!!)

        assertEquals("45 menos los 10 que viajaron = el saldo de la orden", 3500, carrito.cartState.value.totalCents)
        carrito.incrementQuantity(e2)
        assertEquals("al editar vuelve el 10 %", 900, carrito.cartState.value.orderDiscountCents)
    }

    // MARK: - Pagar después

    @Test
    fun `pagar después con 409 - corrige el carrito sin congelar y el fallo dice el aviso`() = runTest {
        val carrito = carritoConDosProductos(soloUno = true, precio = 100.0, descuento = diezPorCiento)
        val e1 = carrito.cartState.value.items.single().id
        coEvery {
            ordenesDelCarrito.createOrder(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(OrderRepository.ServerException(409, "El precio cambió.", listOf(CambioDePrecio(e1, "p1", "Café", catalogo, 10000, 12500))))

        val resultado = carrito.createPayLaterOrder("cliente-1")

        assertEquals("Café cambió de precio: ahora cuesta \$125.00.\nTotal nuevo: \$112.50.", resultado.exceptionOrNull()?.message)
        assertEquals(12500, carrito.cartState.value.items.single().unitPrice)
        assertNull(carrito.cartState.value.descuentoDeCuentaCongelado)
        assertEquals(11250, carrito.cartState.value.totalCents)
        coVerify(exactly = 2) { productsRepository.fetchProducts(any(), any()) }
    }

    // MARK: - Lo demás no cambia

    @Test
    fun `sin red se encola como hoy y no hay aviso de precio`() = runTest {
        cuandoSeCreaLaOrden(sinRed())
        coEvery {
            cashPaymentRepository.queueCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), venueId = any())
        } returns "cola-1"

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10000)
        advanceUntilIdle()

        assertTrue((vm.state.value as PaymentFlowState.Success).isQueued)
        assertNull(vm.avisoDePrecio.value)
        verify(exactly = 0) { refresco.refrescarAhora() }
    }

    @Test
    fun `otra venta empieza sin el aviso ni los cambios de la anterior`() = runTest {
        cuandoSeCreaLaOrden(creada(90.0, cambio("e1", 5000, 4500), cambio("e2", 5000, 4500)))
        montoEnLaTerminal()
        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        vm.startPaymentFlow(CartState(items = listOf(linea("e9", 3000))))

        assertNull(vm.avisoDePrecio.value)
        assertTrue(vm.buildCompletion().cambiosDePrecio.isEmpty())
    }

    @Test
    fun `confirmPayment sella la hora de captura una sola vez`() = runTest {
        val pedidos = mutableListOf<CreateOrderRequest>()
        cuandoSeCreaLaOrden(sinRed(), pedidos = pedidos)
        val encolado = slot<CreateOrderRequest>()
        coEvery {
            cashPaymentRepository.queueCashPayment(capture(encolado), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), venueId = any())
        } returns "cola-1"

        val antes = System.currentTimeMillis()
        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.confirmPayment()
        advanceUntilIdle()
        val despues = System.currentTimeMillis()

        val hora = encolado.captured.createdAtLocal
        assertTrue("$hora", hora != null && hora in antes..despues)
        assertEquals("el mismo pedido capturado va al intento en línea y a la cola", hora, pedidos.single().createdAtLocal)
    }

    // MARK: - El cable

    @Test
    fun `el 409 del servidor llega con sus cambios y el 201 con los suyos`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val storage = mockk<SecureStorage>(relaxed = true)
            every { storage.venueId } returns "venue-1"
            every { storage.accessToken } returns "token-1"
            val repo = OrderRepository(storage, OkHttpClient()).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }
            val pedido = CreateOrderRequest(items = listOf(OrderItemRequest(productId = "p1", name = "Café", quantity = 1, unitPrice = 4500)), subtotal = 4500, total = 4500, paymentMethod = "CARD")
            val unCambio = """{"externalId":"e1","productId":"p1","productName":"Café","catalogAt":"$catalogo","displayedUnitPriceCents":4500,"unitPriceCents":5000}"""
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"El precio cambió.","code":"ORDER_PRICE_CHANGED","details":{"priceChanges":[$unCambio]}}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"order":{"id":"order-1","total":50,"priceChanges":[$unCambio]}}"""))

            val rechazo = repo.createOrder(pedido, staffId = "s1").exceptionOrNull() as OrderRepository.ServerException
            val creadaOk = repo.createOrder(pedido, staffId = "s1").getOrThrow()

            assertEquals(409, rechazo.code)
            assertEquals(listOf(cambio("e1", 4500, 5000)), rechazo.cambiosDePrecio)
            assertEquals(listOf(cambio("e1", 4500, 5000)), creadaOk.data?.priceChanges)
        } finally {
            server.shutdown()
        }
    }

    // MARK: - Andamiaje

    /**
     * Un DAO en memoria para la reserva del efectivo: lo que el aparato tendría en disco, con las reglas de la SQL real
     * (`borrarSiEnVuelo` no borra —`AND 0`—; `descartarReservaRechazada` sólo un `EN_VUELO` con captura; el apartado nuevo y el
     * borrado de los viejos van en una sola escritura). La SQL de verdad la fija `ApartadosDelEfectivoRoomTest` (escritorio).
     */
    private inner class DaoFalso(var fallaAlGuardar: Boolean = false) {
        val filas = linkedMapOf<String, PendingPaymentEntity>()
        private fun descartar(id: String) {
            if (filas[id]?.let { it.syncStatus == "EN_VUELO" && it.q15CaptureJson != null && it.q15Reason == null } == true) filas.remove(id)
        }
        /** Como `insertTransactionQ16`: una fila ya subida no se pisa, y el reemplazo conserva la captura de disco. */
        private fun guardar(fila: PendingPaymentEntity) {
            val vieja = filas[fila.id]
            if (vieja?.syncStatus == "SYNCED") return
            filas[fila.id] = com.avoqado.pos.core.data.local.database.filaAlReemplazar(vieja, fila)
        }
        val dao = mockk<PendingPaymentDao>(relaxed = true).also { dao ->
            coEvery { dao.insert(any()) } coAnswers {
                if (fallaAlGuardar) throw IOException("disco lleno")
                guardar(firstArg())
            }
            coEvery { dao.reservarEnLugarDe(any(), any()) } coAnswers {
                if (fallaAlGuardar) throw IOException("disco lleno")
                secondArg<Collection<String>>().forEach(::descartar)
                guardar(firstArg())
            }
            coEvery { dao.borrarSiEnVuelo(any()) } returns 0
            coEvery { dao.descartarReservaRechazada(any()) } coAnswers { val antes = filas.size; descartar(firstArg()); antes - filas.size }
            coEvery { dao.findForVenue(any(), any()) } coAnswers { filas[secondArg<String>()]?.takeIf { it.venueId == firstArg<String>() } }
            coEvery { dao.enVueloDelVenue(any()) } coAnswers { filas.values.filter { it.venueId == firstArg<String>() && it.syncStatus == "EN_VUELO" } }
            // Ronda corta (agregados 1 y 3): al nacer la orden, el apartado guarda su importe y su orden; 0 filas = fallo.
            coEvery { dao.actualizarApartadoEnVuelo(any(), any(), any()) } coAnswers {
                val f = filas[firstArg()]?.takeIf { it.syncStatus == "EN_VUELO" } ?: return@coAnswers 0
                filas[f.id] = f.copy(amountCents = secondArg(), orderId = thirdArg()); 1
            }
        }
        /** UNO por disco: la propiedad de los apartados (qué es de un flujo vivo) vive en el repositorio. */
        private val repo by lazy { CashPaymentRepository(secureStorage, dao) }
        fun repositorio() = repo
    }

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val kdsRepository = mockk<KDSRepository>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val refresco = mockk<RefrescoDelCatalogo>(relaxed = true)

    // Del carrito (para «volver al carrito» y «pagar después»).
    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val ordenesDelCarrito = mockk<OrderRepository>(relaxed = true)

    private lateinit var vm: PaymentFlowViewModel

    private fun vm(caja: CashPaymentRepository = cashPaymentRepository) = PaymentFlowViewModel(
        orderRepository = orderRepository,
        cashPaymentRepository = caja,
        tenderTypeRepository = mockk(relaxed = true),
        terminalPaymentService = terminalPaymentService,
        tpvSettingsRepository = tpvSettingsRepository,
        paymentSyncService = mockk<PaymentSyncService>(relaxed = true),
        cashDrawerRepository = cashDrawerRepository,
        kdsRepository = kdsRepository,
        kdsOrderBus = mockk<KDSOrderBus>(relaxed = true),
        printerService = printerService,
        secureStorage = secureStorage,
        comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
        replayDeComandas = mockk(relaxed = true),
        comandaDispatcher = ComandaDispatcher(
            mockk<PrintConfigRepository>(relaxed = true),
            ReintentoDeComanda(mockk<ComandaPrinter>(relaxed = true), reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
            printerService,
        ),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        syncOutbox = mockk(relaxed = true),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = areaTicketRepository,
        cancelacionDeCobro = mockk(relaxed = true),
        savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        refrescoDelCatalogo = refresco,
    )

    private fun carritoConDosProductos(soloUno: Boolean = false, precio: Double = 50.0, descuento: Discount? = null): CartViewModel {
        val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
        val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
        val authRepository = mockk<AuthRepository>(relaxed = true)
        val staffRepository = mockk<StaffRepository>(relaxed = true)
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
        return CartViewModel(
            productsRepository = productsRepository,
            discountsRepository = discountsRepository,
            savedCartsRepository = savedCartsRepository,
            authRepository = authRepository,
            secureStorage = secureStorage,
            activeCartState = mockk<ActiveCartState>(relaxed = true),
            orderRepository = ordenesDelCarrito,
            staffRepository = staffRepository,
            classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true).also { every { it.consume() } returns null },
            validateReferralUseCase = mockk(relaxed = true),
            captureReferralUseCase = mockk(relaxed = true),
            planManager = PlanManager(secureStorage),
            tableSession = com.avoqado.pos.tables.data.TableSession(),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = mockk(relaxed = true),
            walletScanRepository = mockk(relaxed = true),
        ).apply {
            addProduct(Product(id = "p1", name = "Café", priceValue = precio, catalogAt = catalogo))
            if (!soloUno) addProduct(Product(id = "p2", name = "Té", priceValue = precio, catalogAt = catalogo))
            descuento?.let(::applyOrderDiscount)
        }
    }

    /** Lo que hace `CheckoutScreen.onPaymentCommitted` con el resultado del cobro. */
    private fun CartViewModel.aplicar(c: com.avoqado.pos.payment.presentation.PaymentCompletion) = aplicarCobroConfirmado(
        splitType = c.splitType,
        paidItemIds = c.paidItemIds,
        remainingBalanceCents = c.remainingBalanceCents,
        premioConfirmadoCents = c.premioConfirmadoCents,
        carritoGuardadoDelCobro = c.carritoGuardadoDelCobro,
        identidadDeVentaDelCobro = c.identidadDeVenta,
        cambiosDePrecio = c.cambiosDePrecio,
        descuentoDeCuentaCongelado = c.descuentoDeCuentaCongelado,
    )

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1", isOnline = true, hasSocket = true)))
        every { cashPaymentRepository.processCashPayment(any(), any()) } answers {
            val cambio = secondArg<Int>() - firstArg<Int>()
            if (cambio >= 0) CashPaymentResult.Success(changeCents = cambio) else CashPaymentResult.InsufficientFunds(shortfall = -cambio)
        }
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } returns null
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { areaTicketRepository.session.current() } returns null
        vm = vm()
    }
}
