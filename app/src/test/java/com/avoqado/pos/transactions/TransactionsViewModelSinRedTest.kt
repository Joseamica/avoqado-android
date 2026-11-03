package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.presentation.TransactionsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration

/**
 * D18 (3-nov): con la lista de Ventas vacía porque no hubo red, nada la recargaba al volver la red: la pantalla se quedaba
 * en «Sin transacciones» hasta que alguien jalara.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelSinRedTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val ventas = MutableStateFlow<List<Transaction>>(emptyList())
    private val fallo = MutableStateFlow<FalloDeLectura?>(null)
    private val red = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val repository: TransactionRepository = mockk(relaxed = true)

    private fun crear(): TransactionsViewModel {
        every { repository.transactions } returns ventas
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        every { repository.falloDeCarga } returns fallo
        coEvery { repository.fetchTransactions(any(), any()) } coAnswers {
            ventas.value = listOf(Transaction(id = "pago-1"))
            fallo.value = null
            Result.success(Unit)
        }
        val factory = mockk<RefreshGateFactory>()
        every { factory.create(any(), any()) } returns RefreshGate(clock = { Duration.ZERO }, random = { 0.5 })
        val monitor = mockk<ConnectivityMonitor>(relaxed = true)
        every { monitor.isConnected } returns red
        every { monitor.isServerReachable } returns servidor
        return TransactionsViewModel(
            repository = repository,
            refundRepository = mockk(relaxed = true),
            cashDrawerRepository = mockk(relaxed = true),
            terminalPaymentService = mockk(relaxed = true),
            roleManager = mockk(relaxed = true),
            tpvSettingsRepository = mockk(relaxed = true),
            managerOverrideCoordinator = mockk(relaxed = true),
            orderRepository = mockk(relaxed = true),
            printerService = mockk(relaxed = true),
            secureStorage = mockk(relaxed = true),
            refreshGateFactory = factory,
            connectivityMonitor = monitor,
            exchangePendingStore = mockk(relaxed = true),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.cortarYVolverLaRed() {
        red.value = false
        runCurrent()
        red.value = true
        runCurrent()
    }

    @Test
    fun `vacia porque no hubo red - al volver la red se recarga sola`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        val vm = crear()
        backgroundScope.launch { vm.vigilarLaRed() }
        runCurrent()

        cortarYVolverLaRed()
        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) } // espera a que la ruta suba
        advanceTimeBy(2_001)
        runCurrent()

        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = null) }
        assertEquals(listOf("pago-1"), vm.transactions.value.map { it.id })
    }

    @Test
    fun `con ventas en la mano, volver la red no recarga por esto`() = runTest(scheduler) {
        ventas.value = listOf(Transaction(id = "ya-estaba"))
        fallo.value = FalloDeLectura.SIN_RED
        val vm = crear()
        backgroundScope.launch { vm.vigilarLaRed() }
        runCurrent()

        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()

        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }
    }

    @Test
    fun `si el servidor contesto mal, volver la red no recarga sola - para eso esta Reintentar`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SERVIDOR
        val vm = crear()
        backgroundScope.launch { vm.vigilarLaRed() }
        runCurrent()

        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()

        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }
    }
}
