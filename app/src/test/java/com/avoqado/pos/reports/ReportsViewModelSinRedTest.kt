package com.avoqado.pos.reports

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.data.network.LecturaFallida
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
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
 * D18 (3-nov): al pasar de «Hoy» a «1M» sin red se quedaban a la vista los números de «Hoy» bajo la pestaña «1M». Codex r1:
 * «Personalizado» dejaba el informe anterior bajo las fechas nuevas (#5). La recarga automática al volver la red se quitó
 * en la ronda 5 (cuatro rondas de Codex con bloqueantes de la misma familia): al volver la conexión no recarga nada solo.
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
    private var enCamino: CompletableDeferred<Unit>? = null

    // La red del aparato y la bandera del servidor.
    private val red = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val monitor = mockk<ConnectivityMonitor>(relaxed = true).also {
        every { it.isConnected } returns red
        every { it.isServerReachable } returns servidor
    }

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

    private fun crear(factory: RefreshGateFactory? = null): ReportsViewModel {
        val repository = mockk<ReportsRepository>(relaxed = true)
        every { repository.informe } returns informeGuardado
        every { repository.ultimoFallo } returns falloGuardado
        every { repository.isLoading } returns cargando
        every { repository.sucursalActiva() } returns "venue"
        coEvery { repository.loadReport(any(), any()) } coAnswers {
            val consulta = firstArg<ConsultaDeInforme>()
            pedidas += consulta
            cargando.value = true
            try {
                enCamino?.await()
                if (sinRed) {
                    falloGuardado.value = FalloDeLectura.SIN_RED
                    Result.failure(LecturaFallida(FalloDeLectura.SIN_RED))
                } else {
                    informeGuardado.value = InformeConsultado(consulta, datosDe(consulta))
                    falloGuardado.value = null
                    Result.success(Unit)
                }
            } finally {
                cargando.value = false
            }
        }
        val gateFactory = factory ?: mockk<RefreshGateFactory>().also {
            every { it.create(any(), any()) } returns RefreshGate(clock = { ahora }, random = { 0.5 })
        }
        val planManager = mockk<PlanManager>(relaxed = true)
        every { planManager.hasFeature("ADVANCED_REPORTS") } returns true
        return ReportsViewModel(repository, planManager, gateFactory)
    }

    private fun estado(vm: ReportsViewModel) = estadoDelInforme(
        vm.consultaElegida.value, vm.informe.value, vm.isLoading.value, vm.ultimoFallo.value,
        eligiendoFechas = vm.showCustomDatePicker.value,
    )

    private fun brutasALaVista(vm: ReportsViewModel): Double? =
        (estado(vm) as? EstadoDelInforme.Listo)?.datos?.summary?.grossSales

    // MARK: - Al volver la red, nada recarga solo (ronda 5)

    @Test
    fun `al volver la red nada recarga solo, y Reintentar si recarga`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        val vm = crear(factory = RefreshGateFactory(monitor)) // la fábrica de verdad, que sí mira la red
        vm.autoRefresh() // sin red: «Sin conexión»
        assertEquals(EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm))

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
        assertEquals(EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm))

        vm.manualRefresh() // «Reintentar»
        runCurrent()

        assertEquals(2, pedidas.size)
        assertEquals(100.0, brutasALaVista(vm))
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
