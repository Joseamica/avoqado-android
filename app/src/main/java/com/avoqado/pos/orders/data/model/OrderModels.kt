package com.avoqado.pos.orders.data.model

import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.core.util.VenueTimeZone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import com.avoqado.pos.tables.data.PreBillBalance
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun puedeCobrarseDesdeLaLista(cobrosConSaldo: Int?, detalle: OrderDetail, porTerminarAqui: Boolean = false): Boolean =
    (cobrosConSaldo ?: 0) >= 1 || porTerminarAqui || detalle.payments.orEmpty().none { it.status == "COMPLETED" }

// MARK: - Enums

enum class OrderStatus(val label: String) {
    PENDING("Pendiente"),
    COMPLETED("Completado"),
    CONFIRMED("Confirmado"),
    CANCELLED("Cancelado");
}

enum class PaymentStatus(val label: String) {
    PENDING("Sin pagar"),
    PARTIAL("Parcial"),
    PAID("Pagado");
}

enum class OrderType(val label: String) {
    DINE_IN("Para comer aquí"),
    TAKEOUT("Para llevar"),
    DELIVERY("Entrega"),
    PICKUP("Recoger");
}

/**
 * Carril del REEMBOLSO — espejo por nombre EXACTO de `RefundState` del server
 * (`src/services/shared/orderBalance.ts`), que Android e iOS replican igual.
 *
 * 🔴 Un reembolso NO reabre el saldo: la cuenta queda `COMPLETED` / `PAID` y su
 * `total` NO se reescribe (modelo Toast/Square, y el CFDI de Egreso mexicano lo
 * exige). Por eso una venta devuelta se ve IDÉNTICA a una cobrada si nadie
 * pinta la marca — y por eso "cobrar el saldo" sobre ella cobraría de más.
 */
enum class RefundState {
    NONE,
    PARTIAL,
    FULL,
    ;

    companion object {
        /**
         * Tolerante a propósito: campo ausente (server viejo) o valor que este
         * cliente no conoce ⇒ [NONE], o sea el comportamiento de hoy. Inventar
         * una marca a partir de un estado desconocido sería peor que no pintarla.
         */
        fun from(raw: String?): RefundState = when (raw?.uppercase()) {
            "FULL" -> FULL
            "PARTIAL" -> PARTIAL
            else -> NONE
        }
    }
}

/**
 * Texto de la marca, o null cuando no hay nada que marcar. Vive aquí —y no en
 * la pantalla— para que Android e iOS no puedan divergir en el texto.
 */
internal fun refundBadgeLabelFor(state: RefundState, refundedAmount: Double): String? = when (state) {
    RefundState.FULL -> "Reembolsada"
    RefundState.PARTIAL -> "Reembolso parcial: $${String.format(Locale.US, "%.2f", refundedAmount)}"
    RefundState.NONE -> null
}

// MARK: - Summary Model

