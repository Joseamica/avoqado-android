package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CashPaymentRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val dao = mockk<PendingPaymentDao>(relaxed = true)

    private lateinit var repository: CashPaymentRepository

    @Before
    fun setup() {
        every { secureStorage.venueId } returns "venue-123"
        every { secureStorage.userId } returns "user-456"
        coEvery { dao.getPendingCount() } returns flowOf(0)
        coEvery { dao.getFailedCount() } returns flowOf(0)
        repository = CashPaymentRepository(secureStorage, dao)
    }

    // MARK: - processCashPayment tests

    @Test
    fun `processCashPayment returns Success when enough cash`() {
        val result = repository.processCashPayment(
            totalCents = 10000,
            cashReceivedCents = 15000,
        )
        assertTrue(result is CashPaymentResult.Success)
    }

    @Test
    fun `processCashPayment returns Success with correct change`() {
        val result = repository.processCashPayment(
            totalCents = 10000,
            cashReceivedCents = 15000,
        ) as CashPaymentResult.Success
        assertEquals(5000, result.changeCents)
    }

    @Test
    fun `processCashPayment returns InsufficientFunds when not enough`() {
        val result = repository.processCashPayment(
            totalCents = 10000,
            cashReceivedCents = 5000,
        )
        assertTrue(result is CashPaymentResult.InsufficientFunds)
        val insufficient = result as CashPaymentResult.InsufficientFunds
        assertEquals(5000, insufficient.shortfall)
    }

    @Test
    fun `processCashPayment returns exact change for exact amount`() {
        val result = repository.processCashPayment(
            totalCents = 10000,
            cashReceivedCents = 10000,
        ) as CashPaymentResult.Success
        assertEquals(0, result.changeCents)
    }

    // MARK: - queueCashPayment tests

    @Test
    fun `queueCashPayment inserts entity into DAO`() = runTest {
        val orderRequest = CreateOrderRequest(
            items = emptyList(),
            subtotal = 10000,
            total = 10000,
            paymentMethod = "CASH",
        )

        repository.queueCashPayment(orderRequest, "user-456", 10000, 0, null)

        coVerify {
            dao.insert(
                match {
                    it.venueId == "venue-123" &&
                        it.amountCents == 10000 &&
                        it.method == "CASH" &&
                        it.syncStatus == "PENDING"
                },
            )
        }
    }

    @Test
    fun `queueCashPayment returns unique local ID`() = runTest {
        val orderRequest = CreateOrderRequest(
            items = emptyList(),
            subtotal = 5000,
            total = 5000,
            paymentMethod = "CASH",
        )

        val id1 = repository.queueCashPayment(orderRequest, "user-456", 5000, 0, null)
        val id2 = repository.queueCashPayment(orderRequest, "user-456", 5000, 0, null)

        assertNotEquals(id1, id2)
    }

    @Test
    fun `queueCashPayment stores tip correctly`() = runTest {
        // total=11500, tip=1500 => amountCents = total - tip = 10000
        val orderRequest = CreateOrderRequest(
            items = emptyList(),
            subtotal = 10000,
            tip = 1500,
            total = 11500,
            paymentMethod = "CASH",
        )

        repository.queueCashPayment(orderRequest, "user-456", 12000, 500, 5)

        coVerify {
            dao.insert(
                match {
                    it.tipCents == 1500 &&
                        it.amountCents == 10000 &&
                        it.cashTenderedCents == 12000 &&
                        it.changeCents == 500 &&
                        it.rating == 5
                },
            )
        }
    }
}
