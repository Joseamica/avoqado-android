package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.model.PendingCustomerAssignment
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.presentation.TransactionsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class TransactionCustomerAssignmentFlowTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val repository = mockk<TransactionRepository>(relaxed = true)
    private val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
    private val customer = Customer("customer-a", firstName = "Ana")
    private val sale = Transaction("payment-a", amount = 100.0, tipAmount = 10.0, canAssignCustomer = true)
    private val pending = PendingCustomerAssignment("actor-a", "venue-a", "payment-a", "customer-a", "Ana")
    private val writes = mutableListOf<PendingCustomerAssignment>()
    private val posts = mutableListOf<PendingCustomerAssignment>()
    private val clears = mutableListOf<PendingCustomerAssignment>()
    private var stored: PendingCustomerAssignment? = null

    private fun vm(): TransactionsViewModel {
        every { storage.userId } returns "actor-a"
        every { storage.venueId } returns "venue-a"
        every { storage.userRole } returns "MANAGER"
        every { storage.venuePermissions } returns listOf("orders:attach-customer", "customers:read", "customers:create")
        every { storage.readPendingCustomerAssignment(any(), any(), any()) } answers { stored }
        every { storage.writePendingCustomerAssignment(any()) } answers {
            firstArg<PendingCustomerAssignment>().let { writes += it; stored = it }; true
        }
        every { storage.clearPendingCustomerAssignment(any()) } answers {
            clears += firstArg<PendingCustomerAssignment>(); stored = null; true
        }
        every { connectivity.isOnline() } returns true
        every { connectivity.isConnected } returns MutableStateFlow(true)
        every { connectivity.isServerReachable } returns MutableStateFlow(true)
        every { repository.transactions } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } returns sale
        coEvery { repository.assignCustomer(any()) } coAnswers {
            val value = firstArg<PendingCustomerAssignment>()
            assertEquals("persist before network", value, stored)
            posts += value; Result.success(Unit)
        }
        val factory = mockk<RefreshGateFactory>()
        every { factory.create(any(), any()) } returns RefreshGate(clock = { kotlin.time.Duration.ZERO })
        return TransactionsViewModel(
            repository = repository, refundRepository = mockk(relaxed = true),
            cashDrawerRepository = mockk(relaxed = true), terminalPaymentService = mockk(relaxed = true),
            roleManager = RoleManager(storage), tpvSettingsRepository = mockk(relaxed = true),
            managerOverrideCoordinator = mockk(relaxed = true), orderRepository = mockk(relaxed = true),
            printerService = mockk(relaxed = true), secureStorage = storage, refreshGateFactory = factory,
            connectivityMonitor = connectivity,
        ).apply { selectTransaction("payment-a") }
    }

    @Test fun `cashier cannot attach even with wildcard permission`() = runTest {
        val vm = vm()
        every { storage.userRole } returns "CASHIER"
        every { storage.venuePermissions } returns listOf("*:*")
        assertFalse(vm.canAssignCustomer)
        vm.chooseCustomer(customer); vm.confirmCustomerAssignment()
        assertTrue(posts.isEmpty()); assertTrue(writes.isEmpty())
    }

    @Test fun `manager with empty permissions cannot attach`() = runTest {
        val vm = vm(); every { storage.venuePermissions } returns emptyList()
        assertFalse(vm.canAssignCustomer)
    }

    @Test fun `missing eligibility fails closed on old server`() = runTest {
        val vm = vm()
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } returns Transaction("payment-old")
        vm.selectTransaction("payment-old")
        assertFalse(vm.canAssignCustomer)
    }

    @Test fun `cancelled selection never persists or posts`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer); vm.cancelCustomerAssignment(); vm.confirmCustomerAssignment()
        assertTrue(posts.isEmpty()); assertTrue(writes.isEmpty()); assertNull(vm.customerCandidate.value)
    }

    @Test fun `confirmation persists exact scope and reloads authoritative customer without changing money`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } returnsMany listOf(sale, sale.copy(customerId = customer.id, customerName = "Ana", canAssignCustomer = false))
        vm.confirmCustomerAssignment()
        assertEquals(listOf(pending), writes); assertEquals(listOf(pending), posts)
        assertEquals("customer-a", vm.selectedTransaction.value?.customerId)
        assertEquals(110.0, vm.selectedTransaction.value!!.totalAmount, 0.0)
        assertTrue(vm.customerAssignmentSucceeded.value); assertNull(stored)
    }

    @Test fun `durability failure sends nothing and does not celebrate`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        every { storage.writePendingCustomerAssignment(any()) } returns false
        vm.confirmCustomerAssignment()
        assertTrue(posts.isEmpty()); assertFalse(vm.customerAssignmentSucceeded.value)
        assertNotNull(vm.customerAssignmentMessage.value)
    }

    @Test fun `offline cannot post or pretend success`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        every { connectivity.isOnline() } returns false
        vm.confirmCustomerAssignment()
        assertTrue(posts.isEmpty()); assertFalse(vm.customerAssignmentSucceeded.value)
        assertEquals("Necesitas conexión para asignar un cliente a esta venta", vm.customerAssignmentMessage.value)
    }

    @Test fun `connection failure retains durable selection for explicit retry`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        coEvery { repository.assignCustomer(any()) } returns Result.failure(IOException("dropped"))
        vm.confirmCustomerAssignment()
        assertEquals(pending, stored); assertEquals(customer.id, vm.customerCandidate.value?.id)
        assertFalse(vm.customerAssignmentSucceeded.value)
    }

    @Test fun `process recovery reads customer and does not replay automatically`() = runTest {
        val vm = vm(); stored = pending
        vm.selectTransaction("payment-a")
        assertEquals(customer.id, vm.customerCandidate.value?.id)
        assertTrue(posts.isEmpty()); assertTrue(writes.isEmpty())
    }

    @Test fun `recovered acknowledgement clears pending with no second post`() = runTest {
        val vm = vm(); stored = pending
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } returns sale.copy(customerId = customer.id, customerName = "Ana", canAssignCustomer = false)
        vm.selectTransaction("payment-a")
        assertNull(stored); assertTrue(posts.isEmpty()); assertTrue(vm.customerAssignmentSucceeded.value)
    }

    @Test fun `conflict discovered before retry never replaces other customer`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } returns sale.copy(customerId = "other-customer", canAssignCustomer = false)
        vm.confirmCustomerAssignment()
        assertTrue(posts.isEmpty()); assertFalse(vm.customerAssignmentSucceeded.value)
        assertEquals("other-customer", vm.selectedTransaction.value?.customerId)
        assertNotNull(vm.customerAssignmentMessage.value)
    }

    @Test fun `changed actor cannot confirm candidate chosen by previous actor`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        every { storage.userId } returns "actor-b"
        vm.confirmCustomerAssignment()
        assertTrue(posts.isEmpty()); assertTrue(writes.isEmpty())
    }

    @Test fun `foreign persisted scope cannot become a candidate`() = runTest {
        val vm = vm(); stored = pending.copy(actorId = "actor-b")
        vm.selectTransaction("payment-a")
        assertNull(vm.customerCandidate.value); assertTrue(posts.isEmpty())
    }

    @Test fun `double confirmation posts once and keeps pending while server waits`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        val response = CompletableDeferred<Result<Unit>>()
        coEvery { repository.assignCustomer(any()) } coAnswers {
            posts += firstArg<PendingCustomerAssignment>(); response.await()
        }
        vm.confirmCustomerAssignment(); vm.confirmCustomerAssignment()
        assertEquals(listOf(pending), posts); assertTrue(vm.isAssigningCustomer.value)
        assertEquals(pending, stored)
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } returns sale.copy(customerId = customer.id, canAssignCustomer = false)
        response.complete(Result.success(Unit))
        assertFalse(vm.isAssigningCustomer.value)
    }

    @Test fun `late assignment result cannot mutate or celebrate another selected sale`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        val response = CompletableDeferred<Result<Unit>>()
        coEvery { repository.assignCustomer(any()) } coAnswers { response.await() }
        vm.confirmCustomerAssignment()
        coEvery { repository.fetchTransactionDetail("payment-b", any(), any()) } returns Transaction("payment-b")
        vm.selectTransaction("payment-b")
        response.complete(Result.success(Unit))
        assertEquals("payment-b", vm.selectedTransaction.value?.id)
        assertFalse(vm.customerAssignmentSucceeded.value); assertEquals(pending, stored)
    }

    @Test fun `ack without detail keeps pending and does not show success`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } returnsMany listOf(sale, null)
        vm.confirmCustomerAssignment()
        assertEquals(pending, stored); assertFalse(vm.customerAssignmentSucceeded.value)
    }

    @Test fun `permission removed while checking sale prevents the post`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        val detail = CompletableDeferred<Transaction?>()
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } coAnswers { detail.await() }
        vm.confirmCustomerAssignment()
        every { storage.venuePermissions } returns listOf("customers:read")
        detail.complete(sale)
        assertTrue(posts.isEmpty()); assertFalse(vm.customerAssignmentSucceeded.value)
    }

    @Test fun `late assignment under different venue cannot show success`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        val response = CompletableDeferred<Result<Unit>>()
        coEvery { repository.assignCustomer(any()) } coAnswers { response.await() }
        vm.confirmCustomerAssignment()
        every { storage.venueId } returns "venue-b"
        response.complete(Result.success(Unit))
        assertFalse(vm.customerAssignmentSucceeded.value); assertEquals(pending, stored)
    }

    @Test fun `failed local acknowledgement cannot report completed recovery`() = runTest {
        val vm = vm(); stored = pending
        every { storage.clearPendingCustomerAssignment(any()) } returns false
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } returns sale.copy(customerId = customer.id, canAssignCustomer = false)
        vm.selectTransaction("payment-a")
        assertFalse(vm.customerAssignmentSucceeded.value); assertEquals(pending, stored)
        assertNotNull(vm.customerAssignmentMessage.value)
    }

    @Test fun `customer search and create honor separate effective permissions`() = runTest {
        val vm = vm()
        every { storage.venuePermissions } returns listOf("orders:attach-customer", "customers:read")
        assertTrue(vm.canAssignCustomer); assertFalse(vm.canCreateCustomerForAssignment)
        every { storage.venuePermissions } returns listOf("orders:attach-customer", "customers:create")
        assertFalse(vm.canAssignCustomer)
    }

    @Test fun `lost post response is verified by get without a second post`() = runTest {
        val vm = vm(); vm.chooseCustomer(customer)
        coEvery { repository.assignCustomer(any()) } coAnswers {
            posts += firstArg<PendingCustomerAssignment>(); Result.failure(IOException("lost response"))
        }
        coEvery { repository.fetchTransactionDetail(any(), any(), any()) } returnsMany listOf(sale, sale.copy(customerId = customer.id, canAssignCustomer = false))
        vm.confirmCustomerAssignment()
        assertEquals(listOf(pending), posts); assertNull(stored)
        assertTrue(vm.customerAssignmentSucceeded.value)
    }
}