@Serializable
data class OrderSummary(
    val id: String,
    val esCuentaDeTarjetas: Boolean = false,
    val esDeMesa: Boolean? = null,
    val cobrosConSaldo: Int? = null,
    val orderNumber: String = "",
    val status: String = "",
    val paymentStatus: String = "",
    val type: String? = null,
    val source: String? = null,
    val subtotal: Double = 0.0,
    val taxAmount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val total: Double = 0.0,
    val itemCount: Int = 0,
    val staffName: String? = null,
    val customerName: String? = null,
    val createdAt: String = "",
    /** ADITIVO (server 2026-08-18). Ausente en servers viejos ⇒ "NONE". */
    val refundState: String = "NONE",
    /** Lo devuelto, en PESOS y POSITIVO. 0 cuando no hay reembolsos. */
    val refundedAmount: Double = 0.0,
    /** D12, aditivo: lo que falta, en CENTAVOS. Ausente (servidor viejo) o raro ⇒ null ⇒ «sin saldo» ⇒ gris sin red. */
    @Serializable(with = CentavosTolerantes::class) val saldoPendienteCents: Int? = null,
    /** D12, aditivo: pagos COMPLETED de la cuenta, devoluciones incluidas (espejo de [puedeCobrarseDesdeLaLista]). */
    @Serializable(with = CentavosTolerantes::class) val pagosCompletados: Int? = null,
    /** D12, aditivo: la cuenta es de vales por área. */
    val esDeVales: Boolean? = null,
) {
    @Transient
    val orderStatus: OrderStatus = when (status.uppercase()) {
        "PENDING" -> OrderStatus.PENDING
        "COMPLETED" -> OrderStatus.COMPLETED
        "CONFIRMED" -> OrderStatus.CONFIRMED
        "CANCELLED" -> OrderStatus.CANCELLED
        else -> OrderStatus.PENDING
    }

    @Transient
    val orderPaymentStatus: PaymentStatus = when (paymentStatus.uppercase()) {
        "PARTIAL" -> PaymentStatus.PARTIAL
        "PAID" -> PaymentStatus.PAID
        else -> PaymentStatus.PENDING
    }

    @Transient
    val orderType: OrderType? = when (type?.uppercase()) {
        "DINE_IN" -> OrderType.DINE_IN
        "TAKEOUT" -> OrderType.TAKEOUT
        "DELIVERY" -> OrderType.DELIVERY
        "PICKUP" -> OrderType.PICKUP
        else -> null
    }

    @Transient
    val refund: RefundState = RefundState.from(refundState)

    /** "Reembolsada" / "Reembolso parcial: $X", o null si no hay nada que marcar. */
    @Transient
    val refundBadgeLabel: String? = refundBadgeLabelFor(refund, refundedAmount)

    /** 🔴 Una cuenta devuelta ya no se cobra: el reembolso no reabre el saldo. */
    @Transient
    val allowsCollection: Boolean = refund == RefundState.NONE

    @Transient
    val formattedTotal: String = "$%,.2f".format(total)

    @Transient
    val timeDisplay: String = parseTimeDisplay(createdAt)

    @Transient
    val dateGroup: String = parseDateGroup(createdAt)
}

/**
 * D12 (Codex r2): un entero JSON ≥ 0 ⇒ ese `Int`; fraccionario, negativo, texto, `null` u otra cosa ⇒ `null`, SIN lanzar.
 * Una fila rara nunca tumba la página entera; sólo esa fila queda «sin saldo» (gris sin red).
 */
object CentavosTolerantes : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = Int.serializer().nullable.descriptor
    override fun deserialize(decoder: Decoder): Int? {
        val valor = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive ?: return null
        return if (valor.isString) null else valor.content.toIntOrNull()?.takeIf { it >= 0 }
    }
    override fun serialize(encoder: Encoder, value: Int?) = (encoder as JsonEncoder).encodeJsonElement(JsonPrimitive(value))
}

/**
 * D12 (P13): LA regla de «cuenta abierta». Una sola, para la copia de «Pedidos», su detalle y el cobro sin red.
 * Abierta = ni cancelada, ni borrada, ni completada; no pagada; y sin reembolso.
 */
fun estaAbierta(status: String?, paymentStatus: String?, refundState: String?): Boolean =
    status?.uppercase() !in setOf("CANCELLED", "DELETED", "COMPLETED") &&
        !paymentStatus.equals("PAID", ignoreCase = true) &&
        RefundState.from(refundState) == RefundState.NONE

/**
 * La fila de la lista vista como detalle (para enseñarla sin red). 🔴 `payments = null` NO dice «sin abonos»: la
 * elegibilidad sin red sale de la FILA (`razonParaNoCobrar`), nunca de [puedeCobrarseDesdeLaLista] sobre este detalle.
 */
