package com.avoqado.pos.payment

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.FakeCashDrawerDao
import com.avoqado.pos.cashdrawer.sesionLocal
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.filaAlReemplazar
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.SalidaDelApartado
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.domain.OrigenDelCobro
import com.avoqado.pos.payment.domain.ManualPaymentChoice
import com.avoqado.pos.payment.domain.TenderTypeOption
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Cruce de develop/Rappi con el único escritor del candidato. Repositorios, conciliación, salida y HTTP reales;
 * DAOs en memoria: la atomicidad Room conserva sus pruebas propias acreditadas. */
@OptIn(ExperimentalCoroutinesApi::class)
class RetenidoEnElApartadoDevelopTest {
    @get:Rule val main = MainDispatcherRule()

    private class Fixture : AutoCloseable {
        val filas = linkedMapOf<String, PendingPaymentEntity>()
        val pagos = mockk<PendingPaymentDao>(relaxed = true)
        val storage = mockk<SecureStorage>(relaxed = true)
        val cajon = FakeCashDrawerDao().apply { sessions[CAJA] = sesionLocal(CAJA, openedAt = 1L) }
        val drawer = mockk<CashDrawerRepository>(relaxed = true)
        val llegada = CompletableDeferred<Unit>()
        val responder = CountDownLatch(1)
        val server = MockWebServer()
        val client: OkHttpClient
        val salida: SalidaDelApartado
        val cash: CashPaymentRepository
        val order: OrderRepository
        var fallarEscritura = false
        var body = RESPUESTA_RAPPI
        private val modelos = mutableListOf<PaymentFlowViewModel>()

