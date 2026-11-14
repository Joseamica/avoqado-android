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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * D67 (13-nov, prueba final en la D3): con internet «a medias» (la lectura pasa, el envío del cobro se corta) cada corte gastaba
 * un intento del cobro encolado (J: 4 de 10 en 40 s; a los 10 iría a «por revisar») y, al volver la red, la cola esperaba
 * minutos porque nadie la despertaba al vencer su pausa entre intentos (J 00:59 → 01:06; K-C 00:47 → 00:51).
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
        createdAt = System.currentTimeMillis() - 60_000,
        q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(
            venueId = "venue-123", key = "local-pay-1", orderId = null, amountCents = 4500, tipCents = 0, method = "CASH",
            splitType = "FULLPAYMENT",
        )),
    )

    private fun elEnvioSeCorta(error: Exception) {
        val call = mockk<okhttp3.Call>()
        every { client.newCall(any()) } returns call
        every { call.execute() } throws error
    }

    private fun corteNoGasta(error: Exception, retryCount: Int = 0) {
        elEnvioSeCorta(error)
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro(retryCount = retryCount))
        service.start()
        coVerify(timeout = 5_000) { dao.updateStatus("local-pay-1", PaymentSyncStatus.PENDING.name) }
        coVerify(exactly = 0) { dao.incrementRetry(any()) }
        coVerify(exactly = 0) { dao.updateStatusWithError(any(), any(), any()) }
    }

    @Test
    fun `un envio cortado a media peticion NO gasta reintentos`() = runTest {
        corteNoGasta(IOException("unexpected end of stream on http://192.168.100.70:3009/..."))
    }

    @Test
    fun `sin red o conexion rechazada tampoco gastan reintentos`() = runTest {
        corteNoGasta(java.net.ConnectException("Failed to connect to /192.168.100.70:3009"))
    }

    @Test
    fun `un corte con los 10 reintentos ya gastados no manda el efectivo a por revisar`() = runTest {
        corteNoGasta(java.net.UnknownHostException("Unable to resolve host"), retryCount = 10)
    }

    @Test
    fun `al vencer la pausa entre intentos la cola se despierta sola sin otro disparo`() = runTest {
        // 3 intentos ⇒ pausa de 8 s; el último fue hace 7.7 s ⇒ faltan ~300 ms. Nadie más la dispara (sin flanco de red y el
        // reloj periódico es de 15 min): antes se quedaba quieta hasta el siguiente disparo cualquiera.
        coEvery { dao.getPendingPayments(any()) } returns listOf(cobro(retryCount = 3, lastRetryAt = System.currentTimeMillis() - 7_700))
        service.start()
        coVerify(timeout = 5_000) { dao.updateStatus("local-pay-1", "SYNCING") }
    }
}
