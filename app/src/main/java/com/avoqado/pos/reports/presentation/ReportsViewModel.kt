package com.avoqado.pos.reports.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.reports.data.ReportsRepository
import com.avoqado.pos.reports.data.model.ReportPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    // MARK: - Refresco (spec estrategia-de-refresco)

    private val gate = refreshGateFactory.create(viewModelScope)

    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    // MARK: - Repository State (forwarded)

    val reportData = repository.reportData
    val isLoading = repository.isLoading
    val errorMessage = repository.errorMessage

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

    // MARK: - Detail Expansion

    private val _showDetailedSummary = MutableStateFlow(false)
    val showDetailedSummary: StateFlow<Boolean> = _showDetailedSummary.asStateFlow()

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
            _showCustomDatePicker.value = true
        } else {
            _showCustomDatePicker.value = false
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
        // Otras fechas = otra identidad (spec §4.4).
        invalidateAndRefresh()
    }

    fun toggleDetailedSummary() {
        _showDetailedSummary.value = !_showDetailedSummary.value
    }

    /** Contrato §4.2: sin launch interno; el gate decide y sella el reloj. */
    suspend fun refreshNow(): Result<Unit> {
        val (startDate, endDate) = getDateRange()
        return repository.loadReport(startDate, endDate, _selectedPeriod.value.chartReportType)
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
            gate.run(workInProgress = { false }, manual = false, block = ::refreshNow)
        }
    }

    // MARK: - Private Helpers

    private fun getDateRange(): Pair<String, String> {
        val period = _selectedPeriod.value
        if (period == ReportPeriod.CUSTOM) {
            return Pair(
                formatISODate(_customStartDate.value),
                formatISODate(_customEndDate.value),
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
