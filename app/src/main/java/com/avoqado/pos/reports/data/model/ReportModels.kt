package com.avoqado.pos.reports.data.model

import kotlinx.serialization.Serializable
import java.util.Locale

// MARK: - Period Enum

enum class ReportPeriod(val label: String, val shortLabel: String) {
    TODAY("Hoy", "1D"),
    THIS_WEEK("Esta semana", "1S"),
    THIS_MONTH("Este mes", "1M"),
    THREE_MONTHS("3 meses", "3M"),
    THIS_YEAR("Este año", "1A"),
    CUSTOM("Personalizado", "");

    val chartReportType: String
        get() = when (this) {
            TODAY -> "hours"
            THIS_WEEK, THIS_MONTH -> "days"
            THREE_MONTHS -> "weeks"
            THIS_YEAR -> "months"
            CUSTOM -> "days"
        }
}

// MARK: - Sales Summary

@Serializable
data class SalesSummaryReport(
    val grossSales: Double = 0.0,
    val netSales: Double = 0.0,
    val discounts: Double = 0.0,
    val refunds: Double = 0.0,
    val taxes: Double = 0.0,
    val tips: Double = 0.0,
    val platformFees: Double = 0.0,
    val staffCommissions: Double = 0.0,
    val totalCollected: Double = 0.0,
    val netProfit: Double = 0.0,
    val transactionCount: Int = 0,
) {
    val averageTicket: Double
        get() = if (transactionCount > 0) netSales / transactionCount else 0.0

    val grossSalesFormatted: String
        get() = String.format(Locale.US, "$%,.2f", grossSales)

    val netSalesFormatted: String
        get() = String.format(Locale.US, "$%,.2f", netSales)

    val discountsFormatted: String
        get() = String.format(Locale.US, "$%,.2f", discounts)

    val refundsFormatted: String
        get() = String.format(Locale.US, "$%,.2f", refunds)

    val taxesFormatted: String
        get() = String.format(Locale.US, "$%,.2f", taxes)

    val tipsFormatted: String
        get() = String.format(Locale.US, "$%,.2f", tips)

    val platformFeesFormatted: String
        get() = String.format(Locale.US, "$%,.2f", platformFees)

    val staffCommissionsFormatted: String
        get() = String.format(Locale.US, "$%,.2f", staffCommissions)

    val totalCollectedFormatted: String
        get() = String.format(Locale.US, "$%,.2f", totalCollected)

    val netProfitFormatted: String
        get() = String.format(Locale.US, "$%,.2f", netProfit)

    val averageTicketFormatted: String
        get() = String.format(Locale.US, "$%,.2f", averageTicket)
}

// MARK: - Payment Method Breakdown

@Serializable
data class PaymentMethodBreakdown(
    val id: String = "",
    val method: String = "",
    val amount: Double = 0.0,
    val count: Int = 0,
    val percentage: Double = 0.0,
) {
    val label: String
        get() = when (method.uppercase()) {
            "CASH" -> "Efectivo"
            "CARD", "CREDIT_CARD", "DEBIT_CARD" -> "Tarjeta"
            "TRANSFER" -> "Transferencia"
            "WALLET" -> "Monedero"
            else -> method
        }

    val iconName: String
        get() = when (method.uppercase()) {
            "CASH" -> "banknote"
            "CARD", "CREDIT_CARD", "DEBIT_CARD" -> "creditcard"
            "TRANSFER" -> "arrow_forward"
            "WALLET" -> "wallet"
            else -> "creditcard"
        }
}

// MARK: - Period Sales

@Serializable
data class PeriodSales(
    val id: String = "",
    val period: String = "",
    val periodLabel: String = "",
    val grossSales: Double = 0.0,
    val transactionCount: Int = 0,
)

// MARK: - Hourly Sales

@Serializable
data class HourlySales(
    val id: String = "",
    val hour: String = "",
    val grossSales: Double = 0.0,
    val transactionCount: Int = 0,
) {
    val hourLabel: String
        get() {
            return try {
                // Parse "HH:mm" or "HH:MM" format
                val parts = hour.split(":")
                if (parts.isEmpty()) return hour
                val h = parts[0].toInt()
                when {
                    h == 0 -> "12 AM"
                    h < 12 -> "$h AM"
                    h == 12 -> "12 PM"
                    else -> "${h - 12} PM"
                }
            } catch (_: Exception) {
                hour
            }
        }
}

// MARK: - Top Product

@Serializable
data class TopProduct(
    val id: String = "",
    val productName: String = "",
    val categoryName: String? = null,
    val unitsSold: Int = 0,
    val grossSales: Double = 0.0,
    val netSales: Double = 0.0,
)

// MARK: - Category Sales

@Serializable
data class CategorySales(
    val id: String = "",
    val categoryName: String = "",
    val unitsSold: Int = 0,
    val grossSales: Double = 0.0,
)

// MARK: - Assembled Report (not serializable)

data class ReportData(
    val summary: SalesSummaryReport,
    val paymentMethods: List<PaymentMethodBreakdown>,
    val hourlySales: List<HourlySales>,
    val periodSales: List<PeriodSales>,
    val topProducts: List<TopProduct>,
    val categories: List<CategorySales>,
)