        init {
            every { storage.venueId } returns VENUE
            every { storage.userId } returns "staff-1"
            every { storage.userFirstName } returns "Ana"
            every { storage.userLastName } returns "Prueba"
            every { storage.venueName } returns "FULLTEST-RETENIDO"
            every { pagos.getPendingCount() } returns flowOf(0)
            every { pagos.getFailedCount() } returns flowOf(0)
            every { pagos.observarEnVuelo() } returns flowOf(emptyList())
            every { pagos.observarEstado(any()) } answers { flowOf(filas[firstArg<String>()]?.syncStatus) }
            coEvery { pagos.findForVenue(any(), any()) } answers {
                filas[secondArg<String>()]?.takeIf { it.venueId == firstArg<String>() }
            }
            coEvery { pagos.findByIdQ15(any()) } answers { filas[firstArg<String>()] }
            coEvery { pagos.insert(any()) } answers {
                val fila = firstArg<PendingPaymentEntity>()
                filas[fila.id] = filaAlReemplazar(filas[fila.id], fila)
            }
            coEvery { pagos.actualizarApartadoEnVuelo(any(), any(), any()) } answers {
                val fila = filas[firstArg<String>()]?.takeIf { it.syncStatus == "EN_VUELO" }
                if (fila == null) 0 else {
                    filas[fila.id] = fila.copy(amountCents = secondArg(), orderId = thirdArg()); 1
                }
            }
            coEvery { pagos.registrarResultadoQ15(any(), any(), any(), any(), any(), any()) } answers {
                if (fallarEscritura) throw IOException("Fallo propio al guardar el 2xx")
                val fila = filas[secondArg<String>()]!!
                filas[fila.id] = fila.copy(
                    syncStatus = if (arg<Boolean>(2)) "SYNCED" else "PENDING",
                    q15Reason = arg(3), q15ResultJson = arg(4),
                    q15InitialResultJson = fila.q15InitialResultJson ?: arg(4),
                )
                1
            }
            coEvery { pagos.enVueloAPendiente(any()) } answers {
                val fila = filas[firstArg<String>()]?.takeIf { it.syncStatus == "EN_VUELO" && it.q15Reason == null }
                if (fila == null) 0 else { filas[fila.id] = fila.copy(syncStatus = "PENDING"); 1 }
            }
            coEvery { pagos.borrarSiEnVuelo(any()) } returns 0
            coEvery { drawer.getOpenSession() } returns cajon.sessions[CAJA]
            every { drawer.cajasQueEsperanSuApertura() } returns emptySet()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    llegada.complete(Unit)
                    check(responder.await(5, TimeUnit.SECONDS)) { "La prueba no liberó su respuesta HTTP" }
                    return MockResponse().setResponseCode(200).setBody(body)
                }
            }
            server.start()
            client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            salida = SalidaDelApartado(pagos, cajon, storage)
            cash = CashPaymentRepository(storage, pagos, salida)
            order = OrderRepository(storage, client, pagos, salida)
        }

        suspend fun reservar(llave: String) {
            cash.reservarCobro(
                CreateOrderRequest(emptyList(), subtotal = RETENIDO, total = RETENIDO, paymentMethod = "CASH"),
                "staff-1", 50_000, 29_500, null, orderId = ORDEN, idempotencyKey = llave,
                venueId = VENUE, cajaDelCobro = CAJA,
            )
        }

        fun vm(): PaymentFlowViewModel {
            val terminal = mockk<TerminalPaymentService>(relaxed = true)
            every { terminal.intentoEnVuelo() } returns null
            every { terminal.contextoDe(any()) } returns null
            every { terminal.pendienteDeLaVenta(any()) } returns null
            coEvery { terminal.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
            val settings = mockk<TpvSettingsRepository>()
            every { settings.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
            val area = mockk<AreaTicketRepository>(relaxed = true)
            every { area.session.current() } returns null
            return PaymentFlowViewModel(
                tableSession = com.avoqado.pos.tables.data.TableSession(), syncOutbox = mockk(relaxed = true),
                orderRepository = order, cashPaymentRepository = cash, tenderTypeRepository = mockk(relaxed = true),
                terminalPaymentService = terminal, tpvSettingsRepository = settings, paymentSyncService = mockk(relaxed = true),
                cashDrawerRepository = drawer, kdsRepository = mockk(relaxed = true), kdsOrderBus = mockk(relaxed = true),
                printerService = mockk(relaxed = true), secureStorage = storage, comandaDispatcher = mockk(relaxed = true),
                comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()), replayDeComandas = mockk(relaxed = true),
                customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(), areaTicketRepository = area,
                cancelacionDeCobro = mockk(relaxed = true), savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ).also { modelos += it }
        }

        suspend fun esperarLlegada() = withContext(Dispatchers.Default) { withTimeout(5_000) { llegada.await() } }
        suspend fun esperarCobro(trabajo: Job) = withContext(Dispatchers.Default) { withTimeout(5_000) { trabajo.join() } }
        override fun close() {
            responder.countDown()
            modelos.forEach { it.viewModelScope.cancel() }
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun `Rappi confirmado anota una sola venta por todo el retenido`() = runTest {
        Fixture().use { f ->
            val llave = "retencion-confirmada"
            f.reservar(llave)
            val captura = f.filas.getValue(llave).q15CaptureJson
            f.responder.countDown()
            val resultado = f.order.recordCashPayment(ORDEN, RETENIDO, "staff-1", idempotencyKey = llave, capturedVenueId = VENUE, capturaPrevia = true).getOrThrow()
            assertEquals(RETENIDO, resultado.cashRetainedCents)
            assertEquals("SYNCED", f.filas.getValue(llave).syncStatus)
            assertEquals(captura, f.filas.getValue(llave).q15CaptureJson)
            f.cash.soltarReserva(llave)
            assertEquals(1, f.cajon.events.size)
            assertEquals("venta175 + propina20 + tercero10", RETENIDO, f.cajon.events.values.single().amountCents)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun `Rappi 2xx con fallo local conserva todo el retenido en su respaldo`() = runTest {
        Fixture().use { f ->
            val llave = "retencion-respaldo"
            f.reservar(llave)
            f.fallarEscritura = true
            f.responder.countDown()
            val error = f.order.recordCashPayment(ORDEN, RETENIDO, "staff-1", idempotencyKey = llave, capturedVenueId = VENUE, capturaPrevia = true)
                .exceptionOrNull() as? OrderRepository.CobroRegistradoSinGuardar
            assertNotNull("2xx comprobado: cobrado, no cancelable", error)
            f.fallarEscritura = false
            assertTrue(f.cash.darPorCobrado(llave, error!!.importeCobradoCents))
            assertEquals("PENDING", f.filas.getValue(llave).syncStatus)
            assertEquals(1, f.cajon.events.size)
            assertEquals("el respaldo no pierde el dinero de terceros", RETENIDO, f.cajon.events.values.single().amountCents)
            assertEquals(RETENIDO, error.importeCobradoCents)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun `Rappi con apartado tardio conserva retenido y no toca la venta nueva`() = runTest {
        Fixture().use { f ->
            val vm = f.vm()
            vm.startPaymentFlow(carrito(RETENIDO), resumeOrderId = ORDEN, origen = OrigenDelCobro.PEDIDO_DE_PLATAFORMA_EFECTIVO)
            val previos = vm.viewModelScope.coroutineContext.job.children.toSet()
            vm.confirmCashPreset(50_000)
            f.esperarLlegada()
            val cobro = vm.viewModelScope.coroutineContext.job.children.single { it !in previos }
            vm.startPaymentFlow(carrito(8_000))
            val nueva = vm.state.value
            f.responder.countDown()
            f.esperarCobro(cobro)
            assertEquals(nueva, vm.state.value)
            assertEquals(1, f.cajon.events.size)
            assertEquals("A queda completa en SU cajon aunque B este en pantalla", RETENIDO, f.cajon.events.values.single().amountCents)
            coVerify(exactly = 0) { f.drawer.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) }
        }
    }

    @Test fun `efectivo de catalogo sin apartado puro tardio conserva todo el retenido`() = runTest {
        Fixture().use { f ->
            val vm = f.vm()
            val anotado = CompletableDeferred<Int>()
            coEvery { f.drawer.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } answers {
                anotado.complete(firstArg()); null
            }
            vm.startPaymentFlow(carrito(RETENIDO), resumeOrderId = ORDEN, origen = OrigenDelCobro.PEDIDO_EN_LINEA)
            val previos = vm.viewModelScope.coroutineContext.job.children.toSet()
            vm.confirmManualChoice(ManualPaymentChoice.Tender(TenderTypeOption(
                "cash-system", 1, "Efectivo", true, "CASH", false, "PRIMARY", 0, opensCashDrawer = true,
            )))
            f.esperarLlegada()
            val cobro = vm.viewModelScope.coroutineContext.job.children.single { it !in previos }
            assertEquals("este tipo no es un apartado de efectivo puro", "cash-system", f.filas.values.single().tenderTypeId)
            vm.startPaymentFlow(carrito(8_000))
            val nueva = vm.state.value
            f.responder.countDown()
            val importe = withContext(Dispatchers.Default) { withTimeout(5_000) { anotado.await() } }
            f.esperarCobro(cobro)
            assertEquals(nueva, vm.state.value)
            assertEquals("sin apartado el escritor legado tambien conserva terceros", RETENIDO, importe)
            assertTrue("la salida del apartado no anota este tipo: no hay dos escritores", f.cajon.events.isEmpty())
            coVerify(exactly = 1) { f.drawer.addCashSale(any(), ORDEN, VENUE, "staff-1", any(), llaveDelCobro = any()) }
        }
    }

    @Test fun `sin retenido valido conserva la suma y el fallback anteriores`() {
        assertEquals(19_500, OrderRepository.importeCobradoEnEfectivo(17_500, 2_000, RETENIDO))
        assertEquals(19_500, OrderRepository.importeCobradoEnEfectivo(17_500, 2_000, RETENIDO, cashRetainedCents = null))
        assertEquals(19_500, OrderRepository.importeCobradoEnEfectivo(17_500, 2_000, RETENIDO, cashRetainedCents = -1))
        assertEquals(RETENIDO, OrderRepository.importeCobradoEnEfectivo(null, 2_000, RETENIDO, cashRetainedCents = -1))
        assertEquals(RETENIDO, OrderRepository.importeCobradoEnEfectivo(17_500, -1, RETENIDO, cashRetainedCents = null))
        assertEquals(RETENIDO, OrderRepository.importeCobradoEnEfectivo(Int.MAX_VALUE, Int.MAX_VALUE, RETENIDO, cashRetainedCents = -1))
    }

    @Test fun `retenido cero o positivo manda tambien sin desglose util`() {
        assertEquals(0, OrderRepository.importeCobradoEnEfectivo(17_500, 2_000, RETENIDO, cashRetainedCents = 0))
        assertEquals(RETENIDO, OrderRepository.importeCobradoEnEfectivo(null, null, 50_000, cashRetainedCents = RETENIDO))
        assertEquals(RETENIDO, OrderRepository.importeCobradoEnEfectivo(Int.MAX_VALUE, Int.MAX_VALUE, 50_000, cashRetainedCents = RETENIDO))
    }

    private companion object {
        const val VENUE = "venue-1"
        const val CAJA = "caja-retencion"
        const val ORDEN = "rappi-1"
        const val RETENIDO = 20_500
        const val RESPUESTA_RAPPI = """{"success":true,"payment":{"paymentId":"pay-rappi","orderId":"rappi-1","amount":17500,"tipAmount":2000,"cashRetainedCents":20500,"deliveryCashAllocation":{"saleCents":17500,"tipCents":2000,"thirdPartyCents":1000},"method":"CASH","status":"COMPLETED","splitType":"FULLPAYMENT","remainingBalanceCents":0,"orderPaymentStatus":"PAID"}}"""

        fun carrito(cents: Int) = CartState(items = listOf(CartItem(id = "saldo-retencion", type = CartItemType.CustomAmount, name = "Pedido", unitPrice = cents)))
    }
}
