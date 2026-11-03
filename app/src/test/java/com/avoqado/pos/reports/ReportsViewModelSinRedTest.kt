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
 * D18 (3-nov): al pasar de «Hoy» a «1M» sin red se quedaban a la vista los números de «Hoy» bajo la pestaña «1M», y
 * nada recargaba al volver la red. Repositorio falso (lo que guarda y lo que falla), gate y red de verdad.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelSinRedTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val informeGuardado = MutableStateFlow<InformeConsultado?>(null)
    private val falloGuardado = MutableStateFlow<FalloDeLectura?>(null)
    private val red = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val pedidas = mutableListOf<ConsultaDeInforme>()
    private var sinRed = false
    private var enCamino: CompletableDeferred<Unit>? = null

    /** El informe de una consulta: las ventas brutas dicen de qué periodo es. */
    private fun datosDe(consulta: ConsultaDeInforme) = ReportData(
        summary = SalesSummaryReport(grossSales = if (consulta.tipo == "hours") 100.0 else 3000.0),
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
        every { factory.create(any(), any()) } returns RefreshGate(clock = { Duration.ZERO }, random = { 0.5 })
        val planManager = mockk<PlanManager>(relaxed = true)
        every { planManager.hasFeature("ADVANCED_REPORTS") } returns true
        val monitor = mockk<ConnectivityMonitor>(relaxed = true)
        every { monitor.isConnected } returns red
        every { monitor.isServerReachable } returns servidor
        return ReportsViewModel(repository, planManager, factory, monitor)
    }

    private fun estado(vm: ReportsViewModel) =
        estadoDelInforme(vm.consultaElegida.value, vm.informe.value, vm.isLoading.value, vm.ultimoFallo.value)

    private fun brutasALaVista(vm: ReportsViewModel): Double? =
        (estado(vm) as? EstadoDelInforme.Listo)?.datos?.summary?.grossSales

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

    @Test
    fun `en Sin conexion, al volver la red se recarga solo`() = runTest(scheduler) {
        sinRed = true
        val vm = crear()
        vm.autoRefresh()
        assertEquals(EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED), estado(vm))
        backgroundScope.launch { vm.vigilarLaRed() }
        runCurrent()

        red.value = false
        runCurrent()
        sinRed = false
        red.value = true
        runCurrent()
        assertEquals("espera a que la ruta suba", 1, pedidas.size)

        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(2, pedidas.size)
        assertEquals(100.0, brutasALaVista(vm))
    }

    @Test
    fun `con el informe del periodo a la vista, volver la red no recarga por esto`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        backgroundScope.launch { vm.vigilarLaRed() }
        runCurrent()

        servidor.value = false
        runCurrent()
        servidor.value = true
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(1, pedidas.size)
    }
}
