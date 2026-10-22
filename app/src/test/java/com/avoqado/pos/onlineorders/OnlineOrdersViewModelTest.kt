package com.avoqado.pos.onlineorders

import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.data.ResultadoImpresion
import com.avoqado.pos.onlineorders.data.VigilanteDePedidosEnLinea
import com.avoqado.pos.onlineorders.domain.AccionResultado
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.EntradaPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.OnlineOrdersViewModel
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.debeAbrirBandeja
import com.avoqado.pos.onlineorders.presentation.entradaDePedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.formatoPesos
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Task A3: la pantalla «Pedidos en línea». Repositorio falso (mockk) y reloj virtual: nada de red ni de tiempo real. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineOrdersViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<OnlineOrdersRepository>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun pedido(
        id: String,
        estado: OnlineOrderStatus,
        total: Long = 20000,
        restante: Long = 20000,
        pago: String = "PENDING",
        needsPrint: Boolean = false,
    ) = OnlineOrder(
        orderId = id, onlineOrderId = "o-$id", orderNumber = id, status = estado,
        paymentMode = "PAY_AT_PICKUP", paymentStatus = pago, customerName = "Ana", customerPhone = "5512345678", note = null,
        createdAt = Instant.parse("2026-10-22T10:00:00Z"), acceptDeadlineAt = null, estimatedReadyAt = null,
        totalCents = total, remainingCents = restante, needsPrint = needsPrint, items = emptyList(),
    )

    private fun bandeja(
        vararg pedidos: OnlineOrder,
        truncated: Boolean = false,
        nextCursor: String? = null,
        storeOff: Boolean = false,
    ) = Inbox(
        serverTime = Instant.parse("2026-10-22T10:00:00Z"),
        pending = pedidos.count { it.status == OnlineOrderStatus.PENDING_ACCEPTANCE },
        active = pedidos.count { it.status == OnlineOrderStatus.ACCEPTED || it.status == OnlineOrderStatus.READY },
        done = 0, orders = pedidos.toList(), storeOff = storeOff, truncated = truncated, nextCursor = nextCursor,
    )

    private class Permisos(var actualizar: Boolean = true, var cancelar: Boolean = true)

    private fun armar(
        permisos: Permisos = Permisos(),
        ventana: MutableStateFlow<Boolean> = MutableStateFlow(true),
        tienePlan: Boolean = true,
        hayAbiertos: Boolean = false,
    ) = OnlineOrdersViewModel(
        repositorio = repo,
        permisoActualizar = { permisos.actualizar },
        permisoCancelar = { permisos.cancelar },
        tienePlan = { tienePlan },
        hayPedidosAbiertos = { hayAbiertos },
        ventanaALaVista = ventana,
        sondearVigilante = { sondeosYa++ },
        imprimirSiToca = { impresiones += it.orderId; ResultadoImpresion.Impresa },
        reimprimirPedido = { reimpresiones += it.orderId; resultadoReimpresion },
    )

    private val impresiones = mutableListOf<String>()
    private val reimpresiones = mutableListOf<String>()
    private var resultadoReimpresion: ResultadoImpresion = ResultadoImpresion.Impresa

    private var sondeosYa = 0

    @Test
    fun `groups orders into the four sections`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(
            bandeja(
                pedido("p1", OnlineOrderStatus.PENDING_ACCEPTANCE),
                pedido("a1", OnlineOrderStatus.ACCEPTED),
                pedido("a2", OnlineOrderStatus.ACCEPTED),
                pedido("r1", OnlineOrderStatus.READY),
                pedido("c1", OnlineOrderStatus.COMPLETED),
                pedido("x1", OnlineOrderStatus.REJECTED),
                pedido("e1", OnlineOrderStatus.EXPIRED),
                pedido("k1", OnlineOrderStatus.CANCELLED_BY_CUSTOMER),
            ),
        )
        val vm = armar()
        vm.refrescar()
        val s = vm.secciones.value
        assertEquals(listOf("p1"), s.porAceptar.map { it.orderId })
        assertEquals(listOf("a1", "a2"), s.enPreparacion.map { it.orderId })
        assertEquals(listOf("r1"), s.listos.map { it.orderId })
        assertEquals(listOf("c1", "x1", "e1", "k1"), s.hoy.map { it.orderId })
    }

    @Test
    fun `accept sends minutes and rereads`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returnsMany listOf(
            Result.success(bandeja(pedido("p1", OnlineOrderStatus.PENDING_ACCEPTANCE))),
            Result.success(bandeja(pedido("p1", OnlineOrderStatus.ACCEPTED))),
        )
        coEvery { repo.accept("p1", 15) } returns AccionResultado.Hecho
        val vm = armar()
        vm.refrescar()
        vm.aceptar("p1", 15)
        runCurrent()
        coVerify(exactly = 1) { repo.accept("p1", 15) }
        coVerify(exactly = 2) { repo.inbox("all", any(), any()) }
        // Relee, no actualiza a ciegas: el pedido está donde lo dijo el servidor.
        assertTrue(vm.secciones.value.porAceptar.isEmpty())
        assertEquals(listOf("p1"), vm.secciones.value.enPreparacion.map { it.orderId })
        assertNull(vm.aviso.value)
    }

    @Test
    fun `Hecho from a second device accept is success and shows no error`() = runTest(dispatcher) {
        // Sólo prueba que el VM trata Hecho como éxito sin aviso. El mapeo 409 ORDER_STATE_CHANGED -> Hecho (OP-D6) lo prueba el
        // repositorio: OnlineOrdersRepositoryTest «409 ... wanted status is Hecho» (A1).
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja(pedido("p1", OnlineOrderStatus.ACCEPTED)))
        coEvery { repo.accept("p1", 10) } returns AccionResultado.Hecho
        val vm = armar()
        vm.aceptar("p1", 10)
        runCurrent()
        assertNull("sin error ni aviso", vm.aviso.value)
        assertEquals(listOf("p1"), vm.secciones.value.enPreparacion.map { it.orderId })
    }

    @Test
    fun `late accept on expired shows ya no disponible`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja(pedido("p1", OnlineOrderStatus.EXPIRED)))
        coEvery { repo.accept("p1", 15) } returns AccionResultado.YaNoDisponible(OnlineOrderStatus.EXPIRED)
        val vm = armar()
        vm.aceptar("p1", 15)
        runCurrent()
        assertEquals(TextosPedidosEnLinea.YA_NO_DISPONIBLE, vm.aviso.value)
        // Y relee: el pedido ya aparece en «Hoy», no en «Por aceptar».
        coVerify(atLeast = 1) { repo.inbox("all", any(), any()) }
        assertEquals(listOf("p1"), vm.secciones.value.hoy.map { it.orderId })
    }

    @Test
    fun `offline action shows neutral retry text and does not queue`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.failure(IOException("sin red"))
        coEvery { repo.accept("p1", 15) } returns AccionResultado.SinConexion
        val vm = armar()
        vm.aceptar("p1", 15)
        runCurrent()
        assertEquals(TextosPedidosEnLinea.REINTENTA, vm.aviso.value)
        assertTrue("el banner neutro de sin conexión", vm.sinConexion.value)
        // Ni se reintenta solo ni se encola: una sola llamada, aunque pase el tiempo.
        advanceTimeBy(60_000); runCurrent()
        coVerify(exactly = 1) { repo.accept("p1", 15) }
    }

    @Test
    fun `tray header only while screen visible`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja())
        val vm = armar()
        runCurrent()
        coVerify(exactly = 0) { repo.inbox(any(), any(), any()) } // no arranca solo: sólo mientras se ve
        val job = launch { vm.mientrasSeVe() }
        runCurrent()
        coVerify(exactly = 1) { repo.inbox("all", true, null) }
        advanceTimeBy(10_000); runCurrent()
        coVerify(exactly = 2) { repo.inbox("all", true, null) } // late cada 10 s con la cabecera
        job.cancel()
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        coVerify(exactly = 2) { repo.inbox(any(), any(), any()) } // cerrada la pantalla, ni una más
    }

    @Test
    fun `no tray header while the desktop window is minimized`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja())
        val ventana = MutableStateFlow(true)
        val vm = armar(ventana = ventana)
        val job = launch { vm.mientrasSeVe() }
        runCurrent()
        coVerify(exactly = 1) { repo.inbox("all", true, null) }
        ventana.value = false // WINDOWS: la ventana se minimiza; el ciclo de vida de Compose sigue en RESUMED
        advanceTimeBy(10_000); runCurrent()
        coVerify(exactly = 1) { repo.inbox("all", false, null) }
        coVerify(exactly = 1) { repo.inbox("all", true, null) } // la cabecera no volvió a mandarse
        ventana.value = true
        advanceTimeBy(10_000); runCurrent()
        coVerify(exactly = 2) { repo.inbox("all", true, null) }
        job.cancel()
    }

    @Test
    fun `buttons hidden without orders update`() = runTest(dispatcher) {
        val permisos = Permisos(actualizar = false)
        val vm = armar(permisos)
        assertFalse(vm.puedeActualizar)
        // Aunque alguien llame a la acción, no llega al servidor.
        vm.aceptar("p1", 15); vm.rechazar("p1", "CLOSED"); vm.marcarListo("p1"); vm.marcarEntregado("p1")
        runCurrent()
        coVerify(exactly = 0) { repo.accept(any(), any()) }
        coVerify(exactly = 0) { repo.reject(any(), any(), any()) }
        coVerify(exactly = 0) { repo.ready(any()) }
        coVerify(exactly = 0) { repo.delivered(any()) }
        permisos.actualizar = true
        assertTrue(vm.puedeActualizar)
    }

    @Test
    fun `cancel requires orders cancel-unpaid`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja())
        coEvery { repo.cancel("a1") } returns AccionResultado.Hecho
        val permisos = Permisos(cancelar = false)
        val vm = armar(permisos)
        assertFalse(vm.puedeCancelar)
        vm.cancelar("a1")
        runCurrent()
        coVerify(exactly = 0) { repo.cancel(any()) }
        permisos.cancelar = true
        vm.cancelar("a1")
        runCurrent()
        coVerify(exactly = 1) { repo.cancel("a1") }
        coVerify(exactly = 1) { repo.inbox("all", any(), any()) } // y relee
    }

    @Test
    fun `reject sends the server reason code and rereads`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja())
        coEvery { repo.reject("p1", "OUT_OF_ITEMS", null) } returns AccionResultado.Hecho
        val vm = armar()
        vm.rechazar("p1", "OUT_OF_ITEMS")
        runCurrent()
        coVerify(exactly = 1) { repo.reject("p1", "OUT_OF_ITEMS", null) }
        coVerify(exactly = 1) { repo.inbox("all", any(), any()) }
    }

    @Test
    fun `ready and delivered reread`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja())
        coEvery { repo.ready("a1") } returns AccionResultado.Hecho
        coEvery { repo.delivered("r1") } returns AccionResultado.Hecho
        val vm = armar()
        vm.marcarListo("a1"); runCurrent()
        vm.marcarEntregado("r1"); runCurrent()
        coVerify(exactly = 1) { repo.ready("a1") }
        coVerify(exactly = 1) { repo.delivered("r1") }
        coVerify(exactly = 2) { repo.inbox("all", any(), any()) }
    }

    @Test
    fun `a second tap while the action is in flight is ignored`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja())
        coEvery { repo.accept("p1", 15) } coAnswers { kotlinx.coroutines.delay(1_000); AccionResultado.Hecho }
        val vm = armar()
        vm.aceptar("p1", 15)
        runCurrent()
        assertTrue(vm.ocupados.value.contains("p1"))
        vm.aceptar("p1", 15) // doble toque
        runCurrent()
        advanceTimeBy(2_000); runCurrent()
        coVerify(exactly = 1) { repo.accept("p1", 15) }
        assertTrue(vm.ocupados.value.isEmpty())
    }

    @Test
    fun `load more appends the next active page by cursor`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), null) } returns Result.success(
            bandeja(pedido("a1", OnlineOrderStatus.ACCEPTED), nextCursor = "c1"),
        )
        coEvery { repo.inbox("active", any(), "c1") } returns Result.success(
            bandeja(pedido("a2", OnlineOrderStatus.ACCEPTED), pedido("r2", OnlineOrderStatus.READY), nextCursor = null),
        )
        val vm = armar()
        vm.refrescar()
        assertTrue(vm.hayMas.value)
        vm.cargarMas()
        runCurrent()
        assertEquals(listOf("a1", "a2"), vm.secciones.value.enPreparacion.map { it.orderId })
        assertEquals(listOf("r2"), vm.secciones.value.listos.map { it.orderId })
        assertFalse(vm.hayMas.value)
        coVerify(exactly = 1) { repo.inbox("active", any(), "c1") }
    }

    @Test
    fun `a refresh keeps the pages already loaded`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), null) } returns Result.success(
            bandeja(pedido("a1", OnlineOrderStatus.ACCEPTED), nextCursor = "c1"),
        )
        coEvery { repo.inbox("active", any(), "c1") } returns Result.success(
            bandeja(pedido("a2", OnlineOrderStatus.ACCEPTED), nextCursor = null),
        )
        val vm = armar()
        vm.refrescar()
        vm.cargarMas(); runCurrent()
        vm.refrescar() // el latido de 10 s no tira lo que ya se cargó
        assertEquals(listOf("a1", "a2"), vm.secciones.value.enPreparacion.map { it.orderId })
    }

    @Test
    fun `truncated shows the last 50 line flag`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja(truncated = true))
        val vm = armar()
        vm.refrescar()
        assertTrue(vm.hoyRecortado.value)
    }

    @Test
    fun `pause goes to the server even without the pause permission so the PIN flow can ask`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja())
        coEvery { repo.pause(30) } returns AccionResultado.Hecho
        coEvery { repo.pause(null) } returns AccionResultado.Hecho
        val vm = armar(Permisos(actualizar = false, cancelar = false))
        vm.pausar(30); runCurrent()
        vm.pausar(null); runCurrent()
        coVerify(exactly = 1) { repo.pause(30) }
        coVerify(exactly = 1) { repo.pause(null) }
    }

    @Test
    fun `a failed pause shows the server message`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja())
        coEvery { repo.pause(15) } returns AccionResultado.Error("No tienes permiso")
        val vm = armar()
        vm.pausar(15); runCurrent()
        assertEquals("No tienes permiso", vm.aviso.value)
    }

    @Test
    fun `accept and reject nudge the global watcher to poll now`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja())
        coEvery { repo.accept("p1", 15) } returns AccionResultado.Hecho
        coEvery { repo.accept("p2", 15) } returns AccionResultado.YaNoDisponible(OnlineOrderStatus.EXPIRED)
        coEvery { repo.reject("p3", "CLOSED", null) } returns AccionResultado.Hecho
        coEvery { repo.ready("a1") } returns AccionResultado.Hecho
        val vm = armar()
        vm.aceptar("p1", 15); runCurrent()
        assertEquals(1, sondeosYa)
        vm.aceptar("p2", 15); runCurrent()
        assertEquals(2, sondeosYa)
        vm.rechazar("p3", "CLOSED"); runCurrent()
        assertEquals(3, sondeosYa)
        vm.marcarListo("a1"); runCurrent()
        assertEquals("Listo no cambia lo que suena", 3, sondeosYa)
    }

    @Test
    fun `empty text only after one good read`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.failure(IllegalStateException("HTTP 500"))
        val vm = armar()
        vm.refrescar()
        assertFalse(vm.leyoAlgunaVez.value)
        assertTrue("fallo que no es de red: aviso neutro", vm.noSePudoLeer.value)
        assertFalse(vm.sinConexion.value)
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja())
        vm.refrescar()
        assertTrue(vm.leyoAlgunaVez.value)
        assertFalse(vm.noSePudoLeer.value)
    }

    @Test
    fun `offline first load shows only the offline banner and no empty text`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.failure(IOException("sin red"))
        val vm = armar()
        vm.refrescar()
        assertTrue(vm.sinConexion.value)
        assertFalse("sin lectura buena no hay texto de vacío", vm.leyoAlgunaVez.value)
        assertFalse("y no es un fallo ajeno a la red", vm.noSePudoLeer.value)
    }

    @Test
    fun `tray header goes only with the visible cycle - action reread and load more send false`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), null) } returns Result.success(
            bandeja(pedido("a1", OnlineOrderStatus.ACCEPTED), nextCursor = "c1"),
        )
        coEvery { repo.inbox("active", any(), "c1") } returns Result.success(bandeja(nextCursor = null))
        coEvery { repo.accept("p1", 15) } returns AccionResultado.Hecho
        val vm = armar() // la ventana se ve (true), pero no corre el ciclo «mientras se ve»
        vm.refrescar()
        vm.aceptar("p1", 15); runCurrent()
        vm.cargarMas(); runCurrent()
        coVerify(exactly = 0) { repo.inbox(any(), true, any()) }
        coVerify(atLeast = 1) { repo.inbox("all", false, null) }
        coVerify(exactly = 1) { repo.inbox("active", false, "c1") }
    }

    @Test
    fun `entry visible without plan while orders exist`() {
        // Con plan: abierta.
        assertEquals(EntradaPedidosEnLinea.ABIERTA, entradaDePedidosEnLinea(puedeLeer = true, tienePlan = true, hayPedidosAbiertos = false))
        // Sin plan y CON pedidos abiertos: se puede atender lo que ya existe (R14).
        assertEquals(EntradaPedidosEnLinea.ABIERTA, entradaDePedidosEnLinea(puedeLeer = true, tienePlan = false, hayPedidosAbiertos = true))
        // Sin plan y sin pedidos: teaser con candado, no desaparece.
        assertEquals(EntradaPedidosEnLinea.TEASER, entradaDePedidosEnLinea(puedeLeer = true, tienePlan = false, hayPedidosAbiertos = false))
        // Sin orders:read no hay nada.
        assertEquals(EntradaPedidosEnLinea.OCULTA, entradaDePedidosEnLinea(puedeLeer = false, tienePlan = true, hayPedidosAbiertos = true))
        assertEquals(EntradaPedidosEnLinea.OCULTA, entradaDePedidosEnLinea(puedeLeer = false, tienePlan = false, hayPedidosAbiertos = false))
    }

    @Test
    fun `screen without plan and without open orders is the teaser`() {
        assertTrue(armar(tienePlan = false, hayAbiertos = false).esTeaser)
        assertFalse(armar(tienePlan = false, hayAbiertos = true).esTeaser)
        assertFalse(armar(tienePlan = true, hayAbiertos = false).esTeaser)
    }

    @Test
    fun `ver opens the tray from any screen - the flag survives until attended`() = runTest {
        val vigilante = VigilanteDePedidosEnLinea(
            consultar = { _, _ -> Result.success(bandeja()) },
            puedeLeer = { true }, tienePlan = { true }, sonar = {}, scope = backgroundScope,
        )
        assertFalse(vigilante.bandejaSolicitada.value)
        vigilante.pedirAbrirBandeja() // el banner «Ver», aunque «Más» todavía no esté compuesta
        assertTrue(vigilante.bandejaSolicitada.value)
        assertTrue(debeAbrirBandeja(solicitada = true, entrada = EntradaPedidosEnLinea.ABIERTA))
        assertTrue("el teaser también abre su pantalla", debeAbrirBandeja(true, EntradaPedidosEnLinea.TEASER))
        assertFalse("sin orders:read no se abre nada, pero la solicitud se atiende", debeAbrirBandeja(true, EntradaPedidosEnLinea.OCULTA))
        assertFalse(debeAbrirBandeja(solicitada = false, entrada = EntradaPedidosEnLinea.ABIERTA))
        vigilante.bandejaAtendida()
        assertFalse(vigilante.bandejaSolicitada.value)
        vigilante.pedirAbrirBandeja() // y se puede pedir otra vez
        assertTrue(vigilante.bandejaSolicitada.value)
    }

    @Test
    fun `money is shown in pesos from exact cents`() {
        assertEquals("\$185.50", formatoPesos(18550))
        assertEquals("\$0.29", formatoPesos(29))
        assertEquals("\$1,234.00", formatoPesos(123400))
    }

    // MARK: - Task A4: impresión

    @Test
    fun `accept success prints the accepted order once`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returnsMany listOf(
            Result.success(bandeja(pedido("p1", OnlineOrderStatus.PENDING_ACCEPTANCE))),
            Result.success(bandeja(pedido("p1", OnlineOrderStatus.ACCEPTED))),
        )
        coEvery { repo.accept("p1", 15) } returns AccionResultado.Hecho
        val vm = armar()
        vm.refrescar()
        assertTrue(impresiones.isEmpty())
        vm.aceptar("p1", 15)
        runCurrent()
        assertEquals(listOf("p1"), impresiones)
    }

    @Test
    fun `a rejected or failed accept prints nothing`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja(pedido("p1", OnlineOrderStatus.PENDING_ACCEPTANCE)))
        coEvery { repo.accept("p1", 10) } returns AccionResultado.SinConexion
        coEvery { repo.reject("p1", "CLOSED", null) } returns AccionResultado.Hecho
        val vm = armar()
        vm.refrescar()
        vm.aceptar("p1", 10)
        runCurrent()
        vm.rechazar("p1", "CLOSED")
        runCurrent()
        assertTrue(impresiones.isEmpty())
    }

    @Test
    fun `each visible poll prints only open orders that need print`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(
            bandeja(
                pedido("a1", OnlineOrderStatus.ACCEPTED, needsPrint = true),
                pedido("a2", OnlineOrderStatus.ACCEPTED, needsPrint = false),
                pedido("r1", OnlineOrderStatus.READY, needsPrint = true),
                pedido("x1", OnlineOrderStatus.REJECTED, needsPrint = true),
            ),
        )
        val vm = armar()
        vm.refrescar()
        runCurrent()
        assertEquals(listOf("a1", "r1"), impresiones)
        vm.refrescar()
        runCurrent()
        // Cada latido lo vuelve a pedir (la clase de impresión es la que no repite al mismo tiempo / el servidor el que ya no lo marca).
        assertEquals(listOf("a1", "r1", "a1", "r1"), impresiones)
    }

    @Test
    fun `reprint goes to the reprint path and tells the result`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja(pedido("a1", OnlineOrderStatus.ACCEPTED)))
        val vm = armar()
        vm.refrescar()
        vm.reimprimir("a1")
        runCurrent()
        assertEquals(listOf("a1"), reimpresiones)
        assertTrue(impresiones.isEmpty())
        assertEquals(TextosPedidosEnLinea.REIMPRESA, vm.aviso.value)
        resultadoReimpresion = ResultadoImpresion.NoSalio
        vm.reimprimir("a1")
        runCurrent()
        assertEquals(TextosPedidosEnLinea.REIMPRESION_FALLO, vm.aviso.value)
    }

    @Test
    fun `reprint needs orders update`() = runTest(dispatcher) {
        coEvery { repo.inbox("all", any(), any()) } returns Result.success(bandeja(pedido("a1", OnlineOrderStatus.ACCEPTED)))
        val vm = armar(Permisos(actualizar = false))
        vm.refrescar()
        vm.reimprimir("a1")
        runCurrent()
        assertTrue(reimpresiones.isEmpty())
    }
}
