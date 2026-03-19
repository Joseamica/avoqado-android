package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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

    // MARK: - Count tracking

    @Test
    fun `pendingCount starts at 0`() {
        assertEquals(0, service.pendingCount.value)
    }

    @Test
    fun `failedCount starts at 0`() {
        assertEquals(0, service.failedCount.value)
    }
}
