package com.avoqado.pos.transactions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.domain.refresh.ReglaDeRecarga
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
import kotlin.time.Duration.Companion.seconds

/**
 * D18 (3-nov): con la lista de Ventas vacía porque no hubo red, nada la recargaba al volver la red. Codex r1: la lista de otra
 * sucursal, persona o búsqueda se enseñaba (#1, #3). Codex r2, la familia de la recarga automática (H1 la espera de 30 s,
 * H2 internet que vuelve sin apagar el WiFi, H3 la reconexión olvidada al pausar): la regla única T1/T2/T3, por flancos y
 * sólo con la pantalla a la vista.
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
    private var cobroSinRedYaSubio = false
    private var enCamino: CompletableDeferred<Unit>? = null
    private val pedidas = mutableListOf<ConsultaDeVentas>()

    // La red, el servidor y lo que falta por subir.
    private var ahora: Duration = Duration.ZERO
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
                ventas.value = listOfNotNull(
                    Transaction(id = "pago-${pedida.busqueda}"),
                    Transaction(id = "cobro-sin-red").takeIf { cobroSinRedYaSubio },
                )
                consultaDeLaLista.value = pedida
                fallo.value = null
                Result.success(Unit)
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
            connectivityMonitor = mockk(relaxed = true),
            exchangePendingStore = mockk(relaxed = true),
            reglaDeRecarga = ReglaDeRecarga(red, servidor, porSubir),
        )
    }

    /** Lo que decide la pantalla, con lo que la pantalla lee del ViewModel. */
    private fun estado(vm: TransactionsViewModel): EstadoDeLaListaDeVentas {
        val deLaConsulta = vm.consultaDeLaLista.value == vm.consultaVigente.value
        val lista = if (deLaConsulta) vm.transactions.value else emptyList()
        return estadoDeLaListaDeVentas(deLaConsulta, lista.size, lista.size, vm.isLoading.value, vm.falloDeCarga.value)
    }

    private class Pantalla : LifecycleOwner {
        val registro = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registro
    }

    /** La lista a la vista (RESUMED), con la regla corriendo. */
    private fun TestScope.aLaVista(vm: TransactionsViewModel): Pantalla {
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.RESUMED
        backgroundScope.launch { vm.vigilarLaRedMientrasSeVe(pantalla) }
        runCurrent()
        return pantalla
    }

    private fun TestScope.servidorSeVaYVuelve() {
        servidor.value = false
        runCurrent()
        servidor.value = true
        dejarPasar()
    }

    /** Lo que tarda la ruta en subir: la regla nueva no espera, pero así la de antes tampoco pierde por el reloj. */
    private fun TestScope.dejarPasar() {
        advanceTimeBy(2_001)
        runCurrent()
    }

    private fun TestScope.wifiSeVaYVuelve() {
        red.value = false
        runCurrent()
        red.value = true
        dejarPasar()
    }

    // MARK: - T2: red y servidor de no a sí

    @Test
    fun `T2 - el WiFi que vuelve recarga la lista que se quedo sin red`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        red.value = false // la pantalla se ve sin red: T1 no recarga
        val vm = crear()
        aLaVista(vm)
        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }

        red.value = true
        dejarPasar()

        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = null) }
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    @Test
    fun `T2 - internet que vuelve sin que el WiFi se apague tambien recarga`() = runTest(scheduler) {
        sinRed = true
        servidor.value = false // el módem perdió internet; la red del aparato nunca bajó (WINDOWS igual)
        val vm = crear()
        vm.autoRefresh() // falla y enfría el gate; la fábrica del gate no se entera de la vuelta: el WiFi nunca bajó
        aLaVista(vm)
        assertEquals(1, pedidas.size)

        sinRed = false
        servidor.value = true
        dejarPasar()

        assertEquals(2, pedidas.size)
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    @Test
    fun `un fallo parcial con el servidor alcanzable hace UNA sola recarga, no mas`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        sinRed = true // la consulta sigue sin respuesta aunque la salud del servidor conteste
        red.value = false
        val vm = crear()
        aLaVista(vm)

        red.value = true // T2
        dejarPasar()
        assertEquals(1, pedidas.size)

        // Su propio fallo apaga la bandera del servidor y el ping la vuelve a prender, una y otra vez.
        repeat(5) {
            ahora += 70.seconds // pasado cualquier enfriamiento: lo que frena es la regla, no el reloj
            servidorSeVaYVuelve()
        }

        assertEquals(1, pedidas.size)
    }

    @Test
    fun `T1 respeta el enfriamiento del gate - no lo fuerza`() = runTest(scheduler) {
        sinRed = true
        val vm = crear()
        vm.autoRefresh() // falla: el gate se enfría y el reloj no avanza

        sinRed = false
        aLaVista(vm) // vuelve a la pantalla con red

        assertEquals(1, pedidas.size)
    }

    @Test
    fun `T2 no depende de que la fabrica del gate llegue antes - recarga dentro del enfriamiento`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        val vm = crear()
        vm.autoRefresh() // falla sin red: el gate se enfría y el reloj no avanza
        aLaVista(vm)

        sinRed = false
        red.value = true // como la fábrica del gate al recuperar la red, T2 reinicia el enfriamiento
        dejarPasar()

        assertEquals(2, pedidas.size)
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    @Test
    fun `T2 con la fabrica de verdad - al volver el WiFi la recarga sale`() = runTest(scheduler) {
        val monitor = mockk<ConnectivityMonitor>(relaxed = true)
        every { monitor.isConnected } returns red
        sinRed = true
        red.value = false
        val vm = crear(factory = RefreshGateFactory(monitor))
        vm.autoRefresh() // falla: el gate se enfría
        aLaVista(vm)

        sinRed = false
        red.value = true
        dejarPasar()

        assertEquals(2, pedidas.size)
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    @Test
    fun `con ventas de la consulta en la mano, volver la red no recarga por esto`() = runTest(scheduler) {
        ventas.value = listOf(Transaction(id = "ya-estaba"))
        consultaDeLaLista.value = consulta(null)
        fallo.value = FalloDeLectura.SIN_RED
        val vm = crear()
        aLaVista(vm)

        wifiSeVaYVuelve()
        servidorSeVaYVuelve()

        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }
    }

    // MARK: - T3: la cola sin red termina de subir

    @Test
    fun `T3 - una cola que tarda 60 s en subir recarga al vaciarse, ya con el cobro`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        porSubir.value = 1 // el cobro de 10 pesos en efectivo, hecho sin red
        red.value = false
        val vm = crear()
        aLaVista(vm)

        red.value = true // T2: recarga con lo que tenga el servidor
        dejarPasar()
        assertEquals(1, pedidas.size)
        assertEquals(listOf("pago-"), vm.transactions.value.map { it.id })

        advanceTimeBy(60_000)
        cobroSinRedYaSubio = true
        porSubir.value = 0 // la cola terminó de subir
        runCurrent()

        assertEquals("T3 recarga aunque la lista ya enseñe datos", 2, pedidas.size)
        assertEquals(listOf("pago-", "cobro-sin-red"), vm.transactions.value.map { it.id })
    }

    @Test
    fun `una cola atorada no frena la recarga - T2 recarga y T3 vuelve a recargar cuando por fin se vacia`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        porSubir.value = 1 // un pendiente que no sube
        red.value = false
        val vm = crear()
        aLaVista(vm)

        red.value = true
        dejarPasar()
        assertEquals("T2 no espera a la cola", 1, pedidas.size)

        advanceTimeBy(10 * 60_000L)
        assertEquals(1, pedidas.size)

        porSubir.value = 0
        runCurrent()
        assertEquals(2, pedidas.size)
    }

    // MARK: - T1 y la pantalla a la vista

    @Test
    fun `T1 - pausar a media reconexion y volver con red recarga al volver`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        red.value = false
        servidor.value = false
        val vm = crear()
        val pantalla = aLaVista(vm) // se ve sin red: T1 no recarga

        red.value = true // vuelve el WiFi, el servidor todavía no contesta
        dejarPasar()
        pantalla.registro.currentState = Lifecycle.State.CREATED // la app al fondo
        runCurrent()
        servidor.value = true // el servidor vuelve con la pantalla pausada
        dejarPasar()
        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }

        pantalla.registro.currentState = Lifecycle.State.RESUMED // vuelve a la pantalla, con red
        runCurrent()

        coVerify(exactly = 1) { repository.fetchTransactions(page = 1, search = null) }
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    @Test
    fun `H3 - volver antes que el servidor y que falle la carga - al volver el servidor recarga`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        red.value = false
        servidor.value = false
        val vm = crear()
        val pantalla = aLaVista(vm)

        red.value = true // vuelve el WiFi, el servidor todavía no contesta
        dejarPasar()
        pantalla.registro.currentState = Lifecycle.State.CREATED // la app al fondo
        runCurrent()
        pantalla.registro.currentState = Lifecycle.State.RESUMED // regresa antes de que vuelva el servidor
        runCurrent()
        sinRed = true
        vm.autoRefresh() // la carga al regresar falla y enfría el gate
        assertEquals(1, pedidas.size)

        sinRed = false
        servidor.value = true // ahora sí vuelve el servidor
        dejarPasar()

        assertEquals(2, pedidas.size)
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    @Test
    fun `con la pantalla en segundo plano no hay ninguna recarga`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        porSubir.value = 1
        val vm = crear()
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.CREATED // otra app encima o el aparato bloqueado
        backgroundScope.launch { vm.vigilarLaRedMientrasSeVe(pantalla) }
        runCurrent()

        wifiSeVaYVuelve()
        servidorSeVaYVuelve()
        porSubir.value = 0
        runCurrent()
        advanceTimeBy(60_000)

        coVerify(exactly = 0) { repository.fetchTransactions(any(), any()) }
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
