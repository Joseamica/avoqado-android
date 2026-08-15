package com.avoqado.pos.reports

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
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

    /** Default: plan unknown → fail-open (full history, today's behavior). */
    private fun createViewModel(planTier: String? = null, planExempt: Boolean = false): ReportsViewModel {
        val storage = mockk<SecureStorage>()
        every { storage.planTier } returns planTier
        every { storage.planExempt } returns planExempt
        val refreshGateFactory = mockk<com.avoqado.pos.core.domain.refresh.RefreshGateFactory>()
        every { refreshGateFactory.create(any(), any()) } returns
            com.avoqado.pos.core.domain.refresh.RefreshGate(clock = { kotlin.time.Duration.ZERO })
        return ReportsViewModel(repository, PlanManager(storage), refreshGateFactory)
    }

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
    fun `init NO fetchea - la carga inicial la dispara la UI via el gate`() = runTest {
        val viewModel = createViewModel()
        coVerify(exactly = 0) { repository.loadReport(any(), any(), any()) }
        viewModel.autoRefresh()
        coVerify(exactly = 1) { repository.loadReport(any(), any(), any()) }
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
    fun `autoRefresh calls repository loadReport`() = runTest {
        val viewModel = createViewModel()
        // El init ya NO fetchea (la carga inicial la dispara la UI vía el gate).
        viewModel.autoRefresh()
        coVerify(atLeast = 1) { repository.loadReport(any(), any(), any()) }
    }

    @Test
    fun `autoRefresh calls loadReport with TODAY chartReportType when period is TODAY`() = runTest {
        val viewModel = createViewModel()
        viewModel.autoRefresh()
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

    // MARK: - Plan gating (ADVANCED_REPORTS, Pro): Free is clamped to TODAY

    @Test
    fun `FREE plan ignores historical period selection`() {
        val viewModel = createViewModel(planTier = "FREE")
        assertFalse(viewModel.hasAdvancedReports)

        viewModel.selectPeriod(ReportPeriod.THIS_WEEK)
        assertEquals(ReportPeriod.TODAY, viewModel.selectedPeriod.value)

        viewModel.selectPeriod(ReportPeriod.THIS_YEAR)
        assertEquals(ReportPeriod.TODAY, viewModel.selectedPeriod.value)
    }

    @Test
    fun `FREE plan ignores CUSTOM period and never opens date picker`() {
        val viewModel = createViewModel(planTier = "FREE")
        viewModel.selectPeriod(ReportPeriod.CUSTOM)
        assertEquals(ReportPeriod.TODAY, viewModel.selectedPeriod.value)
        assertFalse(viewModel.showCustomDatePicker.value)
    }

    @Test
    fun `FREE plan still allows TODAY`() = runTest {
        val viewModel = createViewModel(planTier = "FREE")
        viewModel.selectPeriod(ReportPeriod.TODAY)
        assertEquals(ReportPeriod.TODAY, viewModel.selectedPeriod.value)
        coVerify(atLeast = 1) { repository.loadReport(any(), any(), any()) }
    }

    @Test
    fun `PRO plan selects historical periods normally`() {
        val viewModel = createViewModel(planTier = "PRO")
        assertTrue(viewModel.hasAdvancedReports)
        viewModel.selectPeriod(ReportPeriod.THIS_MONTH)
        assertEquals(ReportPeriod.THIS_MONTH, viewModel.selectedPeriod.value)
    }

    @Test
    fun `exempt FREE venue keeps full history (grandfathered)`() {
        val viewModel = createViewModel(planTier = "FREE", planExempt = true)
        assertTrue(viewModel.hasAdvancedReports)
        viewModel.selectPeriod(ReportPeriod.THIS_WEEK)
        assertEquals(ReportPeriod.THIS_WEEK, viewModel.selectedPeriod.value)
    }

    @Test
    fun `absent plan fails open - full history available`() {
        val viewModel = createViewModel(planTier = null)
        assertTrue(viewModel.hasAdvancedReports)
        viewModel.selectPeriod(ReportPeriod.THREE_MONTHS)
        assertEquals(ReportPeriod.THREE_MONTHS, viewModel.selectedPeriod.value)
    }
}