fun OrderSummary.comoDetalle(): OrderDetail = OrderDetail(
    id = id, esCuentaDeTarjetas = esCuentaDeTarjetas, esDeMesa = esDeMesa, cobrosConSaldo = cobrosConSaldo,
    orderNumber = orderNumber, status = status, paymentStatus = paymentStatus, type = type, source = source,
    subtotal = subtotal, taxAmount = taxAmount, discountAmount = discountAmount, total = total, itemCount = itemCount,
    staffName = staffName, customerName = customerName, createdAt = createdAt, items = null, payments = null,
    refundState = refundState, refundedAmount = refundedAmount,
)

/** D12 (Codex r3 H1): lo que este detalle bueno sabe de la cuenta, para poner la copia al día. */
fun OrderDetail.comoEvidencia(): com.avoqado.pos.orders.data.EvidenciaDelDetalle.Vigente =
    com.avoqado.pos.orders.data.EvidenciaDelDetalle.Vigente(
        abierta = estaAbierta(status, paymentStatus, refundState),
        saldoCents = preBillBalance?.remainingBalanceCents?.takeIf { it >= 0 }, // negativo = raro ⇒ «sin saldo», como la lista
        pagosCompletados = payments.orEmpty().count { it.status == "COMPLETED" },
        cobrosConSaldo = cobrosConSaldo ?: 0,
        esCuentaDeTarjetas = esCuentaDeTarjetas,
    )

/**
 * D12 (revisión r1): el saldo del detalle, tolerante como [CentavosTolerantes]. Si viene raro (fraccionario, texto, sin un
 * campo), es `null` («sin saldo»: gris sin red, nunca cobrable) y el detalle abre igual; antes de D12 este campo se ignoraba.
 */
object SaldoTolerante : KSerializer<PreBillBalance?> {
    override val descriptor: SerialDescriptor = PreBillBalance.serializer().nullable.descriptor
    override fun deserialize(decoder: Decoder): PreBillBalance? {
        val json = decoder as JsonDecoder
        val elemento = json.decodeJsonElement()
        return runCatching { json.json.decodeFromJsonElement(PreBillBalance.serializer(), elemento) }.getOrNull()
    }
    override fun serialize(encoder: Encoder, value: PreBillBalance?) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(value?.let { json.json.encodeToJsonElement(PreBillBalance.serializer(), it) } ?: JsonNull)
    }
}

// MARK: - Detail Model

@Serializable
data class OrderDetail(
    val id: String,
    val esCuentaDeTarjetas: Boolean = false,
    val esDeMesa: Boolean? = null,
    val cobrosConSaldo: Int? = null,
    val orderNumber: String = "",
    val status: String = "",
    val paymentStatus: String = "",
    val type: String? = null,
    val source: String? = null,
    val subtotal: Double = 0.0,
    val taxAmount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val total: Double = 0.0,
    val itemCount: Int = 0,
    val staffName: String? = null,
    val customerName: String? = null,
    val createdAt: String = "",
    val specialRequests: String? = null,
    val items: List<OrderDetailItem>? = null,
    val payments: List<OrderPaymentInfo>? = null,
    /** ADITIVO (server 2026-08-18). Ausente en servers viejos ⇒ "NONE". */
    val refundState: String = "NONE",
    /** Lo devuelto, en PESOS y POSITIVO. 0 cuando no hay reembolsos. */
    val refundedAmount: Double = 0.0,
    /** IVA que la orden SUMA aparte (servidor, aditivo, plan 7, r2 R2-13). Null = servidor anterior. */
    val addedTaxAmount: Double? = null,
    /** D12: el saldo que el servidor ya manda en este detalle (el mismo que lee Mesas). Null = no vino o vino raro. */
    @Serializable(with = SaldoTolerante::class) val preBillBalance: PreBillBalance? = null,
) {
    @Transient
    val orderStatus: OrderStatus = when (status.uppercase()) {
        "PENDING" -> OrderStatus.PENDING
        "COMPLETED" -> OrderStatus.COMPLETED
        "CONFIRMED" -> OrderStatus.CONFIRMED
        "CANCELLED" -> OrderStatus.CANCELLED
        else -> OrderStatus.PENDING
    }

    @Transient
    val orderPaymentStatus: PaymentStatus = when (paymentStatus.uppercase()) {
        "PARTIAL" -> PaymentStatus.PARTIAL
        "PAID" -> PaymentStatus.PAID
        else -> PaymentStatus.PENDING
    }

    @Transient
    val orderType: OrderType? = when (type?.uppercase()) {
        "DINE_IN" -> OrderType.DINE_IN
        "TAKEOUT" -> OrderType.TAKEOUT
        "DELIVERY" -> OrderType.DELIVERY
        "PICKUP" -> OrderType.PICKUP
        else -> null
    }

    @Transient
    val refund: RefundState = RefundState.from(refundState)

    /** "Reembolsada" / "Reembolso parcial: $X", o null si no hay nada que marcar. */
    @Transient
    val refundBadgeLabel: String? = refundBadgeLabelFor(refund, refundedAmount)

    /** 🔴 Una cuenta devuelta ya no se cobra: el reembolso no reabre el saldo. */
    @Transient
    val allowsCollection: Boolean = refund == RefundState.NONE

    @Transient
    val formattedTotal: String = "$%,.2f".format(total)

    @Transient
    val timeDisplay: String = parseTimeDisplay(createdAt)

    @Transient
    val dateGroup: String = parseDateGroup(createdAt)
}

