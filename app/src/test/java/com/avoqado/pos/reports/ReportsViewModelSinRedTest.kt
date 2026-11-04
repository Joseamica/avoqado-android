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
    private var cobroSinRedYaSubio = false
    private var enCamino: CompletableDeferred<Unit>? = null

    /** El informe de una consulta: las ventas brutas dicen de qué periodo es. */
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
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.sucursalActiva() } returns "venue"
        coEvery { repository.loadReport(any(), any()) } coAnswers {
            val consulta = firstArg<ConsultaDeInforme>()
            pedidas += consulta
            val conElCobro = cobroSinRedYaSubio // lo que el servidor ya tenía cuando salió la consulta
            enCamino?.await()
            if (sinRed) {
                falloGuardado.value = FalloDeLectura.SIN_RED
                Result.failure(LecturaFallida(FalloDeLectura.SIN_RED))
            } else {
                informeGuardado.value = InformeConsultado(consulta, datosDe(consulta, conElCobro))
                falloGuardado.value = null
                Result.success(Unit)
            }
        }
        val factory = mockk<RefreshGateFactory>()
        every { factory.create(any(), any()) } returns RefreshGate(clock = { ahora }, random = { 0.5 })
        val planManager = mockk<PlanManager>(relaxed = true)
        every { planManager.hasFeature("ADVANCED_REPORTS") } returns true
        return ReportsViewModel(repository, planManager, factory, ReglaDeRecarga(red, servidor, porSubir))
    }

    private fun estado(vm: ReportsViewModel) = estadoDelInforme(
        vm.consultaElegida.value, vm.informe.value, vm.isLoading.value, vm.ultimoFallo.value,
        eligiendoFechas = vm.showCustomDatePicker.value,
    )

    private fun brutasALaVista(vm: ReportsViewModel): Double? =
        (estado(vm) as? EstadoDelInforme.Listo)?.datos?.summary?.grossSales

    /** Informes a la vista (RESUMED), con la regla corriendo. */
    private fun TestScope.aLaVista(vm: ReportsViewModel): Pantalla {
        val pantalla = Pantalla()
        pantalla.registro.currentState = Lifecycle.State.RESUMED
        backgroundScope.launch { vm.vigilarLaRedMientrasSeVe(pantalla) }
        runCurrent()
        return pantalla
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

    private fun TestScope.servidorSeVaYVuelve() {
        servidor.value = false
        runCurrent()
        servidor.value = true
        dejarPasar()
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

    // MARK: - La regla única de recarga (Codex r2: H1, H2, H3)

    @Test
    fun `T2 - el WiFi que vuelve recarga el informe que se quedo sin red`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        val vm = crear()
        vm.autoRefresh() // sin red: «Sin conexión»
        ahora += 10.seconds // el enfriamiento de ese fallo ya pasó: aquí sólo cuenta el flanco del WiFi
        aLaVista(vm)

        sinRed = false
        red.value = true
        dejarPasar()

        assertEquals(2, pedidas.size)
        assertEquals(100.0, brutasALaVista(vm))
    }

    @Test
    fun `T2 - internet que vuelve sin que el WiFi se apague tambien recarga`() = runTest(scheduler) {
        sinRed = true
        servidor.value = false // el módem perdió internet; la red del aparato nunca bajó (WINDOWS igual)
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)

        sinRed = false
        servidor.value = true
        dejarPasar()

        assertEquals(2, pedidas.size)
        assertEquals(100.0, brutasALaVista(vm))
    }

    @Test
    fun `un fallo parcial con el servidor alcanzable hace UNA sola recarga, no mas`() = runTest(scheduler) {
        sinRed = true // una parte del informe sigue sin respuesta aunque la salud del servidor conteste
        red.value = false
        val vm = crear()
        vm.autoRefresh()
        ahora += 10.seconds // el enfriamiento de ese fallo ya pasó
        aLaVista(vm)

        red.value = true // T2
        dejarPasar()
        assertEquals(2, pedidas.size)

        // Su propio fallo apaga la bandera del servidor y el ping la vuelve a prender, una y otra vez.
        repeat(5) {
            ahora += 70.seconds // pasado cualquier enfriamiento: lo que frena es la regla, no el reloj
            servidorSeVaYVuelve()
        }

        assertEquals(2, pedidas.size)
    }

    @Test
    fun `T3 - una cola que tarda 60 s en subir recarga al vaciarse, aunque el informe ya se vea`() = runTest(scheduler) {
        porSubir.value = 1 // el cobro de 10 pesos en efectivo, hecho sin red
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)
        assertEquals(100.0, brutasALaVista(vm))

        advanceTimeBy(60_000)
        cobroSinRedYaSubio = true
        porSubir.value = 0 // la cola terminó de subir
        runCurrent()

        assertEquals("T3 recarga aunque el informe sea fresco", 2, pedidas.size)
        assertEquals(110.0, brutasALaVista(vm))
    }

    @Test
    fun `T3 - si la consulta de T2 iba en camino cuando subio la cola, al terminar se pide otra con el cobro`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        porSubir.value = 1 // el cobro de 10 pesos en efectivo, hecho sin red
        val vm = crear()
        vm.autoRefresh() // sin red: «Sin conexión»
        ahora += 10.seconds
        aLaVista(vm)

        sinRed = false
        val lenta = CompletableDeferred<Unit>()
        enCamino = lenta
        red.value = true // T2: la consulta sale antes de que suba la cola, y tarda
        dejarPasar()
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
    fun `una cola atorada no frena la recarga - T2 recarga y T3 vuelve a recargar cuando por fin se vacia`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        porSubir.value = 1 // un pendiente que no sube
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)

        sinRed = false
        red.value = true
        dejarPasar()
        assertEquals("T2 no espera a la cola", 2, pedidas.size)

        advanceTimeBy(10 * 60_000L)
        porSubir.value = 0
        runCurrent()
        assertEquals(3, pedidas.size)
    }

    @Test
    fun `T1 - pausar a media reconexion y volver con red recarga al volver`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        servidor.value = false
        val vm = crear()
        vm.autoRefresh()
        ahora += 10.seconds // el enfriamiento de ese fallo ya pasó
        val pantalla = aLaVista(vm)

        sinRed = false
        red.value = true // vuelve el WiFi, el servidor todavía no contesta
        dejarPasar()
        pantalla.registro.currentState = Lifecycle.State.CREATED // la app al fondo
        runCurrent()
        servidor.value = true // el servidor vuelve con Informes pausado
        dejarPasar()
        assertEquals(1, pedidas.size)

        pantalla.registro.currentState = Lifecycle.State.RESUMED // vuelve a Informes, con red
        runCurrent()

        assertEquals(2, pedidas.size)
        assertEquals(100.0, brutasALaVista(vm))
    }

    @Test
    fun `H3 - volver a Informes antes que el servidor y que falle la carga - al volver el servidor recarga`() = runTest(scheduler) {
        sinRed = true
        red.value = false
        servidor.value = false
        val vm = crear()
        vm.autoRefresh() // sin red: «Sin conexión»
        val pantalla = aLaVista(vm)

        red.value = true // vuelve el WiFi, el servidor todavía no contesta
        dejarPasar()
        pantalla.registro.currentState = Lifecycle.State.CREATED // la app al fondo
        runCurrent()
        pantalla.registro.currentState = Lifecycle.State.RESUMED // regresa antes de que vuelva el servidor
        runCurrent()
        ahora += 10.seconds
        vm.autoRefresh() // la carga al regresar falla y vuelve a enfriar el gate
        assertEquals(2, pedidas.size)

        sinRed = false
        servidor.value = true // ahora sí vuelve el servidor
        dejarPasar()

        assertEquals(3, pedidas.size)
        assertEquals(100.0, brutasALaVista(vm))
    }

    @Test
    fun `T1 respeta el enfriamiento del gate - no lo fuerza`() = runTest(scheduler) {
        sinRed = true
        val vm = crear()
        vm.autoRefresh() // falla: el gate se enfría y el reloj no avanza

        sinRed = false
        aLaVista(vm) // vuelve a Informes con red

        assertEquals(1, pedidas.size)
    }

    @Test
    fun `con Informes en segundo plano no hay ninguna recarga`() = runTest(scheduler) {
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
        dejarPasar()
        servidorSeVaYVuelve()
        porSubir.value = 0
        runCurrent()
        advanceTimeBy(60_000)

        assertEquals(1, pedidas.size)
    }

    @Test
    fun `con el informe del periodo a la vista, volver la red no recarga por esto`() = runTest(scheduler) {
        val vm = crear()
        vm.autoRefresh()
        aLaVista(vm)

        wifiSeVaYVuelve()
        servidorSeVaYVuelve()

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
