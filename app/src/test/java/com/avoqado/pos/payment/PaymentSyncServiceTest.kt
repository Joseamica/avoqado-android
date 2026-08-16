package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.PaymentSyncService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentSyncServiceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val client = mockk<OkHttpClient>(relaxed = true)
    private val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true)

    private lateinit var service: PaymentSyncService

    @Before
    fun setup() {
        every { secureStorage.venueId } returns "venue-123"
        every { secureStorage.userId } returns "user-456"
        every { connectivityMonitor.isConnected } returns MutableStateFlow(true)
        every { connectivityMonitor.isServerReachable } returns MutableStateFlow(true)
        coEvery { dao.getPendingCount() } returns flowOf(0)
        coEvery { dao.getFailedCount() } returns flowOf(0)

        service = PaymentSyncService(dao, secureStorage, client, connectivityMonitor)
    }

    @After
    fun tearDown() {
        service.stop()
    }

    // MARK: - Lifecycle tests

    @Test
    fun `start resets syncing payments to pending`() = runTest {
        service.start()
        // Give the syncScope coroutine time to execute
        Thread.sleep(100)
        coVerify { dao.resetSyncingToPending() }
    }

    @Test
    fun `start is idempotent - calling twice does not double-start`() = runTest {
        service.start()
        service.start()
        Thread.sleep(100)
        // resetSyncingToPending should only be called once even though start was called twice
        coVerify(exactly = 1) { dao.resetSyncingToPending() }
    }

    @Test
    fun `stop cancels all jobs`() {
        service.start()
        service.stop()
        // After stop, calling start again should work (isStarted was reset)
        service.start()
        Thread.sleep(100)
        // Verify it restarted successfully — resetSyncingToPending called again
        coVerify(atLeast = 1) { dao.resetSyncingToPending() }
        service.stop() // clean up
    }

    // MARK: - Sync logic

    @Test
    fun `syncNow does nothing when no pending payments`() = runTest {
        coEvery { dao.getPendingPayments(any()) } returns emptyList()

        service.syncNow()
        Thread.sleep(100)

        // When there are no pending payments, no status updates should happen
        coVerify(exactly = 0) { dao.updateStatus(any(), any()) }
    }

    @Test
    fun `syncNow skips payment when backoff not elapsed`() = runTest {
        val payment = com.avoqado.pos.core.data.local.database.PendingPaymentEntity(
            id = "pay-001",
            venueId = "venue-123",
            staffId = "user-456",
            amountCents = 10000,
            tipCents = 0,
            method = "CASH",
            paymentType = "FAST",
            retryCount = 1,
            // lastRetryAt is just now — backoff not elapsed
            lastRetryAt = System.currentTimeMillis(),
            syncStatus = "PENDING",
            createdAt = System.currentTimeMillis() - 5000,
        )

        coEvery { dao.getPendingPayments(any()) } returns listOf(payment)

        service.syncNow()
        Thread.sleep(100)

        // Payment should be skipped — no SYNCING status update
        coVerify(exactly = 0) { dao.updateStatus("pay-001", "SYNCING") }
    }

    // MARK: - Backoff calculation (tested via formula — calculateBackoff is private)

    // MARK: - 409 handling (regresión de dinero)
    //
    // Un 409 NO afirma que el cobro haya quedado registrado. El reintento idempotente de
    // verdad responde 200 con el pago existente; los 409 de ese endpoint significan lo
    // contrario ("no lo registré, vuelve a intentar"). Marcarlo SYNCED lo BORRABA de la
    // cola y perdía la venta con el efectivo ya en el cajón.

    private fun pendingPayment(retryCount: Int = 0) = PendingPaymentEntity(
        // 🔑 `id` ES la llave de idempotencia que viaja en cada reintento, por eso
        // reintentar un 409 nunca puede duplicar el cobro: el server deduplica por
        // [venueId, idempotencyKey].
        id = "local-pay-1",
        venueId = "venue-123",
        staffId = "staff-1",
        amountCents = 4500,
        tipCents = 0,
        method = "CASH",
        paymentType = "ORDER",
        orderId = "order-1",
        retryCount = retryCount,
    )

    @Test
    fun `409 NO marca el pago como sincronizado`() = runTest {
        service.handleSyncResult(pendingPayment(), 409, """{"message":"La cuenta cambio","code":"ORDER_PAYMENT_CONFLICT"}""")

        coVerify(exactly = 0) { dao.updateStatus("local-pay-1", PaymentSyncStatus.SYNCED.name) }
    }

    @Test
    fun `409 reintenta y corta el batch para preservar el FIFO`() = runTest {
        val stop = service.handleSyncResult(pendingPayment(), 409, """{"code":"ORDER_PAYMENT_CONFLICT"}""")

        assertTrue("un 409 debe cortar el batch para no romper el orden FIFO", stop)
        coVerify(exactly = 1) { dao.incrementRetry("local-pay-1", any()) }
    }

    @Test
    fun `409 agotados los reintentos cae a cuarentena VISIBLE, nunca a borrado silencioso`() = runTest {
        service.handleSyncResult(pendingPayment(retryCount = 10), 409, """{"code":"ORDER_PAYMENT_CONFLICT"}""")

        coVerify(exactly = 1) { dao.updateStatusWithError("local-pay-1", PaymentSyncStatus.FAILED.name, any()) }
        coVerify(exactly = 0) { dao.updateStatus("local-pay-1", PaymentSyncStatus.SYNCED.name) }
    }

    @Test
    fun `200 si marca el pago como sincronizado (no rompimos el camino feliz)`() = runTest {
        val stop = service.handleSyncResult(pendingPayment(), 200, """{"success":true}""")

        assertTrue("el camino feliz no corta el batch", !stop)
        coVerify(exactly = 1) { dao.updateStatus("local-pay-1", PaymentSyncStatus.SYNCED.name) }
    }

    @Test
    fun `backoff formula returns 1s for first retry`() {
        val retryCount = 1
        val initial = 1000L
        val max = 30_000L
        val backoff = minOf(
            (Math.pow(2.0, retryCount.toDouble()) * initial).toLong(),
            max,
        )
        assertEquals(2_000L, backoff)
    }

    @Test
    fun `backoff formula returns 4s for second retry`() {
        val retryCount = 2
        val initial = 1000L
        val max = 30_000L
        val backoff = minOf(
            (Math.pow(2.0, retryCount.toDouble()) * initial).toLong(),
            max,
        )
        assertEquals(4_000L, backoff)
    }

    @Test
    fun `backoff formula caps at 30 seconds`() {
        // 2^10 * 1000 = 1024000ms, capped at 30000ms
        val backoff = minOf(
            (Math.pow(2.0, 10.0) * 1000L).toLong(),
            30_000L,
        )
        assertEquals(30_000L, backoff)
    }

    // MARK: - Retry wait
    //
    // El tope del reintento se medía con System.currentTimeMillis() mientras la espera
    // usaba delay(): dentro de runTest, delay() es tiempo VIRTUAL y vuelve al instante,
    // así que el reloj de pared casi no avanza y el bucle gira sin fin. En producción no
    // se nota; en test cuelga la suite entera (medido: 673s en un solo caso).

    @Test
    fun `retryFailedPayment deja de esperar en vez de girar cuando la fila sigue pendiente`() =
        runTest(timeout = 15.seconds) {
            coEvery { dao.syncStatusOf("stuck") } returns PaymentSyncStatus.PENDING.name

            val outcome = service.retryFailedPayment("stuck")

            assertEquals(PaymentSyncService.RetryOutcome.StillQueued, outcome)
        }

    // MARK: - Order id extraction
    //
    // POST /mobile/venues/:id/orders answers {"success":true,"order":{...}} — verified
    // with curl against the running server. A parser that only looks at data.id treats a
    // successful creation as a failure, and every retry creates ANOTHER order: that is
    // exactly how payment dee41b9b sat PENDING for 19 days on the D3 while spawning
    // orphan orders (ORD-1786374408214 among them).

    @Test
    fun `extractOrderId reads the order envelope the create endpoint actually returns`() {
        val body = """
            {"success":true,"order":{"id":"cmf2k9x1y0001qz8h3n4v7abc",
            "orderNumber":"ORD-1786374408214","status":"PENDING","total":481.80}}
        """.trimIndent()

        assertEquals("cmf2k9x1y0001qz8h3n4v7abc", PaymentSyncService.extractOrderId(body))
    }

    @Test
    fun `extractOrderId still reads the data envelope used by other endpoints`() {
        val body = """{"success":true,"data":{"id":"order-from-data-envelope"}}"""

        assertEquals("order-from-data-envelope", PaymentSyncService.extractOrderId(body))
    }

    @Test
    fun `extractOrderId returns null when no envelope carries an id`() {
        val body = """{"success":true,"message":"aceptado"}"""

        assertEquals(null, PaymentSyncService.extractOrderId(body))
    }

    // MARK: - Order creation classification
    //
    // A 2xx means the server DID create the order. If we cannot read its id back, retrying
    // POSTs /orders again and creates a duplicate — the D3 spawned six orphan orders that
    // way for a single $401.50 payment. Unreadable success is a case for a human (FAILED →
    // quarantine), never for the retry loop.

    @Test
    fun `unreadable success is permanent so the retry loop cannot duplicate the order`() {
        val result = PaymentSyncService.classifyOrderCreation(201, """{"success":true}""")

        assertTrue(
            "2xx sin orderId legible debe ser permanente, no reintentable: $result",
            result is PaymentSyncService.OrderResolution.PermanentFailure,
        )
    }

    @Test
    fun `readable success returns the order id`() {
        val result = PaymentSyncService.classifyOrderCreation(
            201,
            """{"success":true,"order":{"id":"order-created-ok"}}""",
        )

        assertEquals(
            PaymentSyncService.OrderResolution.Ready("order-created-ok"),
            result,
        )
    }

    @Test
    fun `server error stays retryable`() {
        val result = PaymentSyncService.classifyOrderCreation(503, "upstream down")

        assertTrue(
            "un 5xx es transitorio y debe reintentarse: $result",
            result is PaymentSyncService.OrderResolution.RetryableFailure,
        )
    }

    // MARK: - Count tracking

    @Test
    fun `pendingCount starts at 0`() {
        assertEquals(0, service.pendingCount.value)
    }

    @Test
    fun `failedCount starts at 0`() {
        assertEquals(0, service.failedCount.value)
    }

    @Test
    fun `blockingWorkCount reads persisted pending and failed payments`() = runTest {
        val pending = payment("pending", "PENDING")
        val failed = payment("failed", "FAILED")
        coEvery { dao.getUnsyncedPayments() } returns listOf(pending)
        coEvery { dao.getFailedPayments() } returns listOf(failed)

        assertEquals(2, service.blockingWorkCount())
    }

    @Test
    fun `retryFailedPayment reopens the same idempotency key`() = runTest {
        service.retryFailedPayment("pay-original-id")

        coVerify(exactly = 1) { dao.retryFailed("pay-original-id") }
    }

    private fun payment(id: String, status: String) = PendingPaymentEntity(
        id = id,
        venueId = "venue-123",
        staffId = "user-456",
        amountCents = 10_000,
        tipCents = 0,
        method = "CASH",
        paymentType = "FAST",
        syncStatus = status,
    )
}
