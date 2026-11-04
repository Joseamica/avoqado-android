package com.avoqado.pos.transactions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.ClaseDeFallo
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.data.network.LecturaFallida
import com.avoqado.pos.core.data.sync.AvisosDeVentasSubidas
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.domain.refresh.RelojDeRecarga
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.onlineorders.data.VentanaALaVista
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration

/**
 * D22 (3-nov): Ventas se recarga sola al volver la red o al subir un cobro, por NIVEL y con generación
 * (`docs/audit/2026-11-03-d22-recarga-sola/promesa.md`, diseños r2–r4). Los nombres siguen el mapa de entradas: H1–H3 (D18 r2),
 * N1–N3 (r3), r4-1…r4-4, la píldora y los de los diseños r2–r4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelRecargaSolaTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    // El repositorio falso.
    private val ventas = MutableStateFlow<List<Transaction>>(emptyList())
    private val consultaDeLaLista = MutableStateFlow<ConsultaDeVentas?>(null)
    private val fallo = MutableStateFlow<FalloDeLectura?>(null)
    private val cargando = MutableStateFlow(false)
    private val cargandoMas = MutableStateFlow(false)
    private val paginasCargadas = MutableStateFlow(1)

    /** Cómo contesta el servidor: `null` = bien; si no, el fallo. */
    private var contesta: (() -> Throwable)? = null
    private var cobroSubio = false
    private var retenerFondo: CompletableDeferred<Unit>? = null
    private var retenerCajero: CompletableDeferred<Unit>? = null
    private var paginasAlTerminar = 1

    private data class Pedida(val busqueda: String, val enFondo: Boolean)
    private val pedidas = mutableListOf<Pedida>()

    // La red, la ventana y los avisos.
    private val ahora: Duration = Duration.ZERO
    private val red = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true) // la regla NO la usa; aquí parpadea como en la vida real
    private val monitor = mockk<ConnectivityMonitor>(relaxed = true).also {
        every { it.isConnected } returns red
        every { it.isServerReachable } returns servidor
    }
    private val avisos = AvisosDeVentasSubidas()
    private val reloj = object : RelojDeRecarga() {
        override fun ahora(): Long = scheduler.currentTime
    }

    private val repository: TransactionRepository = mockk(relaxed = true)

    private val sinRed: () -> Throwable = { LecturaFallida(FalloDeLectura.SIN_RED, IOException("sin red de prueba")) }
    private val deNuestraApi: (Int) -> () -> Throwable = { codigo ->
        { LecturaFallida(FalloDeLectura.SERVIDOR, clases = listOf(ClaseDeFallo.deRespuesta(codigo, """{"error":"No"}"""))) }
    }

    private fun crear(): TransactionsViewModel {
        every { repository.transactions } returns ventas
        every { repository.isLoading } returns cargando
        every { repository.isLoadingMore } returns cargandoMas
        every { repository.paginasCargadas } returns paginasCargadas
        every { repository.falloDeCarga } returns fallo
        every { repository.consultaDeLaLista } returns consultaDeLaLista
        every { repository.consultaPara(any()) } answers { ConsultaDeVentas.de("venue", "ana", firstArg()) }
        coEvery { repository.fetchTransactions(any(), any(), any()) } coAnswers {
            val busqueda = secondArg<String?>()
            val enFondo = thirdArg<Boolean>()
            pedidas += Pedida(busqueda.orEmpty(), enFondo)
            val conElCobro = cobroSubio // lo que el servidor tenía cuando salió la consulta
            cargando.value = true
            try {
                (if (enFondo) retenerFondo else retenerCajero)?.await()
                val error = contesta?.invoke()
                if (error != null) {
                    fallo.value = (error as? LecturaFallida)?.tipo ?: FalloDeLectura.SIN_RED
                    Result.failure(error)
                } else {
                    ventas.value = listOfNotNull(Transaction(id = "pago-${busqueda.orEmpty()}"), Transaction(id = "cobro").takeIf { conElCobro })
                    consultaDeLaLista.value = ConsultaDeVentas.de("venue", "ana", busqueda)
                    fallo.value = null
                    paginasCargadas.value = paginasAlTerminar
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
            connectivityMonitor = monitor,
            exchangePendingStore = mockk(relaxed = true),
            avisosDeVentas = avisos,
            reloj = reloj,
        )
    }

    @After fun ventanaALaVista() = VentanaALaVista.publicar(true)

    private fun estado(vm: TransactionsViewModel): EstadoDeLaListaDeVentas {
        val deLaConsulta = vm.consultaDeLaLista.value == vm.consultaVigente.value
        val lista = if (deLaConsulta) vm.transactions.value else emptyList()
        return estadoDeLaListaDeVentas(deLaConsulta, lista.size, lista.size, vm.isLoading.value, vm.falloDeCarga.value)
    }

    private class Pantalla : LifecycleOwner {
        val registro = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registro
    }

    private fun TestScope.aLaVista(vm: TransactionsViewModel): Pantalla {
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.RESUMED
        backgroundScope.launch { vm.recargarSolaMientrasSeVe(pantalla) }
        backgroundScope.launch { vm.hayVentasNuevas.collect {} } // la pantalla mira la píldora
        runCurrent()
        return pantalla
    }

    private fun TestScope.irA(ms: Long) {
        advanceTimeBy(ms - scheduler.currentTime)
        runCurrent()
    }

    private fun TestScope.conLaLista(): TransactionsViewModel {
        val vm = crear()
        vm.autoRefresh()
        runCurrent()
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
        return vm
    }

    private val automaticas get() = pedidas.count { it.enFondo }

    // MARK: - Volver la red (H1–H3, N3)

    @Test fun `H1 - la cola tarda 60 s - al volver la red recarga y al subir el cobro otra vez, ya con el`() = runTest(scheduler) {
        contesta = sinRed
        red.value = false
        val vm = crear()
        vm.autoRefresh() // sin red: «Sin conexión»
        aLaVista(vm)
        irA(60_000)
        assertEquals("sin red no se intenta", 1, pedidas.size)

        contesta = null
        red.value = true
        runCurrent()
        assertEquals("al volver la red, sin esperar a la cola", listOf("pago-"), vm.transactions.value.map { it.id })

        irA(120_000)
        cobroSubio = true
        avisos.subio("venue") // la cola terminó de subir el cobro 60 s después
        irA(122_001)

        assertEquals(listOf("pago-", "cobro"), vm.transactions.value.map { it.id })
        assertEquals(2, automaticas)
    }

    @Test fun `H2 - internet que vuelve sin apagar el WiFi - reintenta solo y se recupera`() = runTest(scheduler) {
        contesta = sinRed
        servidor.value = false
        val vm = crear()
        vm.autoRefresh() // a los 0 s: falla
        aLaVista(vm)
        irA(7_000)
        contesta = null
        servidor.value = true // vuelve internet; la red del aparato nunca bajó (WINDOWS igual)
        irA(40_000)

        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
        assertTrue("sin tormenta: ${pedidas.size}", pedidas.size <= 3)
    }

    @Test fun `H3 - pausar a media reconexion y volver - recalcula y recarga`() = runTest(scheduler) {
        contesta = sinRed
        red.value = false
        val vm = crear()
        vm.autoRefresh()
        val pantalla = aLaVista(vm)
        irA(10_000)
        red.value = true // vuelve el WiFi, el servidor todavía no contesta
        runCurrent()
        assertEquals(2, pedidas.size)
        pantalla.registro.currentState = Lifecycle.State.CREATED
        runCurrent()
        contesta = null
        irA(60_000)
        assertEquals("en el fondo no carga", 2, pedidas.size)

        pantalla.registro.currentState = Lifecycle.State.RESUMED
        runCurrent()

        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
        assertEquals(3, pedidas.size)
    }

    @Test fun `N3 - un fallo tardio no deja la lista sin recargar`() = runTest(scheduler) {
        contesta = sinRed
        val vm = crear()
        vm.autoRefresh() // a los 0 s
        aLaVista(vm)
        val lenta = CompletableDeferred<Unit>()
        retenerFondo = lenta
        irA(5_001) // la automática sale y tarda
        assertEquals(1, automaticas)
        red.value = false
        runCurrent()
        red.value = true // el WiFi se cae y vuelve con ella en camino
        runCurrent()
        retenerFondo = null
        lenta.complete(Unit) // y falla tarde
        runCurrent()
        servidor.value = false
        servidor.value = true
        contesta = null

        irA(30_000)

        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
        assertEquals(2, automaticas)
    }

    @Test fun `fallo parcial con el servidor alcanzable - reintenta a los 5, 10 y 20 s y luego cada 30, nunca dos a la vez`() = runTest(scheduler) {
        contesta = sinRed
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)
        for ((en, cargas) in listOf(4_999L to 1, 5_001L to 2, 14_999L to 2, 15_001L to 3, 34_999L to 3, 35_001L to 4, 64_999L to 4, 65_001L to 5, 95_001L to 6)) {
            irA(en)
            assertEquals("a los $en ms", cargas, pedidas.size)
        }
        val lenta = CompletableDeferred<Unit>()
        retenerFondo = lenta
        irA(125_001)
        assertEquals(7, pedidas.size)
        irA(300_000)
        assertEquals("con una carga en camino no sale otra", 7, pedidas.size)
        lenta.complete(Unit)
    }

    // MARK: - Un cobro que sube (N1, r4-1, r4-2, agrupado)

    @Test fun `N1 y r4-1 - el cobro que subio recarga aunque la bandera del servidor este apagada y otro siga retenido`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        servidor.value = false // otro 5xx la apagó; la regla no la mira
        cobroSubio = true
        avisos.subio("venue") // sube uno; el retenido nunca avisa
        irA(2_001)

        assertEquals(listOf("pago-", "cobro"), vm.transactions.value.map { it.id })
        assertEquals(1, automaticas)
    }

    @Test fun `r4-2 - una respuesta vieja no borra un aviso nuevo`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        val lenta = CompletableDeferred<Unit>()
        retenerFondo = lenta
        avisos.subio("venue")
        irA(1_001) // la automática sale
        assertEquals(1, automaticas)
        cobroSubio = true
        avisos.subio("venue") // otro cobro sube con ella en camino
        retenerFondo = null
        lenta.complete(Unit) // termina bien, sin ese cobro
        runCurrent()
        assertEquals(listOf("pago-"), vm.transactions.value.map { it.id })

        irA(4_000)

        assertEquals(2, automaticas)
        assertEquals(listOf("pago-", "cobro"), vm.transactions.value.map { it.id })
    }

    @Test fun `se agrupan los avisos seguidos en una sola recarga`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        avisos.subio("venue")
        irA(200)
        avisos.subio("venue")
        irA(400)
        avisos.subio("venue")
        irA(10_000)
        assertEquals(1, automaticas)
    }

    @Test fun `r1-5 - el agrupado no se estira - con un aviso cada 500 ms carga a los 2 s del primero`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        var t = 0L
        while (t <= 2_000L) {
            irA(t)
            avisos.subio("venue")
            t += 500
        }
        irA(2_001)
        assertEquals(1, automaticas)
    }

    @Test fun `una carga buena que empezo despues del aviso apaga la necesidad`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        avisos.subio("venue")
        irA(2_001)
        assertEquals(1, automaticas)
        red.value = false
        runCurrent()
        red.value = true
        irA(10 * 60_000L)
        assertEquals(1, automaticas)
    }

    // MARK: - Lo que la detiene (r4-3, 401 y 4xx)

    @Test fun `r4-3 - el PIN cancelado no se vuelve a pedir y un aviso nuevo recarga de fondo sin abrir nada`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        contesta = deNuestraApi(403)
        vm.manualRefresh() // «Reintentar»: 403 overridable → PIN → el cajero cancela
        runCurrent()
        irA(10 * 60_000L)
        assertEquals("nada automático tras el «no»", 0, automaticas)

        avisos.subio("venue") // un cobro nuevo
        irA(10 * 60_000L + 2_001)
        assertEquals("una sola automática, de fondo (sin PIN)", listOf(true), pedidas.filter { it.enFondo }.map { it.enFondo })
        irA(30 * 60_000L)
        assertEquals("y se vuelve a detener", 1, automaticas)
    }

    @Test fun `401 y los 4xx no reintentan`() = runTest(scheduler) {
        contesta = deNuestraApi(401)
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)
        irA(10 * 60_000L)
        assertEquals(1, pedidas.size)

        contesta = deNuestraApi(404)
        vm.manualRefresh()
        runCurrent()
        irA(20 * 60_000L)
        assertEquals(2, pedidas.size)
    }

    // MARK: - Sólo mientras se ve (r4-4, segundo plano, r2-C y r3-1)

    @Test fun `r4-4 - WINDOWS minimizado no consulta y al restaurar recarga`() = runTest(scheduler) {
        val vm = conLaLista()
        VentanaALaVista.publicar(false)
        aLaVista(vm)
        avisos.subio("venue")
        irA(10 * 60_000L)
        assertEquals(0, automaticas)

        VentanaALaVista.publicar(true)
        irA(10 * 60_000L + 2_001)
        assertEquals(1, automaticas)
    }

    @Test fun `con la pantalla en segundo plano no hay ninguna carga`() = runTest(scheduler) {
        contesta = sinRed
        val vm = crear()
        vm.autoRefresh()
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.CREATED
        backgroundScope.launch { vm.recargarSolaMientrasSeVe(pantalla) }
        runCurrent()
        avisos.subio("venue")
        red.value = false
        red.value = true
        irA(10 * 60_000L)
        assertEquals(1, pedidas.size)
    }

    @Test fun `r2-C - minimizar corta la automatica en camino y no cuenta como fallo`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        retenerFondo = CompletableDeferred()
        avisos.subio("venue")
        irA(1_001)
        assertEquals(1, automaticas)
        VentanaALaVista.publicar(false) // se corta
        runCurrent()
        assertFalse(vm.isLoading.value)
        retenerFondo = null
        VentanaALaVista.publicar(true)
        irA(4_000)
        assertEquals("al volver, la vuelve a pedir: no se gastó", 2, automaticas)
    }

    @Test fun `r2-C2 - lo que pidio el cajero termina aunque se esconda la pantalla`() = runTest(scheduler) {
        contesta = sinRed
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)
        val lenta = CompletableDeferred<Unit>()
        retenerCajero = lenta
        contesta = null
        vm.manualRefresh() // «Reintentar», lento
        runCurrent()
        VentanaALaVista.publicar(false)
        runCurrent()
        retenerCajero = null
        lenta.complete(Unit)
        runCurrent()
        assertEquals(EstadoDeLaListaDeVentas.Lista, estado(vm))
    }

    @Test fun `r3-1 - cancelar la automatica no mata la busqueda`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        retenerFondo = CompletableDeferred() // la automática, lenta
        avisos.subio("venue")
        irA(1_001)
        assertEquals(1, automaticas)

        vm.setSearchText("café")
        irA(1_501) // pasa el debounce: la búsqueda hace SU consulta (no se une a la automática)
        VentanaALaVista.publicar(false) // minimizar corta la automática
        runCurrent()

        assertEquals("pago-café", vm.transactions.value.first().id)
        vm.setSearchText("té")
        irA(2_001)
        assertEquals("las búsquedas siguen", "pago-té", vm.transactions.value.first().id)
    }

    @Test fun `r3-1b - Reintentar no se une a la automatica - hace su propia consulta`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        retenerFondo = CompletableDeferred()
        avisos.subio("venue")
        irA(1_001)
        vm.manualRefresh()
        runCurrent()
        assertEquals(listOf(Pedida("", false), Pedida("", true), Pedida("", false)), pedidas)
    }

    @Test fun `una busqueda por asentarse frena la automatica`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        vm.setSearchText("ca") // escribiendo: todavía no sale
        avisos.subio("venue")
        irA(399)
        assertEquals(0, automaticas)
        irA(5_000) // la búsqueda salió y ya trae lo nuevo
        assertEquals(0, automaticas)
        assertEquals("ca", pedidas.last().busqueda)
    }

    // MARK: - La píldora (más de una página)

    @Test fun `r1-3 - con varias paginas no recarga sola - aparece la pildora y tocarla es jalar`() = runTest(scheduler) {
        val vm = conLaLista()
        paginasCargadas.value = 3
        aLaVista(vm)
        avisos.subio("venue")
        irA(10_000)
        assertEquals(0, automaticas)
        assertTrue(vm.hayVentasNuevas.value)

        vm.manualRefresh() // «Actualizar»
        runCurrent()
        assertEquals(1, paginasCargadas.value)
        assertFalse(vm.hayVentasNuevas.value)
    }

    @Test fun `pildora - con busqueda, Actualizar pide la pagina 1 de esa busqueda`() = runTest(scheduler) {
        val vm = conLaLista()
        vm.setSearchText("café")
        irA(500)
        paginasCargadas.value = 2
        aLaVista(vm)
        avisos.subio("venue")
        irA(5_000)
        assertTrue(vm.hayVentasNuevas.value)
        vm.manualRefresh()
        runCurrent()
        assertEquals(Pedida("café", false), pedidas.last())
        assertFalse(vm.hayVentasNuevas.value)
    }

    @Test fun `pildora - un Actualizar fallido deja las paginas y la pildora`() = runTest(scheduler) {
        val vm = conLaLista()
        paginasCargadas.value = 3
        aLaVista(vm)
        avisos.subio("venue")
        irA(5_000)
        contesta = sinRed
        vm.manualRefresh()
        runCurrent()
        assertEquals(3, paginasCargadas.value)
        assertTrue(vm.hayVentasNuevas.value)
        irA(10 * 60_000L)
        assertEquals("nada reemplaza solo las 3 páginas", 0, automaticas)
    }

    @Test fun `pildora - un 403 propio con el PIN cancelado deja las paginas y la pildora`() = runTest(scheduler) {
        val vm = conLaLista()
        paginasCargadas.value = 3
        aLaVista(vm)
        avisos.subio("venue")
        irA(5_000)
        assertTrue(vm.hayVentasNuevas.value)
        contesta = deNuestraApi(403) // «Actualizar» → 403 overridable → PIN → el cajero cancela
        vm.manualRefresh()
        runCurrent()
        assertEquals(3, paginasCargadas.value)
        assertTrue("la automática se detiene, la píldora no", vm.hayVentasNuevas.value)
        irA(10 * 60_000L)
        assertEquals(0, automaticas)
        assertTrue(vm.hayVentasNuevas.value)
    }

    @Test fun `pildora - otro 4xx definitivo tampoco la apaga y una carga buena si`() = runTest(scheduler) {
        val vm = conLaLista()
        paginasCargadas.value = 3
        aLaVista(vm)
        avisos.subio("venue")
        irA(5_000)
        contesta = deNuestraApi(404)
        vm.manualRefresh()
        runCurrent()
        assertTrue(vm.hayVentasNuevas.value)

        contesta = null
        vm.manualRefresh()
        runCurrent()
        assertFalse(vm.hayVentasNuevas.value)
    }

    @Test fun `al volver a verse la escalera de reintentos vuelve a 5 s`() = runTest(scheduler) {
        contesta = sinRed
        val vm = crear()
        vm.autoRefresh() // a los 0 s
        val pantalla = aLaVista(vm)
        irA(65_001) // reintentos a los 5, 15, 35 y 65 s: el siguiente sería a los 95
        assertEquals(5, pedidas.size)
        pantalla.registro.currentState = Lifecycle.State.CREATED
        runCurrent()
        irA(80_000)
        pantalla.registro.currentState = Lifecycle.State.RESUMED // vuelve a la pantalla
        runCurrent()
        assertEquals("ya pasaron 5 s del último fallo: reintenta al volver, no a los 95", 6, pedidas.size)
    }

    @Test fun `pildora - un aviso durante la actualizacion queda pendiente y ya con una pagina se pide solo`() = runTest(scheduler) {
        val vm = conLaLista()
        paginasCargadas.value = 3
        aLaVista(vm)
        avisos.subio("venue")
        irA(5_000)
        val lenta = CompletableDeferred<Unit>()
        retenerCajero = lenta
        vm.manualRefresh() // tocar la píldora
        runCurrent()
        cobroSubio = true
        avisos.subio("venue") // llega otro a medio camino
        retenerCajero = null
        lenta.complete(Unit)
        runCurrent()
        assertEquals(1, paginasCargadas.value)

        irA(8_000)
        assertEquals(1, automaticas)
        assertEquals(listOf("pago-", "cobro"), vm.transactions.value.map { it.id })
        assertFalse(vm.hayVentasNuevas.value)
    }

    @Test fun `r2-B - con Cargar mas en camino no sale la automatica`() = runTest(scheduler) {
        val vm = conLaLista()
        aLaVista(vm)
        cargandoMas.value = true // la página 2 va en camino
        avisos.subio("venue")
        irA(10_000)
        assertEquals(0, automaticas)
        paginasCargadas.value = 2
        cargandoMas.value = false // llegó la página 2: ya hay dos páginas, toca la píldora
        irA(20_000)
        assertEquals(0, automaticas)
        assertTrue(vm.hayVentasNuevas.value)
    }
}
