package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.presentation.TransactionsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** A missing eligibility flag must fail closed; stale details must never select another actor's sale. */
class TransactionCustomerAssignmentTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val json = Json { ignoreUnknownKeys = true }
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val repository = mockk<TransactionRepository>(relaxed = true)

    private fun viewModel(): TransactionsViewModel {
        every { storage.userId } returns "actor-a"
        every { storage.venueId } returns "venue-a"
        every { repository.transactions } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        val factory = mockk<RefreshGateFactory>()
        every { factory.create(any(), any()) } returns RefreshGate(clock = { kotlin.time.Duration.ZERO })
        return TransactionsViewModel(
            repository = repository, refundRepository = mockk(relaxed = true),
            cashDrawerRepository = mockk(relaxed = true), terminalPaymentService = mockk(relaxed = true),
            roleManager = RoleManager(storage), tpvSettingsRepository = mockk(relaxed = true),
            managerOverrideCoordinator = mockk(relaxed = true), orderRepository = mockk(relaxed = true),
            printerService = mockk(relaxed = true), secureStorage = storage, refreshGateFactory = factory,
            connectivityMonitor = mockk(relaxed = true),
        )
    }

    @Test fun `detail preserves server customer identity and explicit eligibility`() {
        // Dropping either additive field would make an eligible sale impossible to attach safely.
        val detail = json.decodeFromString<Transaction>(
            """{"id":"payment-a","customerId":"customer-a","canAssignCustomer":true}""",
        )
        val decoded = json.parseToJsonElement(json.encodeToString(detail)).jsonObject
        assertEquals("customer-a", decoded["customerId"]?.jsonPrimitive?.content)
        assertEquals("true", decoded["canAssignCustomer"]?.jsonPrimitive?.content)
    }

    @Test fun `late detail from previous payment cannot replace the selected sale`() = runTest {
        val old = CompletableDeferred<Transaction?>()
        coEvery { repository.fetchTransactionDetail("payment-a", any(), any()) } coAnswers { old.await() }
        coEvery { repository.fetchTransactionDetail("payment-b", any(), any()) } returns Transaction("payment-b")
        val vm = viewModel()
        vm.selectTransaction("payment-a")
        vm.selectTransaction("payment-b")
        old.complete(Transaction("payment-a"))
        assertEquals("payment-b", vm.selectedTransaction.value?.id)
    }

    @Test fun `late detail cannot restore a dismissed sale`() = runTest {
        val old = CompletableDeferred<Transaction?>()
        coEvery { repository.fetchTransactionDetail("payment-a", any(), any()) } coAnswers { old.await() }
        val vm = viewModel()
        vm.selectTransaction("payment-a")
        vm.clearSelection()
        old.complete(Transaction("payment-a"))
        assertNull(vm.selectedTransaction.value)
    }

    @Test fun `late detail after actor change is not exposed to the new actor`() = runTest {
        val old = CompletableDeferred<Transaction?>()
        coEvery { repository.fetchTransactionDetail("payment-a", any(), any()) } coAnswers { old.await() }
        val vm = viewModel()
        vm.selectTransaction("payment-a")
        every { storage.userId } returns "actor-b"
        old.complete(Transaction("payment-a"))
        assertNull(vm.selectedTransaction.value)
    }

    @Test fun `server missing null or false eligibility never grants assignment`() {
        listOf(
            """{"id":"old"}""",
            """{"id":"old","customerId":null,"canAssignCustomer":null}""",
            """{"id":"old","canAssignCustomer":false}""",
        ).forEach { payload ->
            assertEquals(false, json.decodeFromString<Transaction>(payload).canAssignCustomer == true)
        }
    }
}
