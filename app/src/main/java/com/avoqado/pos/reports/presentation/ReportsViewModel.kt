package com.avoqado.pos.reports.presentation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import com.avoqado.pos.core.data.network.CargaDeFondo
import com.avoqado.pos.core.data.network.ClaseDeFallo
import com.avoqado.pos.core.data.sync.AvisosDeVentasSubidas
import com.avoqado.pos.core.domain.refresh.Necesidad
import com.avoqado.pos.core.domain.refresh.RecargaSola
import com.avoqado.pos.core.domain.refresh.RelojDeRecarga
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.onlineorders.data.VentanaALaVista
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshOutcome
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.reports.data.ReportsRepository
import com.avoqado.pos.reports.data.model.ConsultaDeInforme
import com.avoqado.pos.reports.data.model.ReportPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val repository: ReportsRepository,
    private val planManager: PlanManager,
    refreshGateFactory: RefreshGateFactory,
    /** D22: la red del aparato para la recarga automática. `null` sólo en pruebas que no la usan: no se recarga sola. */
    private val connectivityMonitor: ConnectivityMonitor? = null,
    private val avisosDeVentas: AvisosDeVentasSubidas = AvisosDeVentasSubidas(),
    private val reloj: RelojDeRecarga = RelojDeRecarga(),
) : ViewModel() {

    // MARK: - Refresco (spec estrategia-de-refresco)

    private val gate = refreshGateFactory.create(viewModelScope)

    /** D22: si al informe le hace falta recargar (un fallo reintentable o un cobro de la sucursal que ya subió). */
    private val necesidad = MutableStateFlow(Necesidad())

    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    // MARK: - Repository State (forwarded)

    /** El último informe bueno y la consulta que lo trajo (D18): sólo se enseña bajo [consultaElegida]. */
    val informe = repository.informe
    val isLoading = repository.isLoading

    /** D18: por qué falló la última carga (`null` si salió bien). */
    val ultimoFallo = repository.ultimoFallo

    // MARK: - Plan gating (ADVANCED_REPORTS, Pro)

    /**
     * Free venues see a TODAY-only summary (mirrors the dashboard rule);
     * Pro+ unlocks the full date range. Fail-open when the plan is unknown.
     */
    val hasAdvancedReports: Boolean
        get() = planManager.hasFeature("ADVANCED_REPORTS")

    /** Tier label required for historical reports ("Pro"). */
    val reportsTierLabel: String
        get() = planManager.requiredTierLabel("ADVANCED_REPORTS") ?: "Pro"

    // MARK: - Period Selection

    private val _selectedPeriod = MutableStateFlow(ReportPeriod.TODAY)
    val selectedPeriod: StateFlow<ReportPeriod> = _selectedPeriod.asStateFlow()

    // MARK: - Custom Date Range

    private val _customStartDate = MutableStateFlow<Long>(System.currentTimeMillis())
    val customStartDate: StateFlow<Long> = _customStartDate.asStateFlow()

    private val _customEndDate = MutableStateFlow<Long>(System.currentTimeMillis())
    val customEndDate: StateFlow<Long> = _customEndDate.asStateFlow()

    private val _showCustomDatePicker = MutableStateFlow(false)
    val showCustomDatePicker: StateFlow<Boolean> = _showCustomDatePicker.asStateFlow()

    /**
     * D18 (Codex r1 #5): el periodo cuyo informe se pide y se enseña. Es el de la pestaña marcada, salvo con el selector
     * de fechas abierto: ahí la pestaña es «Personalizado» y esto sigue siendo el de antes hasta «Aplicar» (o vuelve a él
     * con «Cancelar»). Las fechas del selector tampoco cuentan hasta «Aplicar».
     */
    private var periodoAplicado = ReportPeriod.TODAY
    private var fechasAplicadas = _customStartDate.value to _customEndDate.value

    // MARK: - Detail Expansion

    private val _showDetailedSummary = MutableStateFlow(false)
    val showDetailedSummary: StateFlow<Boolean> = _showDetailedSummary.asStateFlow()

    // MARK: - Consulta elegida (D18)

    private val _consultaElegida = MutableStateFlow(consultaYFin().first)

    /**
     * La consulta del periodo elegido: la pantalla sólo enseña un informe de ESTA consulta. Cambia al elegir el periodo
     * (aunque el gate no vuelva a pedir, p. ej. enfriándose tras un fallo sin red) y en cada consulta.
     */
    val consultaElegida: StateFlow<ConsultaDeInforme> = _consultaElegida.asStateFlow()

    /** La consulta de la última petición que salió (para saber si la que se unió en vuelo era de otro periodo). */
    private var ultimaPedida: ConsultaDeInforme? = null

    // MARK: - Init
    // La carga inicial la dispara la UI vía el gate (autoRefresh).

    // MARK: - Public Actions

    fun selectPeriod(period: ReportPeriod) {
        // Plan gate: Free venues are clamped to TODAY — historical ranges are
        // part of the Pro plan. The UI shows the locked pills as a teaser, and
        // this guard makes the clamp authoritative even for stale UI.
        if (!hasAdvancedReports && period != ReportPeriod.TODAY) {
            return
        }
        _selectedPeriod.value = period
        if (period == ReportPeriod.CUSTOM) {
            // Se eligen fechas: el informe que se ve debajo no es de ellas (lo esconde la pantalla) y nada se pide aún.
            _showCustomDatePicker.value = true
        } else {
            _showCustomDatePicker.value = false
            periodoAplicado = period
            // D18: el periodo nuevo se elige YA, aunque el gate no vuelva a pedir: el informe de otro periodo no se
            // queda a la vista bajo esta pestaña.
            _consultaElegida.value = consultaYFin().first
            // Otro periodo = otra identidad (spec §4.4): invalida el TTL y re-pide.
            invalidateAndRefresh()
        }
    }

    fun setCustomStartDate(millis: Long) {
        _customStartDate.value = millis
    }

    fun setCustomEndDate(millis: Long) {
        _customEndDate.value = millis
    }

    fun applyCustomDates() {
        _showCustomDatePicker.value = false
        _selectedPeriod.value = ReportPeriod.CUSTOM
        periodoAplicado = ReportPeriod.CUSTOM
        fechasAplicadas = _customStartDate.value to _customEndDate.value
        _consultaElegida.value = consultaYFin().first
        // Otras fechas = otra identidad (spec §4.4).
        invalidateAndRefresh()
    }

    /**
     * D18 (Codex r1 #5): cerrar el selector sin aplicar vuelve al periodo que tenía, con su informe (nada se pidió con las
     * fechas sin aplicar), y las fechas del selector vuelven a las aplicadas.
     */
    fun cancelCustomDates() {
        _showCustomDatePicker.value = false
        _selectedPeriod.value = periodoAplicado
        _customStartDate.value = fechasAplicadas.first
        _customEndDate.value = fechasAplicadas.second
    }

    fun toggleDetailedSummary() {
        _showDetailedSummary.value = !_showDetailedSummary.value
    }

    /** Contrato §4.2: sin launch interno; el gate decide y sella el reloj. */
    suspend fun refreshNow(): Result<Unit> = cargarInforme(enFondo = false)

    /**
     * Toda carga del informe (la del gate y la automática). D22: la instantánea se toma ANTES de consultar, y el retorno sólo
     * atiende esa instantánea (diseño r2 §B): un cobro que sube durante la carga sigue pendiente.
     */
    private suspend fun cargarInforme(enFondo: Boolean): Result<Unit> {
        val (consulta, hasta) = consultaYFin()
        _consultaElegida.value = consulta
        ultimaPedida = consulta
        val inst = necesidad.value.instantanea(consulta.sucursal, avisosDeVentas.de(consulta.sucursal))
        val resultado = if (enFondo) withContext(CargaDeFondo.enElContexto) { repository.loadReport(consulta, hasta) }
        else repository.loadReport(consulta, hasta)
        necesidad.update {
            val error = resultado.exceptionOrNull()
            if (error == null) it.alTerminarBien(inst)
            else it.alFallar(inst, ClaseDeFallo.de(error), reloj.ahora(), avisosAhora = avisosDeVentas.de(inst.sucursal))
        }
        return resultado
    }

    /**
     * D22: mientras Informes se ve (RESUMED y, en WINDOWS, la ventana sin minimizar), lo vuelve a pedir solo cuando hace
     * falta (`RecargaSola`). Esa carga corre por su cuenta, de fondo y fuera del gate (nadie se une a ella); dejar de verse
     * la corta con su HTTP. Lo que pide el cajero no pasa por aquí.
     */
    internal suspend fun recargarSolaMientrasSeVe(owner: LifecycleOwner) {
        val monitor = connectivityMonitor ?: return
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            VentanaALaVista.aLaVista.collectLatest { seVe ->
                if (seVe) {
                    necesidad.update { it.alVolverAVerse() } // la escalera de reintentos vuelve a 5 s al volver a verse
                    RecargaSola.atender(
                        necesidad = necesidad,
                        avisos = avisosDeVentas,
                        conRed = monitor.isConnected,
                        otras = combine(repository.isLoading, _showCustomDatePicker) { cargando, eligiendo -> cargando to eligiendo },
                        sucursal = { repository.sucursalActiva()?.takeIf { it.isNotEmpty() } },
                        // Eligiendo fechas no se pide nada: lo de debajo no es de ellas (D18 #5).
                        ocupada = { repository.isLoading.value || _showCustomDatePicker.value },
                        puedeSola = { true },
                        reloj = reloj,
                        recargar = {
                            // r2 §D: con red de vuelta, un enfriamiento viejo del gate no frena después un periodo nuevo.
                            gate.resetCooldown()
                            cargarInforme(enFondo = true)
                        },
                    )
                }
            }
        }
    }

    // Pantalla de solo lectura: sin borradores que proteger (spec §4.5).
    fun autoRefresh() {
        viewModelScope.launch {
            gate.run(workInProgress = { false }, manual = false, block = ::refreshNow)
        }
    }

    fun manualRefresh() {
        viewModelScope.launch {
            _isManualRefreshing.value = true
            try {
                gate.run(workInProgress = { false }, manual = true, block = ::refreshNow)
            } finally {
                _isManualRefreshing.value = false
            }
        }
    }

    /** Periodo o fechas nuevas = identidad nueva: invalida el TTL y re-pide. */
    fun invalidateAndRefresh() {
        gate.invalidate()
        viewModelScope.launch {
            do {
                val salida = gate.run(workInProgress = { false }, manual = false, block = ::refreshNow)
                // D18: si iba en camino la consulta de OTRO periodo, el gate se unió a ella; al terminar, se pide la del
                // periodo elegido (antes se quedaba sin pedir, con el informe del otro periodo a la vista).
            } while (salida == RefreshOutcome.Joined && ultimaPedida != _consultaElegida.value)
        }
    }

    // MARK: - Private Helpers

    /**
     * La consulta del periodo elegido y la fecha que viaja como fin. Los periodos fijos van «hasta ahora» (fin `null`
     * en la consulta: la hora de cada recarga no hace otro informe); el personalizado lleva sus dos fechas.
     */
    private fun consultaYFin(): Pair<ConsultaDeInforme, String> {
        val periodo = periodoAplicado
        val (inicio, fin) = getDateRange(periodo)
        val consulta = ConsultaDeInforme(
            sucursal = repository.sucursalActiva().orEmpty(),
            inicio = inicio,
            fin = fin.takeIf { periodo == ReportPeriod.CUSTOM },
            tipo = periodo.chartReportType,
        )
        return consulta to fin
    }

    private fun getDateRange(period: ReportPeriod): Pair<String, String> {
        if (period == ReportPeriod.CUSTOM) {
            return Pair(
                formatISODate(fechasAplicadas.first),
                formatISODate(fechasAplicadas.second),
            )
        }

        val calendar = Calendar.getInstance(
            TimeZone.getTimeZone(com.avoqado.pos.core.util.VenueTimeZone.current),
        )
        val endDate = formatISODate(calendar.timeInMillis)

        when (period) {
            ReportPeriod.TODAY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }
            ReportPeriod.THIS_WEEK -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }
            ReportPeriod.THIS_MONTH -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }
            ReportPeriod.THREE_MONTHS -> {
                calendar.add(Calendar.MONTH, -3)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }
            ReportPeriod.THIS_YEAR -> {
                calendar.set(Calendar.DAY_OF_YEAR, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }
            else -> {}
        }

        val startDate = formatISODate(calendar.timeInMillis)
        return Pair(startDate, endDate)
    }

    private fun formatISODate(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(millis))
    }
}
