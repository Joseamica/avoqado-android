package com.avoqado.pos.transactions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.data.sync.VueltaDeLaRed
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.model.ConsultaDeVentas
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.presentation.EstadoDeLaListaDeVentas
import com.avoqado.pos.transactions.presentation.TransactionsViewModel
import com.avoqado.pos.transactions.presentation.estadoDeLaListaDeVentas
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration

/**
 * D18 (3-nov): con la lista de Ventas vacía porque no hubo red, nada la recargaba al volver la red. Y Codex r1: la lista de
 * otra sucursal, persona o búsqueda se enseñaba (#1, #3); la recarga salía a la vez que subían los cobros sin red (#2) y en
 * cada parpadeo de la bandera del servidor, forzando el gate y con la app en segundo plano (#4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelSinRedTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    // La sesión de este momento.
    private var sucursal = "venue"
    private var usuario = "ana"

    // El repositorio falso: lo que guarda y lo que falla.
    private val ventas = MutableStateFlow<List<Transaction>>(emptyList())
    private val consultaDeLaLista = MutableStateFlow<ConsultaDeVentas?>(null)
    private val fallo = MutableStateFlow<FalloDeLectura?>(null)
    private val cargando = MutableStateFlow(false)
    private var sinRed = false
    private var enCamino: CompletableDeferred<Unit>? = null
    private val pedidas = mutableListOf<ConsultaDeVentas>()

    // La red y la cola sin red.
    private val red = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val porSubir = MutableStateFlow(0)

    private val repository: TransactionRepository = mockk(relaxed = true)

    private fun consulta(busqueda: String?) = ConsultaDeVentas.de(sucursal, usuario, busqueda)

    private fun crear(factory: RefreshGateFactory? = null): TransactionsViewModel {
        every { repository.transactions } returns ventas
        every { repository.isLoading } returns cargando
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        every { repository.falloDeCarga } returns fallo
        every { repository.consultaDeLaLista } returns consultaDeLaLista
        every { repository.consultaPara(any()) } answers { consulta(firstArg()) }
        coEvery { repository.fetchTransactions(any(), any()) } coAnswers {
            val pedida = consulta(secondArg())
            pedidas += pedida
            enCamino?.await()
            if (sinRed) {
                fallo.value = FalloDeLectura.SIN_RED
                Result.failure(java.io.IOException("sin red de prueba"))
            } else {
                ventas.value = listOf(Transaction(id = "pago-${pedida.busqueda}"))
                consultaDeLaLista.value = pedida
                fallo.value = null
                Result.success(Unit)
            }
        }
        val gateFactory = factory ?: mockk<RefreshGateFactory>().also {
            every { it.create(any(), any()) } returns RefreshGate(clock = { Duration.ZERO }, random = { 0.5 })
        }
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
            refreshGateFactory = gateFactory,
            connectivityMonitor = mockk(relaxed = true),
            exchangePendingStore = mockk(relaxed = true),
            vueltaDeLaRed = VueltaDeLaRed(red, servidor, porSubir, esperaDeLaRutaMs = 2_000, esperaDeLaColaMs = 30_000),
        )
    }

    /** Lo que decide la pantalla, con lo que la pantalla lee del ViewModel. */
    private fun estado(vm: TransactionsViewModel): EstadoDeLaListaDeVentas {
        val deLaConsulta = vm.consultaDeLaLista.value == vm.consultaVigente.value
        val lista = if (deLaConsulta) vm.transactions.value else emptyList()
        return estadoDeLaListaDeVentas(deLaConsulta, lista.size, lista.size, vm.isLoading.value, vm.falloDeCarga.value)
    }

    private fun TestScope.vigilar(vm: TransactionsViewModel) {
        backgroundScope.launch { vm.vigilarLaRed() }
        runCurrent()
    }

    private fun TestScope.cortarYVolverLaRed() {
        red.value = false
        runCurrent()
        red.value = true
        runCurrent()
    }

    private class Pantalla : LifecycleOwner {
        val registro = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registro
    }

    @Test
    fun `vacia porque no hubo red - al volver la red se recarga sola`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        val vm = crear()
        vigilar(vm)

        cortarYVolverLaRed()
        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) } // espera a que la ruta suba
        advanceTimeBy(2_001)
        runCurrent()

        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = null) }
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    @Test
    fun `con un cobro sin subir, la recarga espera a que la cola se vacie`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        porSubir.value = 1
        val vm = crear()
        vigilar(vm)

        cortarYVolverLaRed()
        advanceTimeBy(10_000)
        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }

        porSubir.value = 0
        runCurrent()
        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = null) }
    }

    @Test
    fun `un parpadeo de la bandera del servidor con el WiFi arriba no recarga`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        val vm = crear()
        vigilar(vm)

        repeat(3) {
            servidor.value = false
            runCurrent()
            servidor.value = true
            runCurrent()
            advanceTimeBy(10_000)
        }

        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }
    }

    @Test
    fun `la recarga al volver la red pasa por el gate - no fuerza su enfriamiento`() = runTest(scheduler) {
        sinRed = true
        val vm = crear()
        vm.autoRefresh() // falla: el gate se enfría
        coVerify(exactly = 1) { repository.fetchTransactions(any(), any()) }
        vigilar(vm)

        sinRed = false
        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()

        // Sin el aviso de la red al gate (lo da la fábrica de verdad, prueba de abajo), el enfriamiento manda.
        coVerify(exactly = 1) { repository.fetchTransactions(any(), any()) }
    }

    @Test
    fun `al volver la red de verdad, la fabrica reabre el gate y la recarga sale sin forzarlo`() = runTest(scheduler) {
        val monitor = mockk<ConnectivityMonitor>(relaxed = true)
        every { monitor.isConnected } returns red
        sinRed = true
        val vm = crear(factory = RefreshGateFactory(monitor))
        vm.autoRefresh() // falla: el gate se enfría
        vigilar(vm)

        sinRed = false
        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()

        coVerify(exactly = 2) { repository.fetchTransactions(any(), any()) }
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    @Test
    fun `con la pantalla en segundo plano no vigila, y al verse otra vez si`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        val vm = crear()
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.CREATED // otra app encima o el aparato bloqueado
        backgroundScope.launch { vm.vigilarLaRedMientrasSeVe(pantalla) }
        runCurrent()

        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()
        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }

        pantalla.registro.currentState = Lifecycle.State.RESUMED
        runCurrent()
        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()
        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = null) }
    }

    @Test
    fun `con ventas de la consulta en la mano, volver la red no recarga por esto`() = runTest(scheduler) {
        ventas.value = listOf(Transaction(id = "ya-estaba"))
        consultaDeLaLista.value = consulta(null)
        fallo.value = FalloDeLectura.SIN_RED
        val vm = crear()
        vigilar(vm)

        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()

        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }
    }

    @Test
    fun `si el servidor contesto mal, volver la red no recarga sola - para eso esta Reintentar`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SERVIDOR
        val vm = crear()
        vigilar(vm)

        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()

        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }
    }

    @Test
    fun `la lista de otra sucursal o de otra persona no se ve`() = runTest(scheduler) {
        ventas.value = listOf(Transaction(id = "de-otra-sucursal", staffName = "Ana"))
        consultaDeLaLista.value = ConsultaDeVentas("venue-a", "ana", "")
        sucursal = "venue-b"
        usuario = "beto" // cerró sesión y entró otra persona, o entró por PIN
        fallo.value = FalloDeLectura.SIN_RED

        val vm = crear()

        assertEquals(EstadoDeLaListaDeVentas.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm))
        assertEquals(emptyList<Pair<String, String>>(), vm.staffOptions())
    }

    @Test
    fun `buscar sin red no deja a la vista los resultados de la busqueda anterior`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))

        sinRed = true
        vm.setSearchText("café")
        advanceTimeBy(500) // pasa el debounce de 400 ms
        runCurrent()

        assertEquals("café", pedidas.last().busqueda)
        assertEquals(EstadoDeLaListaDeVentas.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm))
    }

    @Test
    fun `si iba en camino otra consulta, al terminar se pide la busqueda vigente`() = runTest(scheduler) {
        val vm = crear()
        val primera = CompletableDeferred<Unit>()
        enCamino = primera
        vm.autoRefresh() // la lista sin búsqueda, en camino
        vm.setSearchText("café")
        advanceTimeBy(500) // la búsqueda se une a la que va en camino
        runCurrent()

        enCamino = null
        primera.complete(Unit)
        runCurrent()

        assertEquals(listOf("", "café"), pedidas.map { it.busqueda })
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
        assertEquals("pago-café", vm.transactions.value.single().id)
    }
}
