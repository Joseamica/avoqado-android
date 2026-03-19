package com.avoqado.pos.reports

import com.avoqado.pos.reports.data.model.PaymentMethodBreakdown
import com.avoqado.pos.reports.data.model.ReportPeriod
import com.avoqado.pos.reports.data.model.SalesSummaryReport
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportModelsTest {

    // MARK: - SalesSummaryReport

    @Test
    fun `averageTicket divides netSales by transactionCount`() {
        val report = SalesSummaryReport(netSales = 1000.0, transactionCount = 4)
        assertEquals(250.0, report.averageTicket, 0.001)
    }

    @Test
    fun `averageTicket returns zero when no transactions`() {
        val report = SalesSummaryReport(netSales = 500.0, transactionCount = 0)
        assertEquals(0.0, report.averageTicket, 0.001)
    }

    // MARK: - ReportPeriod

    @Test
    fun `TODAY chartReportType is hours`() {
        assertEquals("hours", ReportPeriod.TODAY.chartReportType)
    }

    @Test
    fun `THIS_WEEK chartReportType is days`() {
        assertEquals("days", ReportPeriod.THIS_WEEK.chartReportType)
    }

    @Test
    fun `THIS_YEAR chartReportType is months`() {
        assertEquals("months", ReportPeriod.THIS_YEAR.chartReportType)
    }

    // MARK: - PaymentMethodBreakdown

    @Test
    fun `payment method CASH label is Efectivo`() {
        val breakdown = PaymentMethodBreakdown(method = "CASH")
        assertEquals("Efectivo", breakdown.label)
    }

    @Test
    fun `payment method CREDIT_CARD label is Tarjeta`() {
        val breakdown = PaymentMethodBreakdown(method = "CREDIT_CARD")
        assertEquals("Tarjeta", breakdown.label)
    }

    @Test
    fun `payment method unknown returns raw method string`() {
        val breakdown = PaymentMethodBreakdown(method = "CRYPTO")
        assertEquals("CRYPTO", breakdown.label)
    }
}
