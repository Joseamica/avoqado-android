package com.avoqado.pos.reports

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.data.network.LecturaFallida
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.domain.refresh.ReglaDeRecarga
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reports.data.ReportsRepository
import com.avoqado.pos.reports.data.model.ConsultaDeInforme
import com.avoqado.pos.reports.data.model.InformeConsultado
import com.avoqado.pos.reports.data.model.ReportData
import com.avoqado.pos.reports.data.model.ReportPeriod
import com.avoqado.pos.reports.data.model.SalesSummaryReport
import com.avoqado.pos.reports.presentation.EstadoDelInforme
import com.avoqado.pos.reports.presentation.ReportsViewModel
import com.avoqado.pos.reports.presentation.estadoDelInforme
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
 * D18 (3-nov): al pasar de «Hoy» a «1M» sin red se quedaban a la vista los números de «Hoy» bajo la pestaña «1M», y nada
 * recargaba al volver la red. Codex r1: «Personalizado» dejaba el informe anterior bajo las fechas nuevas (#5). Codex r2 y
 * r3 tumbaron la recarga por flancos (H1–H3, N1–N3, y N2 es de Informes): la regla de la ronda 4 es por NIVEL, con
 * reintentos de 5, 10, 20 y 30 s, sólo con la pantalla a la vista y sin la bandera del servidor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelSinRedTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val ahora: Duration = Duration.ZERO
    private val informeGuardado = MutableStateFlow<InformeConsultado?>(null)
    private val falloGuardado = MutableStateFlow<FalloDeLectura?>(null)
    private val cargando = MutableStateFlow(false)
    private val pedidas = mutableListOf<ConsultaDeInforme>()
    private var sinRed = false
    private var cobroSinRedYaSubio = false
    private var enCamino: CompletableDeferred<Unit>? = null

    /** Lo que pasa a mitad de una carga (N2: una de sus consultas falla y otra contesta). */
    private var alCargar: (() -> Unit)? = null

    // La red del aparato, la bandera del servidor (la regla no la usa: aquí parpadea como en la vida real) y la cola.
    private val red = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val porSubir = MutableStateFlow(0)
    private val monitor = mockk<ConnectivityMonitor>(relaxed = true).also {
        every { it.isConnected } returns red
        every { it.isServerReachable } returns servidor
    }

    /** El informe de una consulta: las ventas brutas dicen de qué periodo es, y si ya trae el cobro hecho sin red. */
    private fun datosDe(consulta: ConsultaDeInforme, conElCobro: Boolean) = ReportData(
        summary = SalesSummaryReport(
            grossSales = when (consulta.tipo) {
                "hours" -> 100.0
                "days" -> if (consulta.fin == null) 3000.0 else 777.0 // 1M, o el rango personalizado
                else -> 50000.0
            } + if (conElCobro) 10.0 else 0.0,
        ),
        paymentMethods = emptyList(),
        hourlySales = emptyList(),
        periodSales = emptyList(),
        topProducts = emptyList(),
        categories = emptyList(),
    )

    private fun crear(): ReportsViewModel {
        val repository = mockk<ReportsRepository>(relaxed = true)
        every { repository.informe } returns informeGuardado
        every { repository.ultimoFallo } returns falloGuardado
        every { repository.isLoading } returns cargando
        every { repository.sucursalActiva() } returns "venue"
        coEvery { repository.loadReport(any(), any()) } coAnswers {
            val consulta = firstArg<ConsultaDeInforme>()
            pedidas += consulta
            val conElCobro = cobroSinRedYaSubio // lo que el servidor ya tenía cuando salió la consulta
            cargando.value = true
            try {
                enCamino?.await()
                alCargar?.invoke()
                if (sinRed) {
                    falloGuardado.value = FalloDeLectura.SIN_RED
                    Result.failure(LecturaFallida(FalloDeLectura.SIN_RED))
                } else {
                    informeGuardado.value = InformeConsultado(consulta, datosDe(consulta, conElCobro))
                    falloGuardado.value = null
                    Result.success(Unit)
                }
            } finally {
                cargando.value = false
            }
        }
        val factory = mockk<RefreshGateFactory>()
        every { factory.create(any(), any()) } returns RefreshGate(clock = { ahora }, random = { 0.5 })
        val planManager = mockk<PlanManager>(relaxed = true)
        every { planManager.hasFeature("ADVANCED_REPORTS") } returns true
        return ReportsViewModel(repository, planManager, factory, ReglaDeRecarga(monitor, porSubir, reloj = { scheduler.currentTime }))
    }

    private fun estado(vm: ReportsViewModel) = estadoDelInforme(
        vm.consultaElegida.value, vm.informe.value, vm.isLoading.value, vm.ultimoFallo.value,
        eligiendoFechas = vm.showCustomDatePicker.value,
    )

    private fun brutasALaVista(vm: ReportsViewModel): Double? =
        (estado(vm) as? EstadoDelInforme.Listo)?.datos?.summary?.grossSales

    private class Pantalla : LifecycleOwner {
        val registro = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registro
    }

    /** Informes a la vista (RESUMED), con la regla corriendo. */
    private fun TestScope.aLaVista(vm: ReportsViewModel): Pantalla {
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.RESUMED
        backgroundScope.launch { vm.vigilarLaRedMientrasSeVe(pantalla) }
        runCurrent()
        return pantalla
    }

    private fun TestScope.wifiSeVaYVuelve() {
        red.value = false
        runCurrent()
        red.value = true
        runCurrent()
    }

    private fun TestScope.servidorParpadea() {
        servidor.value = false
        runCurrent()
        servidor.value = true
        runCurrent()
    }

    private fun TestScope.irA(ms: Long) {
        advanceTimeBy(ms - scheduler.currentTime)
        runCurrent()
    }

    // MARK: - El informe va ligado a su periodo

    @Test
    fun `de Hoy a 1M sin red no deja los numeros de Hoy bajo 1M`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        assertEquals(100.0, brutasALaVista(vm))

        sinRed = true
        vm.selectPeriod(ReportPeriod.THIS_MONTH)

        assertEquals("days", pedidas.last().tipo) // sí se intentó 1M
        assertEquals(EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm))
    }

    @Test
    fun `sin red, volver a Hoy ensena el informe de Hoy que ya estaba`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        sinRed = true
        vm.selectPeriod(ReportPeriod.THIS_MONTH) // falla y deja el gate enfriándose

        vm.selectPeriod(ReportPeriod.TODAY) // el gate no vuelve a pedir; el de Hoy ya está

        assertEquals(100.0, brutasALaVista(vm))
    }

    @Test
    fun `sin red, un periodo que el gate todavia no pide dice Sin conexion y no ensena otro informe`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        sinRed = true
        vm.selectPeriod(ReportPeriod.THIS_MONTH) // falla y deja el gate enfriándose

        vm.selectPeriod(ReportPeriod.THIS_YEAR) // el gate no pide

        assertEquals("months", vm.consultaElegida.value.tipo)
        assertEquals(EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm))
    }

    @Test
    fun `si iba en camino el informe de otro periodo, al terminar se pide el del periodo elegido`() = runTest(scheduler) {
        val vm = crear()
        val hoyEnCamino = CompletableDeferred<Unit>()
        enCamino = hoyEnCamino
        vm.autoRefresh() // Hoy, en camino
        vm.selectPeriod(ReportPeriod.THIS_MONTH) // el gate se une a la consulta de Hoy

        enCamino = null
        hoyEnCamino.complete(Unit)
        runCurrent()

        assertEquals(listOf("hours", "days"), pedidas.map { it.tipo })
        assertEquals(3000.0, brutasALaVista(vm))
    }

    // MARK: - «No se cargó»: se recarga en cuanto hay red, con espera creciente

    @Test
    fun `vuelve el WiFi y el informe que no se cargo se recarga solo`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        val vm = crear()
        vm.autoRefresh() // sin red: «Sin conexión»
        aLaVista(vm)
        irA(20_000)
        assertEquals("sin red no se intenta", 1, pedidas.size)

        sinRed = false
        red.value = true
        runCurrent()

        assertEquals(2, pedidas.size)
        assertEquals(100.0, brutasALaVista(vm))
    }

    @Test
    fun `N2 - el servidor vuelve a mitad de una carga que termina fallando - el informe se recupera solo`() = runTest(scheduler) {
        val vm = crear()
        aLaVista(vm) // todavía nada
        sinRed = true
        alCargar = { // una de las consultas del informe falla y apaga la bandera; otra contesta 200 y la prende
            servidor.value = false
            servidor.value = true
        }
        vm.autoRefresh()
        runCurrent()
        alCargar = null
        assertEquals(EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm)) // con el servidor alcanzable
        sinRed = false

        irA(6_000)

        assertEquals(100.0, brutasALaVista(vm))
        assertEquals(2, pedidas.size)
    }

    @Test
    fun `internet que vuelve sin que el WiFi se apague - el informe se recupera solo`() = runTest(scheduler) {
        sinRed = true
        servidor.value = false // el módem perdió internet; la red del aparato nunca bajó (WINDOWS igual)
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)

        irA(7_000)
        sinRed = false
        servidor.value = true // vuelve internet
        irA(40_000)

        assertEquals(100.0, brutasALaVista(vm))
        assertTrue("sin tormenta: ${pedidas.size} cargas", pedidas.size <= 3)
    }

    @Test
    fun `fallo parcial con el servidor alcanzable - espera 5 s y luego 10 s, sin cargas en medio`() = runTest(scheduler) {
        sinRed = true // una parte del informe no contesta aunque la salud del servidor sí
        val vm = crear()
        vm.autoRefresh() // a los 0 s: falla
        aLaVista(vm)

        for ((en, cargas) in listOf(4_999L to 1, 5_001L to 2, 14_999L to 2, 15_001L to 3)) {
            irA(en)
            assertEquals("a los $en ms", cargas, pedidas.size)
        }
    }

    @Test
    fun `pausar a media reconexion y volver - recalcula y recarga solo`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        val vm = crear()
        vm.autoRefresh() // sin red
        val pantalla = aLaVista(vm)

        irA(10_000)
        red.value = true // vuelve el WiFi, pero el servidor todavía no contesta
        runCurrent()
        assertEquals(2, pedidas.size)
        pantalla.registro.currentState = Lifecycle.State.CREATED // la app al fondo
        runCurrent()
        irA(60_000)
        assertEquals("en el fondo no carga", 2, pedidas.size)

        pantalla.registro.currentState = Lifecycle.State.RESUMED // regresa antes de que vuelva el servidor
        runCurrent()
        assertEquals("recalcula al volver: hace falta y ya pasó la espera", 3, pedidas.size)
        sinRed = false // ahora sí vuelve el servidor
        irA(80_001)

        assertEquals(100.0, brutasALaVista(vm))
        assertEquals(4, pedidas.size)
    }

    @Test
    fun `con Informes en segundo plano no hay ninguna carga`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        porSubir.value = 1
        val vm = crear()
        vm.autoRefresh()
        sinRed = false
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.CREATED
        backgroundScope.launch { vm.vigilarLaRedMientrasSeVe(pantalla) }
        runCurrent()

        red.value = true
        runCurrent()
        servidorParpadea()
        porSubir.value = 0
        runCurrent()
        irA(10 * 60_000L)

        assertEquals(1, pedidas.size)
    }

    // MARK: - El informe se pidió con cobros por subir: se recarga al vaciarse la cola

    @Test
    fun `la cola tarda 60 s en subir - al vaciarse recarga con el cobro aunque el informe se vea, y despues ya no`() = runTest(scheduler) {
        porSubir.value = 1 // el cobro de 10 pesos en efectivo, hecho sin red
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)
        assertEquals(100.0, brutasALaVista(vm))

        irA(60_000)
        assertEquals(1, pedidas.size)
        cobroSinRedYaSubio = true
        porSubir.value = 0 // la cola terminó de subir
        runCurrent()
        assertEquals(110.0, brutasALaVista(vm))

        wifiSeVaYVuelve()
        irA(15 * 60_000L)
        assertEquals("una carga buena con la cola en 0 apaga la necesidad", 2, pedidas.size)
    }

    @Test
    fun `cola atorada - con el informe a la vista no recarga mientras no suba, y recarga al vaciarse`() = runTest(scheduler) {
        porSubir.value = 1 // un pendiente que no sube
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)

        irA(10 * 60_000L)
        assertEquals("sin tormenta", 1, pedidas.size)

        porSubir.value = 0
        runCurrent()
        assertEquals(2, pedidas.size)
    }

    @Test
    fun `si la consulta iba en camino cuando subio la cola, al terminar se pide otra con el cobro`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        porSubir.value = 1
        val vm = crear()
        vm.autoRefresh() // sin red: «Sin conexión»
        aLaVista(vm)
        irA(10_000)

        sinRed = false
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

        assertEquals(3, pedidas.size)
        assertEquals(110.0, brutasALaVista(vm))
    }

    @Test
    fun `con el informe del periodo y nada en la cola, volver la red no recarga`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)

        wifiSeVaYVuelve()
        servidorParpadea()
        irA(10 * 60_000L)

        assertEquals(1, pedidas.size)
    }

    // MARK: - Personalizado (Codex r1 #5)

    @Test
    fun `al elegir Personalizado no queda a la vista el informe del periodo anterior`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        vm.selectPeriod(ReportPeriod.THIS_MONTH)
        assertEquals(3000.0, brutasALaVista(vm))

        vm.selectPeriod(ReportPeriod.CUSTOM)
        vm.setCustomStartDate(1_793_000_000_000L)
        vm.setCustomEndDate(1_793_500_000_000L)

        assertEquals(EstadoDelInforme.EligiendoFechas, estado(vm))
        assertEquals(2, pedidas.size) // elegir fechas no pide nada
    }

    @Test
    fun `cerrar el selector sin aplicar vuelve al periodo que tenia con su informe`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        vm.selectPeriod(ReportPeriod.THIS_MONTH)
        vm.selectPeriod(ReportPeriod.CUSTOM)
        vm.setCustomStartDate(1_793_000_000_000L)

        vm.cancelCustomDates()

        assertEquals(ReportPeriod.THIS_MONTH, vm.selectedPeriod.value)
        assertEquals(3000.0, brutasALaVista(vm))
        assertEquals(2, pedidas.size)
    }

    @Test
    fun `con el selector abierto, recargar pide el periodo que se ve y no las fechas sin aplicar`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        vm.selectPeriod(ReportPeriod.THIS_MONTH)
        vm.selectPeriod(ReportPeriod.CUSTOM)
        vm.setCustomStartDate(1_793_000_000_000L)

        vm.manualRefresh() // el gesto de jalar con el selector abierto

        assertEquals(listOf("hours", "days", "days"), pedidas.map { it.tipo })
        assertEquals(null, pedidas.last().fin) // 1M «hasta ahora», no el rango sin aplicar
        vm.cancelCustomDates()
        assertEquals(3000.0, brutasALaVista(vm))
    }

    @Test
    fun `Aplicar pide el rango personalizado y lo ensena`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        vm.selectPeriod(ReportPeriod.CUSTOM)
        vm.setCustomStartDate(1_793_000_000_000L)
        vm.setCustomEndDate(1_793_500_000_000L)

        vm.applyCustomDates()

        assertEquals(ReportPeriod.CUSTOM, vm.selectedPeriod.value)
        assertEquals("days", pedidas.last().tipo)
        assertEquals(777.0, brutasALaVista(vm))
    }
}
