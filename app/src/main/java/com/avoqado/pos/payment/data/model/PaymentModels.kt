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
        /**
         * Aviso de inventario del server (Square-parity): el cobro SÍ quedó registrado,
         * pero el stock quedó en negativo o no se pudo descontar. Español, listo para
         * el toast ámbar. null = sin faltantes.
         */
        val inventoryWarningMessage: String? = null,
        /**
         * Aviso del server sobre EL CLIENTE de esta venta (`customerLink`): el id no
         * existe en este negocio, la venta ya tenía otro, o no se pudo verificar.
         * Español, listo para el toast ámbar.
         *
         * 🔴 El cobro SÍ quedó registrado. Es aviso, no error: nunca se vuelve a
         * cobrar — el cliente se reasigna desde la misma pantalla de recibo.
         */
        val customerLinkWarning: String? = null,
    ) : PaymentFlowState()
    data class Error(val message: String, val source: PaymentErrorSource) : PaymentFlowState()

    /**
     * 🔴 Tercer desenlace del cobro con tarjeta: **no se sabe si se cobró**.
     *
     * Ni éxito ni fracaso. Nace de un fallo de transporte, un plazo de espera vencido o un
     * server inalcanzable en un punto donde la terminal YA pudo haber cobrado. Existe porque
     * pintarlo como Error —y ofrecer Reintentar— cobró una tarjeta dos veces (2026-08-10).
     *
     * Desde aquí NUNCA sale un cargo a ciegas: sólo volver a consultar, o cobrar de nuevo
     * tras una advertencia explícita del riesgo.
     *
     * @param checking true mientras se re-consulta el estado (deshabilita las acciones).
     * @param fromPreviousSale el cobro sin resolver quedó de OTRA venta (la llave sobrevivió en
     *   disco a un cambio de pestaña o a la muerte del proceso). Confirmarlo NO paga la venta
     *   actual: sólo suelta el bloqueo para poder cobrar ésta.
     */
    data class Undetermined(
        val totalAmount: Int,
        val message: String,
        val checking: Boolean = false,
        val fromPreviousSale: Boolean = false,
    ) : PaymentFlowState()
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

/** Una elección de la persona dentro de la promoción: qué grupo y qué opción. */
@Serializable
data class PromotionSelectionRequest(
    val groupId: String,
    val optionId: String,
)

/**
 * QUÉ promoción tocó el cajero y QUÉ eligió la persona. **Nunca precios**: el
 * server resuelve el combo contra su propio catálogo y arma sus líneas.
 *
 * @param promotionInstanceId una instancia = UN combo. Es la llave de
 *   idempotencia del server (`@@unique(orderId, instanceId)`), así que 3 combos
 *   son 3 líneas con 3 instancias distintas — nunca `quantity: 3`, que el server
 *   rechaza con 400.
 */
@Serializable
data class PromotionRefRequest(
    val promotionId: String,
    val promotionInstanceId: String,
    val selections: List<PromotionSelectionRequest> = emptyList(),
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
    /**
     * Línea de PROMOCIÓN. Cuando viene, esta línea es la promoción entera y
     * viaja SOLA: `OrderRepository.buildCreateOrderPayload` emite únicamente
     * `promotionRef` y omite `productId`, `name` y `unitPrice` — el server
     * rechaza con 400 un item que traiga las dos cosas, y `unitPrice: 0` cuenta
     * como precio (`typeof 0 === 'number'`).
     *
     * Por eso [name] y [unitPrice] quedan en `""` / `0` en una línea de
     * promoción: son relleno del modelo local que NUNCA sale por el cable.
     * Constrúyela con [promocion], no a mano.
     */
    val promotionRef: PromotionRefRequest? = null,
) {
    /** Línea de promoción: el ref y nada más. Ver [promotionRef]. */
    companion object {
        fun promocion(ref: PromotionRefRequest): OrderItemRequest = OrderItemRequest(
            productId = null,
            name = "",
            quantity = 1,
            unitPrice = 0,
            promotionRef = ref,
        )
    }
}

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

/**
 * 🔴 DINERO — cuánto se cobra cuando el server ACABA de crear la orden.
 *
 * Con una promoción el POS sólo **estima** el precio del combo: reproduce la
 * aritmética del server, pero una línea del carrito sólo sabe expresar
 * `precio × cantidad` y el neto que le toca rara vez es múltiplo de su cantidad.
 * La desviación está acotada (`Σ floor(cantidad_i / 2)` centavos) pero existe, y
 * el server tolera sólo **1 centavo** al decidir si una cuenta quedó PAGADA
 * (`remainingAfterPayment <= 0.01`): cobrar el estimado deja la orden PARTIAL
 * para siempre por unos centavos, o pagada de más.
 *
 * Cuando la orden se crea ANTES de tomar el dinero —que es como funciona el
 * cobro— la respuesta ya trae el total real. Ése es el que se cobra.
 *
 * Se adopta SÓLO si se cumplen las cuatro:
 *  - la venta lleva promoción (es el único origen conocido de la desviación, y
 *    así una venta normal se comporta byte por byte como antes);
 *  - es pago COMPLETO — el total del server es de la ORDEN entera, así que en un
 *    pago dividido cobraría de más al primero que pasa;
 *  - el server mandó total (uno viejo no lo manda);
 *  - no es negativo.
 */
fun totalACobrarCents(
    estimadoLocalCents: Int,
    orden: OrderData?,
    esPagoCompleto: Boolean,
    laVentaLlevaPromocion: Boolean,
): Int {
    if (!laVentaLlevaPromocion || !esPagoCompleto) return estimadoLocalCents
    val delServer = orden?.totalCents ?: return estimadoLocalCents
    if (delServer < 0) return estimadoLocalCents
    return delServer
}

@Serializable
data class OrderData(
    val id: String,
    // Folio real que asigna el backend (p.ej. "ORD-123"). Antes se imprimía
    // takeLast(4) del id interno — que no es un folio y salía "---".
    val orderNumber: String? = null,
    val totalAmount: Int? = null,
    /**
     * 🔴 DINERO. Total de la orden **en pesos con decimales** (`114.0`), que es
     * como lo devuelve el server (`toCreatedOrderResponse` → `Number(order.total)`).
     * Ya trae la promoción resuelta, el descuento y la propina.
     *
     * Es el único campo de total que el backend manda de verdad: `totalAmount`
     * (centavos) no existe en esa respuesta y por eso siempre llegaba null.
     */
    val total: Double? = null,
    val status: String? = null,
    /** Promociones que el server resolvió en esta orden. Vacío = venta normal. */
    val promotions: List<OrderPromotionData> = emptyList(),
) {
    /**
     * El total de la orden en CENTAVOS, que es la unidad en la que cobra el POS.
     * null si el server no lo mandó (versión vieja) — y entonces no hay nada que
     * adoptar: se cobra el estimado local.
     */
    val totalCents: Int?
        get() = total?.let { kotlin.math.round(it * 100).toInt() } ?: totalAmount
}

/** Una promoción tal como quedó registrada en la orden. */
@Serializable
data class OrderPromotionData(
    val id: String? = null,
    val instanceId: String? = null,
    val name: String? = null,
    val netCents: Int? = null,
    val discountCents: Int? = null,
    val needsReview: Boolean = false,
)