// MARK: - Item Models

@Serializable
data class OrderDetailItem(
    val id: String,
    val productId: String? = null,
    val productName: String = "",
    val quantity: Int = 1,
    val unitPrice: Double = 0.0,
    val total: Double = 0.0,
    val notes: String? = null,
    val modifiers: List<OrderItemModifier>? = null,
)

@Serializable
data class OrderItemModifier(
    val name: String = "",
    val price: Double = 0.0,
)

// MARK: - Payment Info

@Serializable
data class OrderPaymentInfo(
    val id: String,
    val esSaldoDeTarjeta: Boolean? = null,
    val amount: Double = 0.0,
    val tipAmount: Double = 0.0,
    val method: String = "",
    val status: String = "",
    val createdAt: String = "",
    val processedBy: String? = null,
) {
    @Transient
    val methodLabel: String = if (esSaldoDeTarjeta == true) com.avoqado.pos.giftcards.domain.TextosDeTarjetas.FILA_COBRO else when (method.uppercase()) {
        "CASH" -> "Efectivo"
        "CARD", "CREDIT_CARD", "DEBIT_CARD" -> "Tarjeta"
        // El server manda BANK_TRANSFER (enum PaymentMethod); sin esto el detalle lo mostraba crudo.
        "TRANSFER", "BANK_TRANSFER" -> "Transferencia"
        "DIGITAL_WALLET" -> "Cartera digital"
        "CRYPTOCURRENCY" -> "Criptomoneda"
        "OTHER" -> "Otro"
        else -> method
    }

    @Transient
    val timeDisplay: String = parseTimeDisplay(createdAt)
}

// MARK: - Paginated Response

@Serializable
data class OrderPage(
    val orders: List<OrderSummary> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageCount: Int = 1,
) {
    val hasMore: Boolean get() = page < pageCount
}

// MARK: - Date Helpers (parse UTC ISO -> render in venue timezone)

private fun parseTimeDisplay(isoString: String): String {
    val instant = VenueDateTimeFormatter.parseIso(isoString) ?: return ""
    return instant.let { VenueTimeZone.at(it) }
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}

private fun parseDateGroup(isoString: String): String {
    val instant = VenueDateTimeFormatter.parseIso(isoString) ?: return ""
    val date = VenueTimeZone.at(instant).toLocalDate()
    val today = VenueTimeZone.now().toLocalDate()
    return when {
        date == today -> "Hoy"
        date == today.minusDays(1) -> "Ayer"
        else -> date.format(DateTimeFormatter.ofPattern("d 'de' MMMM", Locale("es", "MX")))
    }
}
