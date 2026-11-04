package com.avoqado.pos.reports

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.network.CargaDeFondo
import com.avoqado.pos.core.data.network.ClaseDeFallo
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.data.network.LecturaFallida
import com.avoqado.pos.core.data.sync.AvisosDeVentasSubidas
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.domain.refresh.RelojDeRecarga
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.onlineorders.data.VentanaALaVista
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration

/** D22 (3-nov): Informes se recarga solo al volver la red o al subir un cobro (diseños r2–r4). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelRecargaSolaTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val ahora: Duration = Duration.ZERO
    private val informeGuardado = MutableStateFlow<InformeConsultado?>(null)
    private val falloGuardado = MutableStateFlow<FalloDeLectura?>(null)
    private val cargando = MutableStateFlow(false)
    private var contesta: (() -> Throwable)? = null
    private var cobroSubio = false
    private var retener: CompletableDeferred<Unit>? = null
    private val deFondo = mutableListOf<Boolean>()

    private val red = MutableStateFlow(true)
    private val monitor = mockk<ConnectivityMonitor>(relaxed = true).also { every { it.isConnected } returns red }
    private val avisos = AvisosDeVentasSubidas()
    private val reloj = object : RelojDeRecarga() {
        override fun ahora(): Long = scheduler.currentTime
    }

    private val sinRed: () -> Throwable = { LecturaFallida(FalloDeLectura.SIN_RED) }

    private fun crear(): ReportsViewModel {
        val repository = mockk<ReportsRepository>(relaxed = true)
        every { repository.informe } returns informeGuardado
        every { repository.ultimoFallo } returns falloGuardado
        every { repository.isLoading } returns cargando
        every { repository.sucursalActiva() } returns "venue"
        coEvery { repository.loadReport(any(), any()) } coAnswers {
            val consulta = firstArg<ConsultaDeInforme>()
            deFondo += CargaDeFondo.activa()
            val conElCobro = cobroSubio
            cargando.value = true
            try {
                retener?.await()
                val error = contesta?.invoke()
                if (error != null) {
                    falloGuardado.value = (error as? LecturaFallida)?.tipo ?: FalloDeLectura.SIN_RED
                    Result.failure(error)
                } else {
                    informeGuardado.value = InformeConsultado(consulta, ReportData(
                        summary = SalesSummaryReport(grossSales = 100.0 + if (conElCobro) 10.0 else 0.0),
                        paymentMethods = emptyList(), hourlySales = emptyList(), periodSales = emptyList(),
                        topProducts = emptyList(), categories = emptyList(),
                    ))
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
        return ReportsViewModel(repository, planManager, factory, monitor, avisos, reloj)
    }

    @After fun ventanaALaVista() = VentanaALaVista.publicar(true)

    private fun brutas(vm: ReportsViewModel): Double? = (estadoDelInforme(
        vm.consultaElegida.value, vm.informe.value, vm.isLoading.value, vm.ultimoFallo.value,
        eligiendoFechas = vm.showCustomDatePicker.value,
    ) as? EstadoDelInforme.Listo)?.datos?.summary?.grossSales

    private class Pantalla : LifecycleOwner {
        val registro = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registro
    }

    private fun TestScope.aLaVista(vm: ReportsViewModel): Pantalla {
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.RESUMED
        backgroundScope.launch { vm.recargarSolaMientrasSeVe(pantalla) }
        runCurrent()
        return pantalla
    }

    private fun TestScope.irA(ms: Long) {
        advanceTimeBy(ms - scheduler.currentTime)
        runCurrent()
    }

    private val automaticas get() = deFondo.count { it }

    @Test fun `N2 - el servidor vuelve a mitad de una carga que termina fallando - reintenta a los 5 s`() = runTest(scheduler) {
        val vm = crear()
        aLaVista(vm)
        contesta = sinRed // una de las cuatro consultas no contesta; otra sí (la bandera del servidor ya no importa)
        vm.autoRefresh()
        runCurrent()
        contesta = null

        irA(4_999)
        assertEquals(0, automaticas)
        irA(5_001)

        assertEquals(1, automaticas)
        assertEquals(100.0, brutas(vm))
    }

    @Test fun `un cobro que sube recarga el informe a la vista, de fondo`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)
        cobroSubio = true
        avisos.subio("venue")
        irA(2_001)
        assertEquals(listOf(false, true), deFondo)
        assertEquals(110.0, brutas(vm))
    }

    @Test fun `con el selector de fechas abierto no se pide nada y al cerrarlo si`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)
        vm.selectPeriod(ReportPeriod.CUSTOM) // eligiendo fechas
        avisos.subio("venue")
        irA(10 * 60_000L)
        assertEquals(0, automaticas)

        vm.cancelCustomDates()
        irA(10 * 60_000L + 2_001)
        assertEquals(1, automaticas)
    }

    @Test fun `r4-3 - una denegacion propia la detiene y un aviso nuevo pide de fondo una sola vez`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)
        contesta = { LecturaFallida(FalloDeLectura.SERVIDOR, clases = listOf(ClaseDeFallo.DENEGACION_PROPIA, ClaseDeFallo.REINTENTABLE)) }
        vm.manualRefresh() // 403 propio + 5xx: el PIN se canceló
        runCurrent()
        irA(10 * 60_000L)
        assertEquals(0, automaticas)

        avisos.subio("venue")
        irA(20 * 60_000L)
        assertEquals(1, automaticas)
    }

    @Test fun `r4-4 - WINDOWS minimizado no consulta`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        VentanaALaVista.publicar(false)
        aLaVista(vm)
        avisos.subio("venue")
        contesta = sinRed
        irA(10 * 60_000L)
        assertEquals(0, automaticas)
    }

    @Test fun `minimizar corta la automatica en camino`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)
        retener = CompletableDeferred()
        avisos.subio("venue")
        irA(1_001)
        assertEquals(1, automaticas)
        VentanaALaVista.publicar(false)
        runCurrent()
        assertEquals(false, vm.isLoading.value)
    }

    @Test fun `con Informes en segundo plano no hay ninguna carga`() = runTest(scheduler) {
        contesta = sinRed
        val vm = crear()
        vm.autoRefresh()
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.CREATED
        backgroundScope.launch { vm.recargarSolaMientrasSeVe(pantalla) }
        runCurrent()
        avisos.subio("venue")
        irA(10 * 60_000L)
        assertEquals(listOf(false), deFondo)
    }
}
