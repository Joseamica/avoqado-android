package com.avoqado.pos.reports.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

    // MARK: - Repository State (forwarded)

    val reportData = repository.reportData
    val isLoading = repository.isLoading
    val errorMessage = repository.errorMessage

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

    init {
        loadReport()
    }

    // MARK: - Public Actions

    fun selectPeriod(period: ReportPeriod) {
        _selectedPeriod.value = period
        if (period == ReportPeriod.CUSTOM) {
            _showCustomDatePicker.value = true
        } else {
            _showCustomDatePicker.value = false
            loadReport()
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
        loadReport()
    }

    fun toggleDetailedSummary() {
        _showDetailedSummary.value = !_showDetailedSummary.value
    }

    fun refresh() {
        loadReport()
    }

    // MARK: - Private Helpers

    private fun loadReport() {
        viewModelScope.launch {
            val (startDate, endDate) = getDateRange()
            val reportType = _selectedPeriod.value.chartReportType
            repository.loadReport(startDate, endDate, reportType)
        }
    }

    private fun getDateRange(): Pair<String, String> {
        val period = _selectedPeriod.value
        if (period == ReportPeriod.CUSTOM) {
            return Pair(
                formatISODate(_customStartDate.value),
                formatISODate(_customEndDate.value),
            )
        }

        val calendar = Calendar.getInstance()
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
