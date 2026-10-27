package com.avoqado.pos.onlineorders

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.OnlineOrdersViewModel
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.carritoDelCobro
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.EfectivoDelPedido
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.pos.data.model.CartItemType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Task A5: «Cobrar» un pedido de la tienda web desde la bandeja (sin mesa). Lo que decide la bandeja ANTES de abrir el cobro:
 * el saldo (del servidor; sin red, el último de la bandeja), saldo 0 = no abre, y un efectivo de ese pedido guardado sin subir
 * en ESTE aparato = no abre otro (Review Focus 4 y 6). Lo que pasa DENTRO del cobro (mesa en PAYING, cambio con
 * `cashRetainedCents`) se prueba en `PaymentFlowViewModelTest`, sección «A5».
 *
 * La cola de efectivo es la REAL (`CashPaymentRepository` sobre un DAO falso): el filtro de qué filas bloquean es dinero y
 * vive en Kotlin, donde estas pruebas lo fijan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroDePedidoEnLineaTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<OnlineOrdersRepository>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private val cola = CashPaymentRepository(secureStorage, dao)

    /** Las filas de `pending_payments` del negocio, tal como las devolvería Room. */
    private var filas: List<PendingPaymentEntity> = emptyList()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { secureStorage.venueId } returns "venue-1"
        coEvery { dao.forVenue("venue-1") } answers { filas }
        coEvery { repo.inbox(any(), any(), any()) } answers { Result.success(bandeja(webOrder)) }
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    /** La bandeja dice $200 por cobrar. */
    private val webOrder = pedido("web-1", restante = 20000)

    private fun pedido(id: String, total: Long = 20000, restante: Long = 20000) = OnlineOrder(
        orderId = id, onlineOrderId = "oo-$id", orderNumber = "W$id", status = OnlineOrderStatus.READY,
        paymentMode = "PAY_AT_PICKUP", paymentStatus = "PENDING", customerName = "Ana", customerPhone = "5512345678", note = null,
        createdAt = Instant.parse("2026-10-22T10:00:00Z"), acceptDeadlineAt = null, estimatedReadyAt = null,
        totalCents = total, remainingCents = restante, needsPrint = false, items = emptyList(),
    )

    private fun bandeja(vararg pedidos: OnlineOrder) = Inbox(
        serverTime = Instant.parse("2026-10-22T10:00:00Z"), pending = 0, active = pedidos.size, done = 0,
        orders = pedidos.toList(), storeOff = false, truncated = false, nextCursor = null,
    )

    private fun fila(orderId: String?, estado: String, q15Reason: String? = null) = PendingPaymentEntity(
        id = "fila-$orderId-$estado", venueId = "venue-1", staffId = "staff-1", amountCents = 20000, tipCents = 0,
        method = "CASH", paymentType = "ORDER", orderId = orderId, syncStatus = estado, q15Reason = q15Reason,
    )

    private fun armar(puedeCobrar: Boolean = true) = OnlineOrdersViewModel(
        repositorio = repo,
        permisoCobrar = { puedeCobrar },
        permisoActualizar = { true },
        permisoCancelar = { true },
        tienePlan = { true },
        hayPedidosAbiertos = { true },
        ventanaALaVista = MutableStateFlow(true),
        efectivoDelPedido = { cola.efectivoDelPedido(it) },
        efectivoYaSubido = { cola.huboEfectivoSubido(it) },
    )

    @Test fun `kitchen cannot open a payment without payments create`() = runTest {
        coEvery { repo.saldoCents(any()) } returns Result.success(20000L)
        val vm = armar(puedeCobrar = false)
        vm.cobrar(webOrder)
        advanceUntilIdle()
        assertNull(vm.cobroAbierto.value)
        coVerify(exactly = 0) { repo.saldoCents(any()) }
    }

    // MARK: - El saldo

    @Test
    fun `uses server balance over inbox remaining`() = runTest(dispatcher) {
        // Review Focus 4: la bandeja dice $200, pero en otro aparato le hicieron un descuento o un abono: el detalle dice $150.
        coEvery { repo.saldoCents("web-1") } returns Result.success(15000L)
        val vm = armar()
        vm.refrescar()

        vm.cobrar(webOrder)
        advanceUntilIdle()

        val cobro = vm.cobroAbierto.value!!
        assertEquals("web-1", cobro.orderId)
        assertEquals("se cobra lo que dice el servidor, nunca la bandeja", 15000, cobro.saldoCents)
        val carrito = carritoDelCobro(vm.cobroAbierto.value!!)
        assertEquals("se cobra lo que dice el servidor, nunca la bandeja", 15000, carrito.totalCents)
        val renglon = carrito.items.single()
        assertEquals(CartItemType.CustomAmount, renglon.type)
        assertEquals(15000, renglon.unitPrice)
        assertEquals(1, renglon.quantity)
        assertEquals("PICKUP", carrito.orderType)
    }

    @Test
    fun `offline uses last inbox remaining`() = runTest(dispatcher) {
        // Ya se abonó algo: la bandeja dice $180 de $200. Sin red, ese último saldo es lo que se cobra (contrato con Rappi 2b).
        val abonado = pedido("web-1", total = 20000, restante = 18000)
        coEvery { repo.saldoCents("web-1") } returns Result.failure(IOException("sin red"))
        val vm = armar()

        vm.cobrar(abonado)
        advanceUntilIdle()

        assertEquals(com.avoqado.pos.payment.domain.OrigenDelCobro.PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO, vm.cobroAbierto.value?.origen)
        assertEquals(18000, vm.cobroAbierto.value?.saldoCents)
        assertEquals(18000, carritoDelCobro(vm.cobroAbierto.value!!).totalCents)
        assertNull("sin red es un estado normal: no hay aviso", vm.aviso.value)
    }

    @Test
    fun `zero balance does not open payment`() = runTest(dispatcher) {
        coEvery { repo.saldoCents("web-1") } returns Result.success(0L)
        val vm = armar()

        vm.cobrar(webOrder)
        advanceUntilIdle()

        assertNull("nada que cobrar: no se abre el cobro", vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.PAGADO, vm.aviso.value)
        // La bandeja se relee para que la tarjeta diga «Pagado» (lo que dice el servidor).
        coVerify(atLeast = 1) { repo.inbox("all", any(), any()) }
    }

    @Test
    fun `offline with a zero tray remaining does not open payment either`() = runTest(dispatcher) {
        coEvery { repo.saldoCents("web-1") } returns Result.failure(IOException("sin red"))
        val vm = armar()

        vm.cobrar(pedido("web-1", restante = 0))
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.PAGADO, vm.aviso.value)
    }

    @Test
    fun `a server error reading the balance does not open payment`() = runTest(dispatcher) {
        // No es falta de red (un 404/500): no se adivina con la bandeja, se dice y se reintenta.
        coEvery { repo.saldoCents("web-1") } returns Result.failure(IllegalStateException("Error del servidor (500)"))
        val vm = armar()

        vm.cobrar(webOrder)
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.SALDO_NO_LEIDO, vm.aviso.value)
    }

    @Test
    fun `only one charge opens at a time`() = runTest(dispatcher) {
        coEvery { repo.saldoCents(any()) } returns Result.success(15000L)
        val vm = armar()
        vm.cobrar(webOrder)
        advanceUntilIdle()

        vm.cobrar(pedido("web-2"))
        advanceUntilIdle()

        assertEquals("web-1", vm.cobroAbierto.value?.orderId)
        coVerify(exactly = 0) { repo.saldoCents("web-2") }
    }

    // MARK: - Un cobro por pedido a la vez en este aparato (Review Focus 6)

    @Test
    fun `a pending queued cash for the same order blocks a second charge`() = runTest(dispatcher) {
        filas = listOf(fila("web-1", "PENDING"))
        coEvery { repo.saldoCents(any()) } returns Result.success(20000L)
        val vm = armar()

        vm.cobrar(webOrder)
        advanceUntilIdle()

        assertNull("no se abre un segundo cobro del mismo pedido", vm.cobroAbierto.value)
        assertEquals("Cobro en efectivo guardado: se sube al volver la conexión", vm.aviso.value)
        coVerify(exactly = 0) { repo.saldoCents(any()) }
    }

    @Test
    fun `a SYNCING one too`() = runTest(dispatcher) {
        // Codex plan r2 #1: al reconectar la fila pasa a SYNCING; si la red vuelve a caer en ese envío, sigue siendo dinero.
        filas = listOf(fila("web-1", "SYNCING"))
        coEvery { repo.saldoCents(any()) } returns Result.success(20000L)
        val vm = armar()

        vm.cobrar(webOrder)
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.COBRO_EN_EFECTIVO_GUARDADO, vm.aviso.value)
    }

    @Test
    fun `an EN_VUELO one waits for a manager and says so`() = runTest(dispatcher) {
        // Ruling 21 #2: guardado antes del POST y sin desenlace (proceso que murió, o un 4xx que dejó la reserva): pudo llegar
        // al servidor, así que bloquea, pero NADA lo sube solo. Decirle «se sube al volver la conexión» le mentía al cajero.
        filas = listOf(fila("web-1", "EN_VUELO"))
        coEvery { repo.saldoCents(any()) } returns Result.success(20000L)
        val vm = armar()

        vm.cobrar(webOrder)
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(
            "Este pedido tiene un cobro en efectivo por revisar: un gerente lo resuelve en Operaciones por revisar",
            vm.aviso.value,
        )
        coVerify(exactly = 0) { repo.saldoCents(any()) }
    }

    @Test
    fun `a PENDING held for review waits for a manager too`() = runTest(dispatcher) {
        // La cola (`PaymentSyncService`) no manda una fila con motivo de revisión: espera a un gerente.
        filas = listOf(fila("web-1", "PENDING", q15Reason = "RESULTADO_NO_COMPROBADO"))
        coEvery { repo.saldoCents(any()) } returns Result.success(20000L)
        val vm = armar()

        vm.cobrar(webOrder)
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.COBRO_EN_EFECTIVO_POR_REVISAR, vm.aviso.value)
    }

    @Test
    fun `the three states of this device's cash for an order`() = runTest {
        filas = listOf(
            fila("otro-pedido", "PENDING"),
            fila("web-1", "SYNCED"),
            fila(null, "PENDING"),
        )
        assertEquals(EfectivoDelPedido.NINGUNO, cola.efectivoDelPedido("web-1"))

        // 🔴 DINERO (PR #53): ninguno prueba que el dinero no se recibió, así que no deja cobrar otra vez.
        // FAILED también es un efectivo que agotó sus reintentos de red; el gerente lo reintenta en «Operaciones por revisar».
        filas = listOf(fila("web-1", "FAILED"))
        assertEquals(EfectivoDelPedido.POR_REVISAR, cola.efectivoDelPedido("web-1"))
        // El gerente dijo «no se cobró»: la afirmación sola no prueba que no se cobró (`descartarPorqueNoSeCobro`).
        filas = listOf(fila("web-1", "EN_VUELO", q15Reason = "RESULTADO_NO_COMPROBADO"))
        assertEquals(EfectivoDelPedido.POR_REVISAR, cola.efectivoDelPedido("web-1"))

        filas = listOf(fila("web-1", "PENDING"))
        assertEquals(EfectivoDelPedido.EN_COLA, cola.efectivoDelPedido("web-1"))
        filas = listOf(fila("web-1", "SYNCING"))
        assertEquals(EfectivoDelPedido.EN_COLA, cola.efectivoDelPedido("web-1"))

        filas = listOf(fila("web-1", "EN_VUELO"))
        assertEquals(EfectivoDelPedido.POR_REVISAR, cola.efectivoDelPedido("web-1"))
        filas = listOf(fila("web-1", "PENDING", q15Reason = "CAPTURA_SIN_EVIDENCIA"))
        assertEquals(EfectivoDelPedido.POR_REVISAR, cola.efectivoDelPedido("web-1"))
        filas = listOf(fila("web-1", "PENDING", q15Reason = "PRICE_PROOF_PENDING"))
        assertEquals("retenido por precio: la cola lo detiene igual que a los demás", EfectivoDelPedido.POR_REVISAR, cola.efectivoDelPedido("web-1"))

        // Por revisar gana a «en cola»: el aviso dice lo que necesita una persona.
        filas = listOf(fila("web-1", "PENDING"), fila("web-1", "EN_VUELO"))
        assertEquals(EfectivoDelPedido.POR_REVISAR, cola.efectivoDelPedido("web-1"))
    }

    @Test
    fun `without a venue nothing blocks and nothing is read`() = runTest {
        every { secureStorage.venueId } returns null
        filas = listOf(fila("web-1", "PENDING"), fila("web-1", "SYNCED"))
        assertEquals(EfectivoDelPedido.NINGUNO, cola.efectivoDelPedido("web-1"))
        assertFalse(cola.huboEfectivoSubido("web-1"))
    }

    // MARK: - Sin red, la bandeja vieja no sabe de un efectivo que ya subió (Ruling 21 #3)

    @Test
    fun `offline with this device's cash already uploaded after the tray does not open`() = runTest(dispatcher) {
        // Efectivo encolado sin red → el WiFi vuelve unos segundos, la cola lo sube (SYNCED) → se cae otra vez antes de que la
        // bandeja se relea. La bandeja sigue diciendo $150 por cobrar: abrir aquí cobraría dos veces con otra llave.
        val vm = armar()
        coEvery { repo.inbox(any(), any(), any()) } returns Result.success(bandeja(pedido("web-1", restante = 15000)))
        vm.refrescar()
        filas = listOf(fila("web-1", "SYNCED"))
        coEvery { repo.saldoCents("web-1") } returns Result.failure(IOException("sin red"))

        vm.cobrar(pedido("web-1", restante = 15000))
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.REINTENTA, vm.aviso.value)
    }

    @Test
    fun `online the server balance is the truth even with uploaded cash`() = runTest(dispatcher) {
        // Con red el saldo del servidor ya cuenta lo subido: un abono anterior de este aparato no frena el resto.
        filas = listOf(fila("web-1", "SYNCED"))
        coEvery { repo.saldoCents("web-1") } returns Result.success(5000L)
        val vm = armar()

        vm.cobrar(webOrder)
        advanceUntilIdle()

        assertEquals(5000, vm.cobroAbierto.value?.saldoCents)
    }

    @Test
    fun `offline without uploaded cash still uses the tray`() = runTest(dispatcher) {
        filas = listOf(fila("otro-pedido", "SYNCED"))
        coEvery { repo.saldoCents("web-1") } returns Result.failure(IOException("sin red"))
        val vm = armar()

        vm.cobrar(pedido("web-1", restante = 15000))
        advanceUntilIdle()

        assertEquals(15000, vm.cobroAbierto.value?.saldoCents)
    }

    // MARK: - Dos toques rápidos en pedidos distintos (Ruling 21 #4)

    @Test
    fun `a slow second tap never replaces a charge already open`() = runTest(dispatcher) {
        coEvery { repo.saldoCents("web-1") } returns Result.success(20000L)
        coEvery { repo.saldoCents("web-2") } coAnswers { delay(5_000); Result.success(9000L) }
        val vm = armar()

        vm.cobrar(webOrder)
        vm.cobrar(pedido("web-2"))
        advanceUntilIdle()

        assertEquals("el cobro abierto sigue siendo el primero", "web-1", vm.cobroAbierto.value?.orderId)
        assertEquals(20000, vm.cobroAbierto.value?.saldoCents)
    }

    // MARK: - Después de cobrar

    @Test
    fun `never calls delivered after charging`() = runTest(dispatcher) {
        coEvery { repo.saldoCents("web-1") } returns Result.success(20000L)
        val vm = armar()
        vm.refrescar()
        vm.cobrar(webOrder)
        advanceUntilIdle()
        assertTrue(vm.cobroAbierto.value != null)

        // El cobro terminó (éxito, encolado o cancelado): se cierra y se relee la bandeja. «Entregado» lo decide el servidor.
        vm.cobroTerminado()
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        coVerify(exactly = 2) { repo.inbox("all", any(), any()) }
        coVerify(exactly = 0) { repo.delivered(any()) }
    }

    @Test
    fun `each Cobrar is a new opening and the open one keeps its own`() = runTest(dispatcher) {
        // «El cobro se reiniciaba al volver a la pestaña»: la apertura es UNA por toque de «Cobrar». Mientras el cobro sigue
        // abierto se conserva (la pantalla puede volver a componerse); uno nuevo nace con otra.
        coEvery { repo.saldoCents("web-1") } returns Result.success(20000L)
        val vm = armar()
        vm.cobrar(webOrder)
        advanceUntilIdle()
        val primera = vm.cobroAbierto.value!!.apertura
        vm.cobrar(webOrder)
        advanceUntilIdle()
        assertEquals(primera, vm.cobroAbierto.value!!.apertura)

        vm.cobroTerminado()
        advanceUntilIdle()
        vm.cobrar(webOrder)
        advanceUntilIdle()
        assertTrue(primera != vm.cobroAbierto.value!!.apertura)
    }

    @Test
    fun `reads cashRetainedCents from the pay response`() {
        // La respuesta de `POST /orders/:o/pay` (Task S2): sobrepago de $100 debidos con $150 ⇒ se quedan $100 en el cajón.
        val body = """{"success":true,"payment":{"paymentId":"p1","amount":10000,"tipAmount":0,"cashRetainedCents":10000,"changeCents":5000}}"""
        assertEquals(10000, OrderRepository.extractCashRetainedCentsFromResponse(body))
        assertNull(OrderRepository.extractCashRetainedCentsFromResponse("""{"success":true,"payment":{"amount":10000}}"""))
        assertNull(OrderRepository.extractCashRetainedCentsFromResponse("""{"payment":{"cashRetainedCents":"10000"}}"""))
        assertNull(OrderRepository.extractCashRetainedCentsFromResponse("""{"payment":{"cashRetainedCents":-1}}"""))
    }
}
