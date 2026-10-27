package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.transactions.data.*
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.presentation.exchange.*
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExchangePaymentViewModelTest {
    @get:Rule val main=MainDispatcherRule(UnconfinedTestDispatcher())
    private val repo=mockk<ExchangeRepository>()
    private val store=mockk<ExchangePendingStore>()
    private val secure=mockk<SecureStorage>(relaxed=true) { every { venueId } returns "venue"; every { userId } returns "actor" }
    private val monitor=mockk<ConnectivityMonitor> { every { isOnline() } returns true }
    private val overrideEnabled = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val settings = mockk<com.avoqado.pos.tpvsettings.data.TpvSettingsRepository> {
        every { managerPinOverrideEnabled } returns overrideEnabled
    }
    private fun vm()=ExchangePaymentViewModel(repo,store,secure,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),monitor,settings)

    @Test fun `disabled cash cannot create pending even after override changes`() {
        coEvery { store.load("venue", "payment") } returns null
        val vm = vm()
        vm.open(Transaction("payment", payoutOptions = listOf(com.avoqado.pos.transactions.data.model.PayoutOption("CASH", 1000, 0, true))))
        val draft = ExchangeDraft("payment", emptyList(), emptyList(), ExchangeSettlementDraft("PAYOUT", -250))
        vm.begin(draft)
        overrideEnabled.value = true
        val enabled = vm.options(draft).single()
        assertTrue(enabled.requiresPin)
        overrideEnabled.value = false
        val disabled = vm.options(draft).single()
        assertEquals(TextosCambio.EFECTIVO_SIN_PERMISO, disabled.disabledReason)
        vm.choose(disabled)
        vm.choose(enabled)
        assertFalse(vm.state.value.locked)
        assertNotNull(vm.draft.value)
        coVerify(exactly = 0) { store.save(any()) }
        coVerify(exactly = 0) { repo.create(any(), any()) }
    }
    @Test fun `opening pending sale does not post or charge without explicit confirmation`() {
        coEvery { store.load("venue","payment") } returns PendingExchange("venue","payment","ORD-1","Ana","""{"staffId":"actor"}""")
        val vm=vm(); vm.open(Transaction("payment"))
        assertTrue(vm.ready.value); assertTrue(vm.state.value.locked)
        assertEquals(TextosCambio.CONFIRMAR_ESTADO,vm.state.value.action)
        coVerify(exactly=0) { repo.create(any(),any()) }
    }
    @Test fun `another actor cannot use pending card and pending body survives`() {
        val p=PendingExchange("venue","payment","ORD-1","Ana","""{"staffId":"other"}""")
        coEvery { store.load("venue","payment") } returns p
        val vm=vm(); vm.open(Transaction("payment")); vm.retry()
        assertEquals(TextosCambio.ACTOR_MISMATCH.replace("{nombre}","Ana"),vm.message.value)
        coVerify(exactly=0) { repo.create(any(),any()) }
        coVerify(exactly=0) { store.remove(any()) }
    }
    @Test fun `offline confirmation never creates or queues an exchange`() {
        coEvery { store.load("venue","payment") } returns null
        every { monitor.isOnline() } returns false
        val vm=vm(); vm.open(Transaction("payment"))
        vm.begin(ExchangeDraft("payment",emptyList(),emptyList(),ExchangeSettlementDraft("EVEN",0)))
        assertEquals(TextosCambio.SIN_CONEXION,vm.message.value)
        coVerify(exactly=0) { store.save(any()) }
        coVerify(exactly=0) { repo.create(any(),any()) }
    }
    @Test fun `failed pending read keeps editing locked and explicit retry reads storage again`() {
        coEvery { store.load("venue","payment") } throws java.io.IOException("disk")
        val vm=vm(); vm.open(Transaction("payment"))
        assertTrue(vm.ready.value); assertTrue(vm.state.value.locked)
        assertEquals(TextosCambio.RECUPERACION_FALLIDA,vm.message.value)
        coEvery { store.load("venue","payment") } returns null
        vm.retry()
        assertFalse(vm.state.value.locked)
        coVerify(exactly=0) { repo.create(any(),any()) }
    }

    @Test fun `late storage read cannot show previous sale pending under the new sale`() = runTest {
        val old=kotlinx.coroutines.CompletableDeferred<PendingExchange?>()
        coEvery { store.load("venue","old") } coAnswers { old.await() }
        coEvery { store.load("venue","new") } returns null
        val vm=vm(); vm.open(Transaction("old")); vm.open(Transaction("new"))
        old.complete(PendingExchange("venue","old","ORD-OLD","Ana","{}"))
        advanceUntilIdle()
        assertTrue(vm.ready.value); assertFalse(vm.state.value.locked)
        coVerify(exactly=0) { repo.create(any(),any()) }
    }

    @Test fun `returning to the same sale reloads a pending cleared by the exchange screen`() {
        coEvery { store.load("venue","payment") } returns PendingExchange("venue","payment","ORD-1","Ana","""{"staffId":"actor"}""")
        val vm=vm(); val sale=Transaction("payment"); vm.open(sale)
        assertTrue(vm.state.value.locked)
        coEvery { store.load("venue","payment") } returns null
        vm.open(sale,force=true)
        assertFalse(vm.state.value.locked)
        coVerify(exactly=0) { repo.create(any(),any()) }
    }

}
