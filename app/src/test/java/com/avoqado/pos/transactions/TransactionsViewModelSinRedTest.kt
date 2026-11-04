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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration

/**
 * D18 (3-nov): con la lista de Ventas vacía porque no hubo red, nada la recargaba al volver la red. Codex r1: la lista de otra
 * sucursal, persona o búsqueda se enseñaba (#1, #3). Codex r2 y r3 tumbaron la recarga por flancos (H1–H3, N1–N3): la regla
 * de la ronda 4 es por NIVEL, con reintentos de 5, 10, 20 y 30 s, sólo con la pantalla a la vista y sin la bandera del servidor.
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

    // La red del aparato, la bandera del servidor (la regla no la usa: aquí parpadea como en la vida real) y la cola.
    private val ahora: Duration = Duration.ZERO
    private val red = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val porSubir = MutableStateFlow(0)
    private val monitor = mockk<ConnectivityMonitor>(relaxed = true).also {
        every { it.isConnected } returns red
        every { it.isServerReachable } returns servidor
    }

    private val repository: TransactionRepository = mockk(relaxed = true)

    private fun consulta(busqueda: String?) = ConsultaDeVentas.de(sucursal, usuario, busqueda)

    private fun crear(): TransactionsViewModel {
        every { repository.transactions } returns ventas
        every { repository.isLoading } returns cargando
        every { repository.isLoadingMore } returns MutableStateFlow(false)
        every { repository.falloDeCarga } returns fallo
        every { repository.consultaDeLaLista } returns consultaDeLaLista
        every { repository.consultaPara(any()) } answers { consulta(firstArg()) }
        coEvery { repository.fetchTransactions(any(), any()) } coAnswers {
            val pedida = consulta(secondArg())
            pedidas += pedida
            val conElCobro = cobroSinRedYaSubio // lo que el servidor ya tenía cuando salió la consulta
            cargando.value = true
            try {
                enCamino?.await()
                if (sinRed) {
                    fallo.value = FalloDeLectura.SIN_RED
                    Result.failure(java.io.IOException("sin red de prueba"))
                } else {
                    ventas.value = listOfNotNull(
                        Transaction(id = "pago-${pedida.busqueda}"),
                        Transaction(id = "cobro-sin-red").takeIf { conElCobro },
                    )
                    consultaDeLaLista.value = pedida
                    fallo.value = null
                    Result.success(Unit)
                }
            } finally {
                cargando.value = false
            }
        }
        val gateFactory = mockk<RefreshGateFactory>().also {
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
            reglaDeRecarga = ReglaDeRecarga(monitor, porSubir, reloj = { scheduler.currentTime }),
        )
    }

    /** Lo que decide la pantalla, con lo que la pantalla lee del ViewModel. */
    private fun estado(vm: TransactionsViewModel): EstadoDeLaListaDeVentas {
        val deLaConsulta = vm.consultaDeLaLista.value == vm.consultaVigente.value
        val lista = if (deLaConsulta) vm.transactions.value else emptyList()
        return estadoDeLaListaDeVentas(deLaConsulta, lista.size, lista.size, vm.isLoading.value, vm.falloDeCarga.value)
    }

    private fun ids(vm: TransactionsViewModel) = vm.transactions.value.map { it.id }

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

    private fun TestScope.servidorParpadea() {
        servidor.value = false
        runCurrent()
        servidor.value = true
        runCurrent()
    }

    private fun TestScope.wifiSeVaYVuelve() {
        red.value = false
        runCurrent()
        red.value = true
        runCurrent()
    }

    private fun TestScope.irA(ms: Long) {
        advanceTimeBy(ms - scheduler.currentTime)
        runCurrent()
    }

    // MARK: - «No se cargó»: se recarga en cuanto hay red

    @Test
    fun `vuelve el WiFi y la lista que no se cargo se recarga sola`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        red.value = false
        val vm = crear()
        aLaVista(vm)
        assertEquals("sin red no se intenta", 0, pedidas.size)

        red.value = true
        runCurrent()

        assertEquals(1, pedidas.size)
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    @Test
    fun `internet que vuelve sin que el WiFi se apague - la lista se recupera sola`() = runTest(scheduler) {
        sinRed = true
        servidor.value = false // el módem perdió internet; la red del aparato nunca bajó (WINDOWS igual)
        val vm = crear()
        vm.autoRefresh() // falla
        aLaVista(vm)

        irA(7_000)
        sinRed = false
        servidor.value = true // vuelve internet
        irA(40_000)

        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
        assertTrue("sin tormenta: ${pedidas.size} cargas", pedidas.size <= 3)
    }

    @Test
    fun `fallo parcial con el servidor alcanzable - reintenta a los 5, 10 y 20 s y luego cada 30, nunca dos a la vez`() = runTest(scheduler) {
        sinRed = true // una ruta no contesta aunque la salud del servidor sí
        val vm = crear()
        vm.autoRefresh() // a los 0 s: falla
        aLaVista(vm)

        val esperadas = listOf(
            4_999L to 1, 5_001L to 2, 14_999L to 2, 15_001L to 3, 34_999L to 3,
            35_001L to 4, 64_999L to 4, 65_001L to 5, 94_999L to 5, 95_001L to 6,
        )
        for ((en, cargas) in esperadas) {
            irA(en)
            assertEquals("a los $en ms", cargas, pedidas.size)
        }

        val lenta = CompletableDeferred<Unit>()
        enCamino = lenta
        irA(125_001)
        assertEquals(7, pedidas.size) // sale y no contesta
        irA(245_001)
        assertEquals("con una carga en camino no sale otra", 7, pedidas.size)
        enCamino = null
        lenta.complete(Unit)
        runCurrent()
    }

    @Test
    fun `N3 - una carga que falla tarde no deja la lista sin recargar`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        red.value = false
        val vm = crear()
        aLaVista(vm)

        val lenta = CompletableDeferred<Unit>()
        enCamino = lenta
        sinRed = true
        red.value = true // sale una carga y tarda
        runCurrent()
        assertEquals(1, pedidas.size)
        wifiSeVaYVuelve() // el WiFi se cae y vuelve con ella en camino
        enCamino = null
        lenta.complete(Unit) // y falla tarde
        runCurrent()
        servidorParpadea() // su fallo apaga la bandera y el ping la prende
        sinRed = false

        irA(30_000)

        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
        assertEquals(2, pedidas.size)
    }

    @Test
    fun `pausar a media reconexion y volver - recalcula y recarga sola`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        red.value = false
        val vm = crear()
        val pantalla = aLaVista(vm)

        sinRed = true
        red.value = true // vuelve el WiFi, pero el servidor todavía no contesta
        runCurrent()
        assertEquals(1, pedidas.size)
        pantalla.registro.currentState = Lifecycle.State.CREATED // la app al fondo
        runCurrent()
        irA(60_000)
        assertEquals("en el fondo no carga", 1, pedidas.size)

        pantalla.registro.currentState = Lifecycle.State.RESUMED // regresa antes de que vuelva el servidor
        runCurrent()
        assertEquals("recalcula al volver: hace falta y ya pasó la espera", 2, pedidas.size)
        sinRed = false // ahora sí vuelve el servidor
        irA(70_001)

        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
        assertEquals(3, pedidas.size)
    }

    @Test
    fun `con la pantalla en segundo plano no hay ninguna carga`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        porSubir.value = 1
        val vm = crear()
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.CREATED // otra app encima o el aparato bloqueado
        backgroundScope.launch { vm.vigilarLaRedMientrasSeVe(pantalla) }
        runCurrent()

        wifiSeVaYVuelve()
        porSubir.value = 0
        runCurrent()
        irA(10 * 60_000L)

        assertEquals(0, pedidas.size)
    }

    // MARK: - Lo que se ve se pidió con cobros por subir: se recarga al vaciarse la cola

    @Test
    fun `la cola tarda 60 s en subir - al vaciarse recarga con el cobro, y despues ya no`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        porSubir.value = 1 // el cobro de 10 pesos en efectivo, hecho sin red
        red.value = false
        val vm = crear()
        aLaVista(vm)

        red.value = true // con lo que tenga el servidor, sin esperar a la cola
        runCurrent()
        assertEquals(listOf("pago-"), ids(vm))
        irA(60_000)
        assertEquals(1, pedidas.size)

        cobroSinRedYaSubio = true
        porSubir.value = 0 // la cola terminó de subir
        runCurrent()
        assertEquals(listOf("pago-", "cobro-sin-red"), ids(vm))

        wifiSeVaYVuelve()
        irA(15 * 60_000L)
        assertEquals("una carga buena con la cola en 0 apaga la necesidad", 2, pedidas.size)
    }

    @Test
    fun `N1 - la cola se vacia con la bandera del servidor apagada - igual recarga con el cobro`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        porSubir.value = 1
        red.value = false
        val vm = crear()
        aLaVista(vm)
        red.value = true
        runCurrent()
        assertEquals(listOf("pago-"), ids(vm))

        servidor.value = false // otra petición devolvió un 5xx: la bandera se apaga
        runCurrent()
        cobroSinRedYaSubio = true
        porSubir.value = 0 // el último cobro sí subió
        runCurrent()
        servidor.value = true // el ping la vuelve a prender
        irA(60_000)

        assertEquals(listOf("pago-", "cobro-sin-red"), ids(vm))
    }

    @Test
    fun `cola atorada - con la lista a la vista no recarga mientras no suba, y recarga al vaciarse`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        porSubir.value = 1 // un pendiente que no sube
        red.value = false
        val vm = crear()
        aLaVista(vm)
        red.value = true
        runCurrent()
        assertEquals("con lo que tenga el servidor, sin esperar a la cola", 1, pedidas.size)

        irA(10 * 60_000L)
        assertEquals("sin tormenta", 1, pedidas.size)

        porSubir.value = 0
        runCurrent()
        assertEquals(2, pedidas.size)
    }

    @Test
    fun `si la consulta iba en camino cuando subio la cola, al terminar se pide otra con el cobro`() = runTest(scheduler) {
        fallo.value = FalloDeLectura.SIN_RED
        porSubir.value = 1
        red.value = false
        val vm = crear()
        aLaVista(vm)

        val lenta = CompletableDeferred<Unit>()
        enCamino = lenta
        red.value = true // la consulta sale antes de que suba la cola, y tarda
        runCurrent()
        cobroSinRedYaSubio = true
        porSubir.value = 0 // la cola sube con esa consulta en camino
        runCurrent()
        enCamino = null
        lenta.complete(Unit)
        runCurrent()

        assertEquals(2, pedidas.size)
        assertEquals(listOf("pago-", "cobro-sin-red"), ids(vm))
    }

    @Test
    fun `lo que se veia sin red con un cobro en la cola se recarga cuando la cola sube`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh() // la lista, con red y sin nada en la cola
        val pantalla = aLaVista(vm)
        pantalla.registro.currentState = Lifecycle.State.CREATED // se va a cobrar
        runCurrent()
        red.value = false
        porSubir.value = 1 // cobra 10 pesos sin red
        pantalla.registro.currentState = Lifecycle.State.RESUMED // regresa a Ventas sin red: se ve la lista de antes
        runCurrent()

        red.value = true
        runCurrent()
        assertEquals("la cola todavía no sube", 1, pedidas.size)
        cobroSinRedYaSubio = true
        porSubir.value = 0
        runCurrent()

        assertEquals(listOf("pago-", "cobro-sin-red"), ids(vm))
    }

    @Test
    fun `con la lista de la consulta y nada en la cola, volver la red no recarga`() = runTest(scheduler) {
        ventas.value = listOf(Transaction(id = "ya-estaba"))
        consultaDeLaLista.value = consulta(null)
        fallo.value = FalloDeLectura.SIN_RED
        val vm = crear()
        aLaVista(vm)

        wifiSeVaYVuelve()
        servidorParpadea()
        irA(10 * 60_000L)

        assertEquals(0, pedidas.size)
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
