package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
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
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * D67 (13-nov, prueba final en la D3): con internet «a medias» (la lectura pasa, el envío del cobro se corta) cada corte gastaba
 * un intento del cobro encolado (J: 4 de 10 en 40 s; a los 10 iría a «por revisar») y, al volver la red, la cola esperaba
 * minutos porque nadie la despertaba al vencer su pausa entre intentos (J 00:59 → 01:06; K-C 00:47 → 00:51).
 * Ahora un corte gasta a lo más un intento cada [PaymentSyncService.VENTANA_DE_CORTES_MS] (Codex r1 #1: sin tope, un cobro que
 * SIEMPRE se corta trabaría la cola), y cada fallo que deja una pausa agenda su despertar (r1 #3) con la misma sesión (r1 #4).
 */
class PaymentSyncServiceInternetAMediasTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val client = mockk<OkHttpClient>(relaxed = true)
    private val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true)
    private val cajon = mockk<CashDrawerRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private lateinit var service: PaymentSyncService
    private val ahora get() = System.currentTimeMillis()

    @Before
    fun setup() {
        every { secureStorage.venueId } returns "venue-123"
        every { secureStorage.userId } returns "user-456"
        every { connectivityMonitor.isConnected } returns MutableStateFlow(true)
        every { connectivityMonitor.isServerReachable } returns MutableStateFlow(true)
        coEvery { dao.getPendingCount() } returns flowOf(0)
        coEvery { dao.getFailedCount() } returns flowOf(0)
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        service = PaymentSyncService(dao, secureStorage, client, connectivityMonitor, cajon, outbox, mockk(relaxed = true))
    }

    @After
    fun tearDown() = service.stop()

    /** Un cobro rápido en efectivo con su prueba de captura (Q15): sin ella la cola lo retiene antes de llegar a la red. */
    private fun cobro(retryCount: Int = 0, lastRetryAt: Long? = null) = PendingPaymentEntity(
        id = "local-pay-1", venueId = "venue-123", staffId = "staff-1", amountCents = 4500, tipCents = 0, method = "CASH",
        paymentType = "FAST", retryCount = retryCount, lastRetryAt = lastRetryAt, syncStatus = "PENDING",
        createdAt = System.currentTimeMillis() - 600_000,
        q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(
            venueId = "venue-123", key = "local-pay-1", orderId = null, amountCents = 4500, tipCents = 0, method = "CASH",
            splitType = "FULLPAYMENT",
        )),
    )

    private fun laRedContesta(responder: () -> okhttp3.Response): okhttp3.Call {
        val call = mockk<okhttp3.Call>()
        every { client.newCall(any()) } returns call
        every { call.execute() } answers { responder() }
        return call
    }

    private fun corteDentroDeLaVentana(error: Exception, retryCount: Int) {
        laRedContesta { throw error }
        // El último intento contado fue hace 40 s: su pausa (≤ 30 s) ya venció, pero la ventana de cortes (3 min) no.
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro(retryCount = retryCount, lastRetryAt = ahora - 40_000))
        service.start()
        coVerify(timeout = 5_000) { dao.updateStatus("local-pay-1", PaymentSyncStatus.PENDING.name) }
        coVerify(exactly = 0) { dao.incrementRetry(any(), any()) }
        coVerify(exactly = 0) { dao.updateStatusWithError(any(), any(), any()) }
    }

    @Test
    fun `un envio cortado a media peticion dentro de la ventana NO gasta otro intento`() = runTest {
        corteDentroDeLaVentana(IOException("unexpected end of stream on http://192.168.100.44:3010/..."), retryCount = 3)
    }

    @Test
    fun `una conexion rechazada dentro de la ventana tampoco gasta otro intento`() = runTest {
        corteDentroDeLaVentana(java.net.ConnectException("Failed to connect to /192.168.100.44:3010"), retryCount = 3)
    }

    @Test
    fun `con los 10 gastados, un corte dentro de la ventana no manda el efectivo a por revisar`() = runTest {
        corteDentroDeLaVentana(java.net.UnknownHostException("Unable to resolve host"), retryCount = 10)
    }

    @Test
    fun `el primer corte si cuenta un intento, para que arranque la ventana`() = runTest {
        laRedContesta { throw IOException("unexpected end of stream") }
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro())
        service.start()
        coVerify(timeout = 5_000) { dao.incrementRetry("local-pay-1", any()) }
        coVerify(exactly = 0) { dao.updateStatusWithError(any(), any(), any()) }
    }

    @Test
    fun `un cobro que SIEMPRE se corta acaba en por revisar y suelta la cola`() = runTest {
        // Codex r1 #1: el último intento contado fue hace 4 min (fuera de la ventana) y ya lleva los 10.
        laRedContesta { throw IOException("unexpected end of stream") }
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro(retryCount = 10, lastRetryAt = ahora - 4 * 60_000))
        service.start()
        coVerify(timeout = 5_000) { dao.updateStatusWithError("local-pay-1", PaymentSyncStatus.FAILED.name, any()) }
    }

    @Test
    fun `al vencer la pausa entre intentos la cola se despierta sola sin otro disparo`() = runTest {
        // 3 intentos ⇒ pausa de 8 s; el último fue hace 7.7 s ⇒ faltan ~300 ms. Nadie más la dispara (sin flanco de red y el
        // reloj periódico es de 15 min): antes se quedaba quieta hasta el siguiente disparo cualquiera.
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro(retryCount = 3, lastRetryAt = ahora - 7_700))
        service.start()
        coVerify(timeout = 5_000) { dao.updateStatus("local-pay-1", "SYNCING") }
    }

    @Test
    fun `un 503 agenda su propio despertar y se reintenta sin otro disparo`() = runTest {
        // Codex r1 #3: el fallo que CREA la pausa (2 s tras el primer intento) programa el despertar.
        val call = laRedContesta {
            okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("http://avoqado.test/").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(503).message("Service Unavailable")
                .body("".toResponseBody()).build()
        }
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro())
        service.start()
        verify(timeout = 6_000, atLeast = 2) { call.execute() }
    }

    @Test
    fun `si la persona cambia mientras viaja la peticion, el corte no agenda un despertar con la sesion nueva`() = runTest {
        // Codex r2 #1: sale con la sesión A; a media petición entra B (cierre automático sin stop) y luego se corta.
        // 1 intento contado hace 40 s: dentro de la ventana, no cuenta otro y su pausa es de 2 s.
        val call = laRedContesta {
            every { secureStorage.userId } returns "otra-persona"
            throw IOException("unexpected end of stream")
        }
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro(retryCount = 1, lastRetryAt = ahora - 40_000))
        service.start()
        coVerify(timeout = 5_000) { dao.updateStatus("local-pay-1", PaymentSyncStatus.PENDING.name) }
        Thread.sleep(3_000)
        verify(exactly = 1) { call.execute() }
    }

    @Test
    fun `cuando un cobro se va a por revisar el siguiente sale sin esperar otro disparo`() = runTest {
        // Codex r2 #2: A agota sus intentos (fuera de la ventana) y pasa a FAILED; B, detrás en la fila, debe salir solo.
        val b = cobro().copy(id = "local-pay-B", q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(
            venueId = "venue-123", key = "local-pay-B", orderId = null, amountCents = 4500, tipCents = 0, method = "CASH",
            splitType = "FULLPAYMENT",
        )))
        val fila = mutableListOf(cobro(retryCount = 10, lastRetryAt = ahora - 4 * 60_000), b)
        coEvery { dao.getPendingPayments(any()) } answers { fila.toList() }
        coEvery { dao.updateStatusWithError("local-pay-1", PaymentSyncStatus.FAILED.name, any()) } answers {
            fila.removeAll { it.id == "local-pay-1" }
        }
        laRedContesta { throw IOException("unexpected end of stream") }
        service.start()
        coVerify(timeout = 5_000) { dao.updateStatusWithError("local-pay-1", PaymentSyncStatus.FAILED.name, any()) }
        coVerify(timeout = 5_000) { dao.updateStatus("local-pay-B", "SYNCING") }
    }

    @Test
    fun `el envio sale sellado con la sesion que empezo la pasada aunque a media pasada entre otra persona`() = runTest {
        // Codex r3 #1: la pasada arranca con la generación 7; mientras lee la cola entra otra persona (8). El POST lleva el sello
        // 7, así que AuthInterceptor no lo deja salir con la sesión 8 (SesionCambiada).
        every { secureStorage.generacion } returns 7L
        val pedidas = mutableListOf<okhttp3.Request>()
        val call = mockk<okhttp3.Call>()
        every { client.newCall(capture(pedidas)) } returns call
        every { call.execute() } throws IOException("unexpected end of stream")
        coEvery { dao.getPendingPayments(any()) } answers {
            every { secureStorage.generacion } returns 8L
            listOf(cobro())
        }
        service.start()
        coVerify(timeout = 5_000) { dao.incrementRetry("local-pay-1", any()) }
        org.junit.Assert.assertEquals(7L, with(com.avoqado.pos.core.data.network.SelloDePeticion::class.java) { pedidas.first().tag(this)?.generacion })
    }

    @Test
    fun `un envio que no salio porque cambio la sesion queda pendiente sin gastar intentos`() = runTest {
        laRedContesta { throw com.avoqado.pos.core.data.network.SesionCambiada() }
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro())
        service.start()
        coVerify(timeout = 5_000) { dao.updateStatus("local-pay-1", PaymentSyncStatus.PENDING.name) }
        coVerify(exactly = 0) { dao.incrementRetry(any(), any()) }
        coVerify(exactly = 0) { dao.updateStatusWithError(any(), any(), any()) }
    }

    @Test
    fun `el despertar no sube nada si cambio la persona de la sesion`() = runTest {
        // Codex r1 #4: la pausa vence ~300 ms después, pero para entonces entró otra persona sin pasar por stop().
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro(retryCount = 3, lastRetryAt = ahora - 7_700))
        service.start()
        coVerify(timeout = 5_000) { dao.getPendingPayments(any()) }
        every { secureStorage.userId } returns "otra-persona"
        Thread.sleep(1_500)
        coVerify(exactly = 0) { dao.updateStatus("local-pay-1", "SYNCING") }
    }
}
