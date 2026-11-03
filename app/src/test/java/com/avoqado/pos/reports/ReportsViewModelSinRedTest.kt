package com.avoqado.pos.reports

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.data.network.LecturaFallida
import com.avoqado.pos.core.data.sync.VueltaDeLaRed
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
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
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * D18 (3-nov): al pasar de «Hoy» a «1M» sin red se quedaban a la vista los números de «Hoy» bajo la pestaña «1M», y nada
 * recargaba al volver la red. Codex r1: la recarga salía a la vez que la cola de cobros (#2) y en cada parpadeo de la
 * bandera del servidor, forzando el gate y con la app en segundo plano (#4); y «Personalizado» dejaba el informe anterior
 * bajo las fechas nuevas (#5). Repositorio falso (lo que guarda y lo que falla), gate y vuelta de la red de verdad.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelSinRedTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private var ahora: Duration = Duration.ZERO
    private val informeGuardado = MutableStateFlow<InformeConsultado?>(null)
    private val falloGuardado = MutableStateFlow<FalloDeLectura?>(null)
    private val red = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val porSubir = MutableStateFlow(0)
    private val pedidas = mutableListOf<ConsultaDeInforme>()
    private var sinRed = false
    private var enCamino: CompletableDeferred<Unit>? = null

    /** El informe de una consulta: las ventas brutas dicen de qué periodo es. */
    private fun datosDe(consulta: ConsultaDeInforme) = ReportData(
        summary = SalesSummaryReport(
            grossSales = when (consulta.tipo) {
                "hours" -> 100.0
                "days" -> if (consulta.fin == null) 3000.0 else 777.0 // 1M, o el rango personalizado
                else -> 50000.0
            },
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
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.sucursalActiva() } returns "venue"
        coEvery { repository.loadReport(any(), any()) } coAnswers {
            val consulta = firstArg<ConsultaDeInforme>()
            pedidas += consulta
            enCamino?.await()
            if (sinRed) {
                falloGuardado.value = FalloDeLectura.SIN_RED
                Result.failure(LecturaFallida(FalloDeLectura.SIN_RED))
            } else {
                informeGuardado.value = InformeConsultado(consulta, datosDe(consulta))
                falloGuardado.value = null
                Result.success(Unit)
            }
        }
        val factory = mockk<RefreshGateFactory>()
        every { factory.create(any(), any()) } returns RefreshGate(clock = { ahora }, random = { 0.5 })
        val planManager = mockk<PlanManager>(relaxed = true)
        every { planManager.hasFeature("ADVANCED_REPORTS") } returns true
        val vuelta = VueltaDeLaRed(red, servidor, porSubir, esperaDeLaRutaMs = 2_000, esperaDeLaColaMs = 30_000)
        return ReportsViewModel(repository, planManager, factory, vuelta)
    }

    private fun estado(vm: ReportsViewModel) = estadoDelInforme(
        vm.consultaElegida.value, vm.informe.value, vm.isLoading.value, vm.ultimoFallo.value,
        eligiendoFechas = vm.showCustomDatePicker.value,
    )

    private fun brutasALaVista(vm: ReportsViewModel): Double? =
        (estado(vm) as? EstadoDelInforme.Listo)?.datos?.summary?.grossSales

    private fun TestScope.vigilar(vm: ReportsViewModel) {
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

    // MARK: - Al volver la red (Codex r1 #2 y #4)

    @Test
    fun `en Sin conexion, al volver la red se recarga solo`() = runTest(scheduler) {
        sinRed = true
        val vm = crear()
        vm.autoRefresh()
        assertEquals(EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm))
        ahora += 10.seconds // el gate ya no se enfría (en el aparato lo reabre la fábrica al volver la red)
        vigilar(vm)

        sinRed = false
        cortarYVolverLaRed()
        assertEquals("espera a que la ruta suba", 1, pedidas.size)

        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(2, pedidas.size)
        assertEquals(100.0, brutasALaVista(vm))
    }

    @Test
    fun `con un cobro sin subir, la recarga espera a que la cola se vacie`() = runTest(scheduler) {
        sinRed = true
        val vm = crear()
        vm.autoRefresh()
        ahora += 10.seconds
        porSubir.value = 1
        vigilar(vm)

        sinRed = false
        cortarYVolverLaRed()
        advanceTimeBy(10_000)
        assertEquals("el informe no se pide con el cobro todavía en la cola", 1, pedidas.size)

        porSubir.value = 0
        runCurrent()
        assertEquals(2, pedidas.size)
        assertEquals(100.0, brutasALaVista(vm))
    }

    @Test
    fun `un fallo parcial con el servidor alcanzable no provoca una segunda recarga automatica`() = runTest(scheduler) {
        sinRed = true
        val vm = crear()
        vm.autoRefresh()
        ahora += 10.seconds
        vigilar(vm)

        cortarYVolverLaRed() // la red vuelve, pero una parte del informe sigue sin respuesta
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, pedidas.size)

        // La salud sigue contestando: la bandera del servidor parpadea con el WiFi arriba.
        repeat(5) {
            servidor.value = false
            runCurrent()
            ahora += 70.seconds
            servidor.value = true
            runCurrent()
            advanceTimeBy(10_000)
        }

        assertEquals(2, pedidas.size)
    }

    @Test
    fun `la recarga al volver la red pasa por el gate - no fuerza su enfriamiento`() = runTest(scheduler) {
        sinRed = true
        val vm = crear()
        vm.autoRefresh() // falla: el gate se enfría y el reloj no avanza
        vigilar(vm)

        sinRed = false
        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(1, pedidas.size)
    }

    @Test
    fun `con Informes en segundo plano no vigila, y al verse otra vez si`() = runTest(scheduler) {
        sinRed = true
        val vm = crear()
        vm.autoRefresh()
        ahora += 10.seconds
        sinRed = false
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.CREATED
        backgroundScope.launch { vm.vigilarLaRedMientrasSeVe(pantalla) }
        runCurrent()

        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, pedidas.size)

        pantalla.registro.currentState = Lifecycle.State.RESUMED
        runCurrent()
        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, pedidas.size)
    }

    @Test
    fun `con el informe del periodo a la vista, volver la red no recarga por esto`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        ahora += 10.seconds
        vigilar(vm)

        cortarYVolverLaRed()
        advanceTimeBy(2_001)
        runCurrent()

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
