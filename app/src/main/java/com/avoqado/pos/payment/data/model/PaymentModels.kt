package com.avoqado.pos.payment.data.model

import kotlinx.serialization.Serializable

enum class PaymentMethod(val value: String, val displayName: String) {
    CARD("CARD", "Tarjeta"),
    CASH("CASH", "Efectivo"),
}

enum class PaymentErrorSource {
    NETWORK,
    TERMINAL,
    SERVER,
    UNKNOWN,
}

sealed class PaymentFlowState {
    data object Loading : PaymentFlowState()
    data class SelectingPaymentMethod(val amount: Int) : PaymentFlowState()
    data class CollectingCashAmount(val total: Int) : PaymentFlowState()
    data class CollectingRating(val amount: Int) : PaymentFlowState()
    data class CollectingTip(val amount: Int, val rating: Int?) : PaymentFlowState()
    data class Confirming(val amount: Int, val tip: Int, val rating: Int?) : PaymentFlowState()
    data class SelectingTerminal(val totalAmount: Int) : PaymentFlowState()
    data class Processing(val totalAmount: Int) : PaymentFlowState()
    data class SentToTerminal(val totalAmount: Int) : PaymentFlowState()
    data class Success(
        val totalAmount: Int,
        val method: PaymentMethod,
        val changeAmount: Int = 0,
        val isQueued: Boolean = false,  // true when payment was queued offline
        val paymentId: String? = null,
        val receiptAccessKey: String? = null,
        /**
         * URL del recibo ya armada por el backend (apunta al dashboard: calificación + autofactura).
         * Preferirla sobre construirla desde el accessKey — ver `resolveReceiptUrl`.
         */
        val receiptUrl: String? = null,
    ) : PaymentFlowState()
    data class Error(val message: String, val source: PaymentErrorSource) : PaymentFlowState()
}

data class PaymentContext(
    val subtotalCents: Int,
    val discountCents: Int = 0,
    val taxCents: Int = 0,
    val tipCents: Int = 0,
    val totalCents: Int = subtotalCents,
    val rating: Int? = null,
    val tipPercentage: Int? = null,
    val items: List<PaymentItem> = emptyList(),
    val splitType: String = "FULLPAYMENT",
)

data class PaymentItem(
    val name: String,
    val quantity: Int,
    val unitPrice: Int,
    val lineTotal: Int,
    val modifiers: List<String> = emptyList(),
    val note: String? = null,
    val isCortesia: Boolean = false,
)

@Serializable
data class CreateOrderRequest(
    val items: List<OrderItemRequest>,
    val subtotal: Int,
    val discount: Int = 0,
    val tip: Int = 0,
    val total: Int,
    val paymentMethod: String,
    val rating: Int? = null,
    val note: String? = null,
    val splitType: String? = null,
    /** Links the resulting sale to a class/appointment reservation (walk-in flow).
     *  Null for ordinary sales. */
    val reservationId: String? = null,
)

@Serializable
data class OrderItemRequest(
    val productId: String? = null,
    val name: String,
    val quantity: Int,
    val unitPrice: Int,
    val modifiers: List<OrderModifierRequest> = emptyList(),
    val note: String? = null,
    val isCortesia: Boolean = false,
    /** ITEM/CATEGORY-scoped discount selected from the product panel — mirrors
     * iOS's `PaymentItem.discountId` (PaymentModels.swift), sent as-is on the
     * `/mobile/venues/:venueId/orders` payload. */
    val discountId: String? = null,
    /**
     * Venta por peso: kg con 3 decimales (ej. 0.435), SIEMPRE con productId real y quantity = 1.
     * El server recalcula total = Product.price × weightQuantity (half-up a 2 dec) — el cliente
     * nunca manda precio para líneas con productId. Se OMITE del payload cuando es null (líneas
     * normales idénticas a antes). Rechaza 400: producto por peso sin weightQuantity, peso en
     * producto normal, quantity≠1 en pesado, o fuera de 0.001–99.999 kg.
     */
    val weightQuantity: Double? = null,
)

@Serializable
data class OrderModifierRequest(
    val modifierId: String,
    val name: String,
    val price: Int,
)

@Serializable
data class CreateOrderResponse(
    val success: Boolean = true,
    val data: OrderData? = null,
    val message: String? = null,
)

@Serializable
data class OrderData(
    val id: String,
    // Folio real que asigna el backend (p.ej. "ORD-123"). Antes se imprimía
    // takeLast(4) del id interno — que no es un folio y salía "---".
    val orderNumber: String? = null,
    val totalAmount: Int? = null,
    val status: String? = null,
)
