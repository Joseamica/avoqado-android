package com.avoqado.pos.tables

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.*
import com.avoqado.pos.tables.presentation.*
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * «Anular producto» en la cuenta de MESA (Flautas, 20-nov): un renglón ya enviado se quita con su motivo, por la ruta del SERVIDOR,
 * como la cortesía: sólo con red (sin red no se encola: lo dice en gris), con la versión que devuelve el servidor y el texto del
 * rechazo tal cual (pagos, último producto). El PIN del gerente lo abre el ForbiddenInterceptor, no esta pantalla.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AnularProductoEnviadoTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val repo = mockk<TableServiceRepository>(relaxed = true)
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val session = TableSession()
    private val enLinea = MutableStateFlow(true)
    private val detalle = OrderDetail("o", total = 100.0, version = 4, items = listOf(
        OrderDetailItem(id = "line", productName = "Agua de pepino", total = 50.0, sentToKitchenAt = "2026-11-20T18:00:00Z"),
        OrderDetailItem(id = "line2", productName = "Flautas", total = 50.0, sentToKitchenAt = "2026-11-20T18:00:00Z")))
    private lateinit var vm: TableOrderViewModel

    @Before fun prepare() {
        every { storage.venueId } returns "a"
        every { storage.giftCardRequestsJson } returns null
        every { repo.tables } returns MutableStateFlow(listOf(DiningTable("t", "1", openOrders = listOf(OpenCheckSummary("o", total = 100.0)))))
        every { repo.isLoading } returns MutableStateFlow(false)
        every { repo.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        every { outbox.acks } returns MutableSharedFlow()
        every { outbox.pendingCount } returns MutableStateFlow(0)
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns com.avoqado.pos.core.data.sync.TrabajoSinResolver(0, 0, emptySet(), 0)
        coEvery { repo.getOrderDetail(any(), any()) } returns Result.success(detalle)
        coEvery { repo.getCustomerLoyalty(any(), any(), any()) } returns Result.failure(IllegalStateException("sin lealtad"))
        session.start(TableSession.Active("t", "1", null, "o", "ORD", 1, 10000, TableSession.Mode.ORDERING))
        val red = mockk<ConnectivityMonitor>(relaxed = true)
        every { red.isConnected } returns enLinea
        val config = mockk<PrintConfigRepository>(relaxed = true)
        every { config.getCurrentConfig() } returns PrintConfig()
        vm = TableOrderViewModel(repo, session, config, mockk(relaxed = true), mockk(relaxed = true), storage, outbox,
            mockk(relaxed = true), red, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun cleanup() { vm.viewModelScope.cancel() }

    @Test fun anulaElRenglonConSuMotivoYGuardaLaVersionDelServidor() = runTest {
        coEvery { repo.voidItem("a", "o", "line", "El cliente cambió de parecer") } returns Result.success(9)
        // La relectura de después no trae versión: la que queda es la que devolvió la anulación.
        coEvery { repo.getOrderDetail(any(), any()) } returnsMany listOf(Result.success(detalle), Result.success(detalle.copy(version = null, items = detalle.items.drop(1))))
        vm.loadCheck(); advanceUntilIdle()

        vm.voidSentItem("line", "El cliente cambió de parecer"); advanceUntilIdle()

        coVerify(exactly = 1) { repo.voidItem("a", "o", "line", "El cliente cambió de parecer") }
        coVerify(exactly = 0) { repo.compItem(any(), any(), any(), any()) }
        assertEquals(9, session.current()?.version)
        assertEquals("Producto anulado — El cliente cambió de parecer", vm.actionMessage.value)
        assertFalse(vm.actionIsError.value)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun sinRedNoSeEncolaYLoDiceEnGris() = runTest {
        vm.loadCheck(); advanceUntilIdle()
        enLinea.value = false

        vm.voidSentItem("line", "Error de entrada"); advanceUntilIdle()

        coVerify(exactly = 0) { repo.voidItem(any(), any(), any(), any()) }
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any()) }
        assertEquals("Sin conexión — anular un producto enviado necesita internet; se habilita al volver la señal", vm.mensajeSinRed.value)
        assertFalse(vm.actionIsError.value)
    }

    @Test fun elRechazoDelServidorSeDiceTalCual() = runTest {
        val cuerpo = """{"success":false,"message":"Es el único producto de la cuenta. Para quitarlo usa «Anular cuenta», o manda primero lo que lo reemplaza y luego anúlalo.","code":"ORDER_ITEM_VOID_LAST_ITEM"}"""
        val http = retrofit2.HttpException(retrofit2.Response.error<Any>(400, cuerpo.toResponseBody("application/json".toMediaTypeOrNull())))
        coEvery { repo.voidItem(any(), any(), any(), any()) } returns Result.failure(http)
        vm.loadCheck(); advanceUntilIdle()

        vm.voidSentItem("line", "Error de entrada"); advanceUntilIdle()

        assertTrue(vm.actionIsError.value)
        assertEquals(ULTIMO_PRODUCTO_SIN_ANULAR, vm.actionMessage.value)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun sinRespuestaDelServidorNoDiceQueNoSeAnulo() = runTest {
        // Codex r1 #9: el aparato creía tener red y la llamada se cortó: no se sabe si el SERVIDOR lo anuló.
        coEvery { repo.voidItem(any(), any(), any(), any()) } returns Result.failure(java.net.SocketTimeoutException("timeout"))
        vm.loadCheck(); advanceUntilIdle()

        vm.voidSentItem("line", "Error de entrada"); advanceUntilIdle()

        assertEquals(ANULAR_SIN_CONFIRMAR, vm.mensajeSinRed.value)
        assertFalse(vm.actionIsError.value)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun anularVaEnGrisConPromocionOElUnicoProducto() {
        val suelto = OrderDetailItem(id = "a", productName = "Agua", total = 35.0)
        val pieza = OrderDetailItem(id = "p", productName = "Agua del combo", total = 33.0, orderPromotionId = "promo-1")
        val dos = OrderDetail("o", items = listOf(suelto, OrderDetailItem(id = "b", productName = "Flautas", total = 65.0)))
        assertNull(motivoParaNoAnular(suelto, dos))
        assertNull(motivoParaNoAnular(suelto, null)) // sin la cuenta leída decide el SERVIDOR
        assertEquals(PROMOCION_SIN_ANULAR, motivoParaNoAnular(pieza, dos))
        assertEquals(ULTIMO_PRODUCTO_SIN_ANULAR, motivoParaNoAnular(suelto, OrderDetail("o", items = listOf(suelto))))
        // La promoción manda aunque sea el único renglón: la salida es la cortesía o anular la cuenta.
        assertEquals(PROMOCION_SIN_ANULAR, motivoParaNoAnular(pieza, OrderDetail("o", items = listOf(pieza))))
        // Codex r2 #8: con un abono (o la cabecera PARTIAL/PAID) va en gris ANTES de tocar, con el texto del SERVIDOR.
        assertEquals(CUENTA_CON_PAGOS_SIN_ANULAR, motivoParaNoAnular(suelto, dos.copy(paymentStatus = "PARTIAL")))
        assertEquals(CUENTA_CON_PAGOS_SIN_ANULAR, motivoParaNoAnular(suelto,
            dos.copy(payments = listOf(OrderDetailPayment(id = "p1", status = "COMPLETED")))))
    }

    @Test fun losMotivosEmpiezanPorLosDeAnular() {
        assertEquals(listOf("Error de entrada", "El cliente cambió de parecer", "El artículo ya no está disponible"),
            MOTIVOS_DE_RENGLON_ENVIADO.take(3))
    }
}
