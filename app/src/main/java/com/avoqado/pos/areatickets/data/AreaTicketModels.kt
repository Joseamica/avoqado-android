package com.avoqado.pos.areatickets.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AreaTicketApiError(
    val code: String = "AREA_TICKET_REQUEST_FAILED",
    val message: String = "No se pudo completar la operación.",
    val retryable: Boolean = false,
)

@Serializable
data class AreaTicketEnvelope<T>(
    val success: Boolean,
    val data: T? = null,
    val error: AreaTicketApiError? = null,
)

@Serializable
data class AreaTicketSettingsData(
    val venueId: String,
    val areaTickets: AreaTicketModuleSettings,
    val terminal: AreaTicketTerminalCapabilities,
    val scaleIntegration: ScaleIntegrationSettings,
    val variableWeightBarcode: VariableWeightBarcodeSettings = VariableWeightBarcodeSettings(),
)

@Serializable
data class AreaTicketModuleSettings(
    val entitled: Boolean,
    val enabled: Boolean,
    val allowMixedCart: Boolean = true,
    val claimTtlSeconds: Int = 300,
    val checkoutSessionMaxAgeMinutes: Int = 30,
    val ticketExpiryPolicy: String = "BUSINESS_DAY_CLOSE",
    val ticketExpiryMinutes: Int? = null,
    val deliveryVerificationMode: String = "PAPER_OR_SCAN",
    val codeSymbology: String = "CODE128",
    val inventoryReservationMode: String = "NONE",
)

@Serializable
data class AreaTicketArea(
    val id: String,
    val name: String,
    val fulfillmentMode: String,
    val active: Boolean = true,
)

@Serializable
data class AreaTicketTerminalCapabilities(
    val id: String,
    val name: String,
    val fulfillmentArea: AreaTicketArea? = null,
    val canIssueAreaTickets: Boolean = false,
    val canCheckoutAreaTickets: Boolean = false,
    val canDeliverAreaTickets: Boolean = false,
    val defaultWorkspace: String = "STANDARD_POS",
)

@Serializable
data class ScaleProfile(
    val id: String,
    val name: String,
    val location: String,
    val model: String,
    val allowedContexts: List<String> = emptyList(),
    val transport: String = "MANUAL",
    val vendorId: Int? = null,
    val productId: Int? = null,
    val baudRate: Int? = null,
    val dataBits: Int? = null,
    val parity: String? = null,
    val stopBits: Int? = null,
    val frameParser: JsonObject? = null,
    val stableIndicator: String? = null,
    val unit: String = "KILOGRAM",
    val active: Boolean = true,
)

@Serializable
data class ScaleIntegrationSettings(
    val entitled: Boolean,
    val enabled: Boolean,
    val profile: ScaleProfile? = null,
    val manualFallbackAllowed: Boolean = true,
)

@Serializable
data class VariableWeightBarcodeSettings(
    val entitled: Boolean = false,
    val enabled: Boolean = false,
    val format: String = "EAN13_PLU5_WEIGHT5",
    val prefix: String = "20",
)

@Serializable
data class AreaTicketLine(
    val id: String,
    val clientLineId: String,
    val productId: String? = null,
    val productNameSnapshot: String,
    val skuSnapshot: String? = null,
    val categoryNameSnapshot: String? = null,
    val quantity: String,
    val weightKg: String? = null,
    val unitPrice: String,
    val discountAmount: String = "0.00",
    val taxAmount: String = "0.00",
    val total: String,
    val notes: String? = null,
)

@Serializable
data class AreaTicket(
    val id: String,
    val code: String,
    val status: String,
    val fulfillmentArea: AreaTicketArea,
    val currency: String = "MXN",
    val subtotal: String,
    val discountAmount: String = "0.00",
    val taxAmount: String = "0.00",
    val total: String,
    val printStatus: String = "NOT_PRINTED",
    val version: Int = 1,
    val issuedAt: String,
    val paidAt: String? = null,
    val orderId: String? = null,
    val checkoutSessionId: String? = null,
    val lines: List<AreaTicketLine> = emptyList(),
)

@Serializable
data class AreaTicketCheckoutOrder(
    val id: String,
    val orderNumber: String,
    val paymentStatus: String,
    val status: String,
    val paidAmount: String = "0.00",
    val remainingBalance: String = "0.00",
    val total: String,
    val areaDeliveryCode: String? = null,
)

@Serializable
data class AreaTicketCheckoutTotals(
    val subtotal: String,
    val discountAmount: String,
    val total: String,
)

@Serializable
data class AreaTicketCheckout(
    val id: String,
    val status: String,
    val order: AreaTicketCheckoutOrder? = null,
    val activePaymentAttemptId: String? = null,
    val version: Int,
    val lastHeartbeatAt: String? = null,
    val expiresAt: String,
    val createdAt: String,
    val tickets: List<AreaTicket> = emptyList(),
    val totals: AreaTicketCheckoutTotals,
)

@Serializable
data class AreaTicketScanData(
    val type: String,
    val code: String,
    val ticket: AreaTicket? = null,
    val candidates: List<String> = emptyList(),
)

@Serializable
data class ScanRequest(val code: String, val context: String)

@Serializable
data class IdempotentRequest(val idempotencyKey: String)

@Serializable
data class AddTicketRequest(val code: String, val idempotencyKey: String)

@Serializable
data class CheckoutData(val checkout: AreaTicketCheckout)

@Serializable
data class NormalCheckoutItem(
    val productId: String,
    val quantity: Int,
    val notes: String? = null,
    val modifierIds: List<String> = emptyList(),
    val discountId: String? = null,
    val weightQuantity: Double? = null,
)

@Serializable
data class MaterializeCheckoutRequest(
    val idempotencyKey: String,
    val normalItems: List<NormalCheckoutItem>,
    val source: String = "AVOQADO_ANDROID",
    val customerName: String? = null,
    val note: String? = null,
)

@Serializable
data class IssueAreaTicketLineRequest(
    val clientLineId: String,
    val productId: String,
    val quantity: String,
    val weightKg: String? = null,
    val notes: String? = null,
    val modifierIds: List<String> = emptyList(),
    val discountId: String? = null,
)

@Serializable
data class IssueAreaTicketRequest(
    val idempotencyKey: String,
    val lines: List<IssueAreaTicketLineRequest>,
)

@Serializable
data class IssuedTicketData(val ticket: AreaTicket)

@Serializable
data class PrintAttemptRequest(
    val idempotencyKey: String,
    val status: String,
    val kind: String,
    val reason: String? = null,
    val errorCode: String? = null,
)

@Serializable
data class FulfillAreaTicketRequest(
    val idempotencyKey: String,
    val method: String,
)

@Serializable
data class PendingFulfillmentData(
    val fulfillmentArea: AreaTicketArea? = null,
    val tickets: List<AreaTicket> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class DeliveryOrderSummary(
    val id: String,
    val orderNumber: String,
    val areaDeliveryCode: String? = null,
    val paymentStatus: String,
)

@Serializable
data class DeliveryResolutionData(
    val order: DeliveryOrderSummary,
    val fulfillmentArea: AreaTicketArea,
    val tickets: List<AreaTicket> = emptyList(),
)

fun String.moneyToCents(): Int = runCatching {
    java.math.BigDecimal(this).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact()
}.getOrDefault(0)
