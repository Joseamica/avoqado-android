package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.FalloDeLectura
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * D18 (3-nov): sin red, la lista de Ventas vacía dice «Sin conexión» y se recarga con «Reintentar». Codex r1: la lista de
 * otra sucursal, persona o búsqueda se enseñaba (#1, #3). La recarga automática al volver la red se quitó en la ronda 5
 * (cuatro rondas de Codex con bloqueantes de la misma familia): al volver la conexión no recarga nada solo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelSinRedTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    // La sesión de este momento.
    private var sucursal = "venue"
    private var usuario = "ana"

    // El repositorio falso: lo que guarda, lo que hay en el servidor y lo que falla.
    private val ventas = MutableStateFlow<List<Transaction>>(emptyList())
    private val consultaDeLaLista = MutableStateFlow<ConsultaDeVentas?>(null)
    private val fallo = MutableStateFlow<FalloDeLectura?>(null)
    private val cargando = MutableStateFlow(false)
    private var sinRed = false
    private var enCamino: CompletableDeferred<Unit>? = null
    private val pedidas = mutableListOf<ConsultaDeVentas>()

    // La red del aparato y la bandera del servidor.
    private val ahora: Duration = Duration.ZERO
    private val red = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val monitor = mockk<ConnectivityMonitor>(relaxed = true).also {
        every { it.isConnected } returns red
        every { it.isServerReachable } returns servidor
    }

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
            cargando.value = true
            try {
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
            } finally {
                cargando.value = false
            }
        }
        val gateFactory = factory ?: mockk<RefreshGateFactory>().also {
            every { it.create(any(), any()) } returns RefreshGate(clock = { ahora }, random = { 0.5 })
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
            connectivityMonitor = monitor,
            exchangePendingStore = mockk(relaxed = true),
        )
    }

    /** Lo que decide la pantalla, con lo que la pantalla lee del ViewModel. */
    private fun estado(vm: TransactionsViewModel): EstadoDeLaListaDeVentas {
        val deLaConsulta = vm.consultaDeLaLista.value == vm.consultaVigente.value
        val lista = if (deLaConsulta) vm.transactions.value else emptyList()
        return estadoDeLaListaDeVentas(deLaConsulta, lista.size, lista.size, vm.isLoading.value, vm.falloDeCarga.value)
    }

    // MARK: - Al volver la red, nada recarga solo (ronda 5)

    @Test
    fun `al volver la red nada recarga solo, y Reintentar si recarga`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        val vm = crear(factory = RefreshGateFactory(monitor)) // la fábrica de verdad, que sí mira la red
        vm.autoRefresh() // sin red: «Sin conexión»
        assertEquals(EstadoDeLaListaDeVentas.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm))

        sinRed = false
        red.value = true // vuelve el WiFi
        runCurrent()
        servidor.value = false // y la bandera del servidor parpadea
        runCurrent()
        servidor.value = true
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertEquals("nada recarga solo", 1, pedidas.size)
        assertEquals(EstadoDeLaListaDeVentas.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm))

        vm.manualRefresh() // «Reintentar»
        runCurrent()

        assertEquals(2, pedidas.size)
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    // MARK: - La lista va ligada a su consulta (Codex r1 #1 y #3)

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
