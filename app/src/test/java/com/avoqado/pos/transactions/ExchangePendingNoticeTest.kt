package com.avoqado.pos.transactions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.transactions.data.*
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.presentation.TransactionsViewModel
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class ExchangePendingNoticeTest {
    @get:Rule val main=MainDispatcherRule(UnconfinedTestDispatcher())
    @get:Rule val temporary=TemporaryFolder()
    private val scope=CoroutineScope(Dispatchers.IO+SupervisorJob())
    private val secure=mockk<SecureStorage>(relaxed=true)
    private val repository=mockk<TransactionRepository>(relaxed=true)
    private val terminal=mockk<TerminalPaymentService>(relaxed=true)
    private lateinit var store:ExchangePendingStore

    @Before fun setup() {
        every { secure.venueId } returns "venue"
        every { secure.userId } returns "actor"
        every { repository.transactions } returns MutableStateFlow(emptyList())
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        store=ExchangePendingStore(PreferenceDataStoreFactory.create(scope=scope,
            produceFile={temporary.root.resolve("notices.preferences_pb")}))
    }
    @After fun cleanup() { scope.cancel() }
    private fun pending(payment:String="payment",venue:String="venue",actor:String="actor",folio:String="ORD-1")=
        PendingExchange(venue,payment,folio,"Ana","""{"staffId":"$actor"}""")
    private fun vm(pendingStore:ExchangePendingStore=store):TransactionsViewModel {
        val factory=mockk<RefreshGateFactory> {
            every { create(any(),any()) } returns RefreshGate(clock={Duration.ZERO})
        }
        return TransactionsViewModel(repository,mockk(relaxed=true),mockk(relaxed=true),terminal,
            mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),
            mockk(relaxed=true),secure,factory,mockk(relaxed=true),pendingStore)
    }

    @Test fun `sales entry lists only the current venue and actor pending notices without sending`()=runTest {
        store.save(pending())
        store.save(pending(payment="second",folio="ORD-2"))
        store.save(pending(payment="other-actor",actor="other"))
        store.save(pending(payment="other-venue",venue="other"))
        val vm=vm()
        vm.refreshPendingExchanges().join()
        assertEquals(setOf("payment","second"),vm.pendingExchangeNotices.value.map { it.paymentId }.toSet())
        assertEquals(setOf("Hay un cambio sin confirmar de ORD-1","Hay un cambio sin confirmar de ORD-2"),
            vm.pendingExchangeNotices.value.map { it.message }.toSet())
        assertNull(vm.selectedTransaction.value)
        coVerify(exactly=0) { repository.fetchTransactionDetail(any(),any(),any()) }
        coVerify(exactly=0) { repository.fetchTransactions(any(),any()) }
        coVerify(exactly=0) { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) }
    }

    @Test fun `tapping a pending notice opens its original sale without charging`()=runTest {
        store.save(pending())
        val sale=Transaction("payment")
        coEvery { repository.fetchTransactionDetail("payment","actor","venue") } returns sale
        val vm=vm()
        vm.refreshPendingExchanges().join()
        vm.openPendingExchange(vm.pendingExchangeNotices.value.single().paymentId)
        advanceUntilIdle()
        assertEquals("payment",vm.selectedTransactionId.value)
        assertEquals(sale,vm.selectedTransaction.value)
        assertNotNull(store.load("venue","payment"))
        coVerify(exactly=1) { repository.fetchTransactionDetail("payment","actor","venue") }
        coVerify(exactly=0) { terminal.sendPaymentToTerminal(any(),any(),any(),any(),any(),any(),any()) }
    }

    @Test fun `returning to sales removes a notice only after the pending is removed`()=runTest {
        val pending=store.save(pending())
        val vm=vm()
        vm.refreshPendingExchanges().join()
        assertEquals(1,vm.pendingExchangeNotices.value.size)
        store.remove(pending)
        vm.refreshPendingExchanges().join()
        assertTrue(vm.pendingExchangeNotices.value.isEmpty())
    }

    @Test fun `a notice cannot open after changing the actor or venue`()=runTest {
        store.save(pending())
        val vm=vm()
        vm.refreshPendingExchanges().join()
        every { secure.userId } returns "other"
        vm.openPendingExchange("payment")
        every { secure.userId } returns "actor"
        every { secure.venueId } returns "other"
        vm.openPendingExchange("payment")
        assertNull(vm.selectedTransactionId.value)
        coVerify(exactly=0) { repository.fetchTransactionDetail(any(),any(),any()) }
    }

    @Test fun `a delayed pending lookup cannot publish notices into another session`()=runTest {
        val deferred=CompletableDeferred<List<PendingExchange>>()
        val slowStore=mockk<ExchangePendingStore>()
        coEvery { slowStore.forActor("venue","actor") } coAnswers { deferred.await() }
        val vm=vm(slowStore)
        vm.refreshPendingExchanges()
        every { secure.userId } returns "other"
        deferred.complete(listOf(pending()))
        advanceUntilIdle()
        assertTrue(vm.pendingExchangeNotices.value.isEmpty())
    }
}
