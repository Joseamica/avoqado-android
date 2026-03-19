package com.avoqado.pos.reports

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.reports.data.ReportsRepository
import com.avoqado.pos.reports.data.model.ReportPeriod
import com.avoqado.pos.reports.presentation.ReportsViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<ReportsRepository>(relaxed = true)

    @Before
    fun setup() {
        every { repository.reportData } returns MutableStateFlow(null)
        every { repository.isLoading } returns MutableStateFlow(false)
        every { repository.errorMessage } returns MutableStateFlow(null)
    }

    private fun createViewModel() = ReportsViewModel(repository)

    // MARK: - Initial State

    @Test
    fun `initial period is TODAY`() {
        val viewModel = createViewModel()
        assertEquals(ReportPeriod.TODAY, viewModel.selectedPeriod.value)
    }

    @Test
    fun `initial showCustomDatePicker is false`() {
        val viewModel = createViewModel()
        assertFalse(viewModel.showCustomDatePicker.value)
    }

    @Test
    fun `initial showDetailedSummary is false`() {
        val viewModel = createViewModel()
        assertFalse(viewModel.showDetailedSummary.value)
    }

    @Test
    fun `init loads report on construction`() = runTest {
        val viewModel = createViewModel()
        coVerify(atLeast = 1) { repository.loadReport(any(), any(), any()) }
    }

    // MARK: - Period Selection

    @Test
    fun `selectPeriod changes selectedPeriod`() {
        val viewModel = createViewModel()
        viewModel.selectPeriod(ReportPeriod.THIS_WEEK)
        assertEquals(ReportPeriod.THIS_WEEK, viewModel.selectedPeriod.value)
    }

    @Test
    fun `selectPeriod THIS_MONTH changes period correctly`() {
        val viewModel = createViewModel()
        viewModel.selectPeriod(ReportPeriod.THIS_MONTH)
        assertEquals(ReportPeriod.THIS_MONTH, viewModel.selectedPeriod.value)
    }

    @Test
    fun `selectPeriod THREE_MONTHS changes period correctly`() {
        val viewModel = createViewModel()
        viewModel.selectPeriod(ReportPeriod.THREE_MONTHS)
        assertEquals(ReportPeriod.THREE_MONTHS, viewModel.selectedPeriod.value)
    }

    @Test
    fun `selectPeriod THIS_YEAR changes period correctly`() {
        val viewModel = createViewModel()
        viewModel.selectPeriod(ReportPeriod.THIS_YEAR)
        assertEquals(ReportPeriod.THIS_YEAR, viewModel.selectedPeriod.value)
    }

    @Test
    fun `selectPeriod CUSTOM shows date picker`() {
        val viewModel = createViewModel()
        viewModel.selectPeriod(ReportPeriod.CUSTOM)
        assertTrue(viewModel.showCustomDatePicker.value)
    }

    @Test
    fun `selectPeriod CUSTOM does not load report immediately`() = runTest {
        val viewModel = createViewModel()
        // Reset after init
        io.mockk.clearMocks(repository, answers = false, recordedCalls = true, childMocks = false)
        viewModel.selectPeriod(ReportPeriod.CUSTOM)
        // After clearing, CUSTOM should not trigger loadReport
        coVerify(exactly = 0) { repository.loadReport(any(), any(), any()) }
    }

    @Test
    fun `selectPeriod non-custom hides date picker and loads report`() = runTest {
        val viewModel = createViewModel()
        // First show the date picker
        viewModel.selectPeriod(ReportPeriod.CUSTOM)
        assertTrue(viewModel.showCustomDatePicker.value)
        // Then switch to a non-custom period
        viewModel.selectPeriod(ReportPeriod.THIS_WEEK)
        assertFalse(viewModel.showCustomDatePicker.value)
        coVerify(atLeast = 1) { repository.loadReport(any(), any(), any()) }
    }

    // MARK: - Custom Date Range

    @Test
    fun `setCustomStartDate updates customStartDate`() {
        val viewModel = createViewModel()
        val millis = 1700000000000L
        viewModel.setCustomStartDate(millis)
        assertEquals(millis, viewModel.customStartDate.value)
    }

    @Test
    fun `setCustomEndDate updates customEndDate`() {
        val viewModel = createViewModel()
        val millis = 1700086400000L
        viewModel.setCustomEndDate(millis)
        assertEquals(millis, viewModel.customEndDate.value)
    }

    @Test
    fun `applyCustomDates hides picker and loads report`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectPeriod(ReportPeriod.CUSTOM)
        assertTrue(viewModel.showCustomDatePicker.value)

        viewModel.applyCustomDates()
        assertFalse(viewModel.showCustomDatePicker.value)
        coVerify(atLeast = 1) { repository.loadReport(any(), any(), any()) }
    }

    // MARK: - Detailed Summary Toggle

    @Test
    fun `toggleDetailedSummary toggles from false to true`() {
        val viewModel = createViewModel()
        assertFalse(viewModel.showDetailedSummary.value)
        viewModel.toggleDetailedSummary()
        assertTrue(viewModel.showDetailedSummary.value)
    }

    @Test
    fun `toggleDetailedSummary toggles back to false`() {
        val viewModel = createViewModel()
        viewModel.toggleDetailedSummary()
        viewModel.toggleDetailedSummary()
        assertFalse(viewModel.showDetailedSummary.value)
    }

    // MARK: - Refresh

    @Test
    fun `refresh calls repository loadReport`() = runTest {
        val viewModel = createViewModel()
        viewModel.refresh()
        // Should have been called at least twice: once in init, once on refresh
        coVerify(atLeast = 2) { repository.loadReport(any(), any(), any()) }
    }

    @Test
    fun `refresh calls loadReport with TODAY chartReportType when period is TODAY`() = runTest {
        val viewModel = createViewModel()
        viewModel.refresh()
        // TODAY has chartReportType = "hours"
        coVerify(atLeast = 1) { repository.loadReport(any(), any(), "hours") }
    }

    @Test
    fun `refresh after selectPeriod THIS_WEEK uses days reportType`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectPeriod(ReportPeriod.THIS_WEEK)
        // THIS_WEEK has chartReportType = "days"
        coVerify(atLeast = 1) { repository.loadReport(any(), any(), "days") }
    }
}
