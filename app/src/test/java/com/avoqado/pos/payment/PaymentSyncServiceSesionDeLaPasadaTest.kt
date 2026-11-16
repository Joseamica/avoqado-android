package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.network.PasadaConSello
import com.avoqado.pos.core.data.network.sello
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.PaymentSyncService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * D80 (hz:8024bc1c:D80, lo destaparon las rondas r2–r4 de Codex del D67): una pasada de la cola sin red empieza con la sesión de A
 * y, durante una espera, el cierre automático por renovación rechazada saca a A y entra B, sin `stop()` del servicio. El D67 ya
 * sellaba los dos POST de dinero de la cola de cobros; lo demás que esa pasada enviaba después salía con las credenciales de B: la
 * consulta por llave (Q15), la recuperación (Q16), el replay del cajón y el outbox de Mesas. Y la pasada de B no arrancaba hasta el
 * reloj de 15 min, porque `start()` regresaba sin hacer nada.
 */
class PaymentSyncServiceSesionDeLaPasadaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val client = mockk<OkHttpClient>(relaxed = true)
    private val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true)
    private val cajon = mockk<CashDrawerRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private lateinit var service: PaymentSyncService
    private val pedidas = CopyOnWriteArrayList<Request>()

    @Before
    fun setup() {
        every { secureStorage.venueId } returns VENUE
        every { secureStorage.userId } returns "cajera-A"
        every { secureStorage.generacion } returns 7L
        every { connectivityMonitor.isConnected } returns MutableStateFlow(true)
        every { connectivityMonitor.isServerReachable } returns MutableStateFlow(true)
        coEvery { dao.getPendingCount() } returns flowOf(0)
        coEvery { dao.getFailedCount() } returns flowOf(0)
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        val call = mockk<okhttp3.Call>()
        every { client.newCall(any()) } answers { pedidas += firstArg<Request>(); call }
        every { call.execute() } throws IOException("sin red")
        service = PaymentSyncService(dao, secureStorage, client, connectivityMonitor, cajon, outbox, mockk(relaxed = true))
    }

    @After
    fun tearDown() = service.stop()

    /** Entra B (generación 8) en el momento de la llamada: el cierre automático por renovación rechazada, sin `stop()`. */
    private fun entraOtraPersona() {
        every { secureStorage.generacion } returns 8L
        every { secureStorage.userId } returns "cajera-B"
    }

    /** Un efectivo de mostrador SIN RED retenido esperando su precio (Q16): la pasada pide `/sync/recovery`. */
    private fun retenidoQ16(): PendingPaymentEntity {
        val pedido = """{"externalId":"ext-d80"}"""
        return PendingPaymentEntity(
            id = "pago-q16", venueId = VENUE, staffId = "cajera-A", amountCents = 4500, tipCents = 0, method = "CASH",
            paymentType = "ORDER", orderId = null, orderRequestJson = pedido, syncStatus = "PENDING",
            createdAt = System.currentTimeMillis() - 600_000, q15Reason = ConciliacionDelCobro.PRICE_PROOF_PENDING,
            q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(
                venueId = VENUE, key = "pago-q16", orderId = null, amountCents = 4500, tipCents = 0, method = "CASH",
                splitType = "FULLPAYMENT", orderRequestJson = pedido,
            )),
        )
    }

    /** Un efectivo rápido cuyo resultado no se comprobó (Q15): la pasada pregunta por su llave. */
    private fun sinComprobarQ15() = PendingPaymentEntity(
        id = "pago-q15", venueId = VENUE, staffId = "cajera-A", amountCents = 4500, tipCents = 0, method = "CASH",
        paymentType = "FAST", syncStatus = "PENDING", createdAt = System.currentTimeMillis() - 600_000,
        q15Reason = ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,
        q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(
            venueId = VENUE, key = "pago-q15", orderId = null, amountCents = 4500, tipCents = 0, method = "CASH",
            splitType = "FULLPAYMENT",
        )),
    )

    @Test
    fun `P1 la recuperacion Q16 sale sellada con la sesion que empezo la pasada`() = runTest {
        coEvery { dao.getPendingPayments(any()) } answers { entraOtraPersona(); listOf(retenidoQ16()) }

        service.start()

        esperarA("la recuperación") { pedidas.any { it.url.encodedPath.endsWith("/sync/recovery") } }
        val recuperacion = pedidas.first { it.url.encodedPath.endsWith("/sync/recovery") }
        assertEquals(7L, recuperacion.sello)
    }

    @Test
    fun `P1 la consulta por llave Q15 sale sellada con la sesion de la pasada`() = runTest {
        val fila = sinComprobarQ15()
        coEvery { dao.getPendingPayments(any()) } returns listOf(fila)
        coEvery { dao.findForVenue(VENUE, fila.id) } returns fila

        service.start()

        esperarA("la consulta por llave") { pedidas.any { it.url.encodedPath.contains("/payments/by-key/") } }
        assertEquals(7L, pedidas.first { it.url.encodedPath.contains("/payments/by-key/") }.sello)
    }

    @Test
    fun `P1 si la sesion cambia a media pasada la consulta por llave Q15 no sale con la nueva`() = runTest {
        val fila = sinComprobarQ15()
        coEvery { dao.getPendingPayments(any()) } answers { listOf(fila) }
        // Tras el cierre automático vuelve a entrar la MISMA cajera: es otra sesión (8), aunque la persona y la sucursal sean
        // las mismas. Pasa en la última lectura antes de la consulta.
        coEvery { dao.findForVenue(VENUE, fila.id) } answers { every { secureStorage.generacion } returns 8L; fila }

        service.start()
        coVerify(timeout = 5_000) { dao.findForVenue(VENUE, fila.id) }
        service.esperarSincronizacionActualQ16()

        val porLlave = pedidas.filter { it.url.encodedPath.contains("/payments/by-key/") }
        assertTrue("Ninguna consulta por llave sale sin el sello de la pasada: ${porLlave.map { it.sello }}", porLlave.all { it.sello == 7L })
        coVerify(exactly = 0) { dao.recuperarResultadoPorLlaveQ15(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 el cajon y el outbox de Mesas corren con el sello de la pasada`() = runTest {
        val delCajon = CopyOnWriteArrayList<Long?>()
        val delOutbox = CopyOnWriteArrayList<Long?>()
        coEvery { cajon.sincronizarCajonPrimero() } coAnswers {
            delCajon += kotlin.coroutines.coroutineContext[PasadaConSello]?.generacion ?: -1L
            entraOtraPersona() // a media pasada, mientras el cajón espera a la red
            true
        }
        coEvery { outbox.replayNow(VENUE) } coAnswers {
            delOutbox += kotlin.coroutines.coroutineContext[PasadaConSello]?.generacion ?: -1L
            0
        }
        coEvery { dao.getPendingPayments(any()) } returns emptyList()

        service.start()

        esperarA("el outbox") { delOutbox.isNotEmpty() }
        assertEquals(7L, delCajon.first())
        assertEquals(7L, delOutbox.first())
    }

    @Test
    fun `P1 si entra otra persona a media pasada al terminar arranca la pasada de quien entro`() = runTest {
        val suelta = CompletableDeferred<Unit>()
        val lecturas = CopyOnWriteArrayList<Long>()
        coEvery { dao.getPendingPayments(any()) } coAnswers {
            lecturas += secureStorage.generacion
            if (lecturas.size == 1) suelta.await() // la pasada de A espera a la red
            emptyList()
        }
        service.start()
        esperarA("la pasada de A") { lecturas.isNotEmpty() }

        // Cierre automático: sale A, entra B y `AppState.onLoginSuccess` vuelve a llamar `start()` (el servicio nunca se detuvo).
        entraOtraPersona()
        service.start()
        suelta.complete(Unit)

        esperarA("la pasada de B") { lecturas.size >= 2 }
        coVerify(exactly = 2) { dao.getPendingPayments(any()) }
        assertEquals("La segunda pasada es la de B", listOf(7L, 8L), lecturas.toList())
    }

    /**
     * D6 (15-nov): MockK da la llamada por hecha (`coVerify(timeout)`) antes de que su respuesta termine de anotarla en la lista,
     * que corre en el hilo del servicio. Leer la lista justo después tronaba a veces (1 de 8,911 en la suite). Se espera a lo
     * anotado, no al mock.
     */
    private fun esperarA(que: String, listo: () -> Boolean) {
        val hasta = System.nanoTime() + 5_000_000_000L
        while (!listo()) {
            check(System.nanoTime() < hasta) { "No llegó a tiempo: $que" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val VENUE = "venue-d80"
    }
}
