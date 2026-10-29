package com.avoqado.pos.transactions.data.model

import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.core.util.formatMoney

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Single source of truth for turning a backend `PaymentMethod` code into a
 * Spanish label. Mirrors backend enum (avoqado-server `prisma/schema.prisma`
 * `PaymentMethod`): CASH, CREDIT_CARD, DEBIT_CARD, DIGITAL_WALLET,
 * BANK_TRANSFER, CRYPTOCURRENCY, OTHER. "CARD" and legacy "TRANSFER" are kept
 * as harmless aliases (older payment rows). Matches iOS's
 * `PaymentMethodDisplay` by exact label text.
 */
object PaymentMethodDisplay {
    fun label(method: String?, cardBrand: String? = null, maskedPan: String? = null): String =
        when (method) {
            "CASH" -> "Efectivo"
            "CARD" -> {
                val brand = cardBrand ?: ""
                if (!maskedPan.isNullOrEmpty() && maskedPan.length >= 4) {
                    val last4 = maskedPan.takeLast(4)
                    "$brand ****$last4".trim()
                } else {
                    brand.ifEmpty { "Tarjeta" }
                }
            }
            "CREDIT_CARD" -> "Tarjeta de crédito"
            "DEBIT_CARD" -> "Tarjeta de débito"
            "DIGITAL_WALLET" -> "Cartera digital"
            "BANK_TRANSFER", "TRANSFER" -> "Transferencia"
            "CRYPTOCURRENCY" -> "Criptomoneda"
            "GIFT_CARD" -> "Tarjeta de regalo"
            "OTHER" -> "Otro"
            null -> "—"
            else -> method.replace("_", " ").replaceFirstChar { it.uppercase() }
        }
}

/**
 * El comprobante COMPLETO de un cobro, tal como lo vio el cliente (servidor, aditivo, plan 7a; r2 R2-9, r3 R3-11). Pesos y
 * magnitudes; `subtotal − discount + serviceCharge + addedTax + tip = total` siempre. Con recibo digital emitido, son SUS cifras.
 * `subtotal` es el BRUTO cuando desglosa y `discount` sólo llega si desglosa y es > 0 (ruling A7-R6).
 */
@Serializable
data class TicketTotalsDto(
    val kind: String, // "COBRO" | "DEVOLUCION"
    val desglosado: Boolean,
    val subtotal: Double,
    val serviceCharge: Double,
    val addedTax: Double,
    val tip: Double,
    val total: Double,
    val discount: Double? = null,
    /** D15 (servidor, aditivo): sólo viene, en `true`, en el comprobante de UNA parte de una venta dividida. */
    val parcial: Boolean? = null,
)

/**
 * A7-R8 (A5 m5): el comprobante se lee TOLERANTE, como iOS. Si le falta un campo, trae uno raro o un `kind` desconocido, cuenta
 * como AUSENTE (`null`) y la reimpresión cae al respaldo de hoy, en vez de tumbar el detalle de la venta (sin detalle no se puede
 * reimprimir ni devolver). Nunca se rellena con ceros: el TOTAL ya trae la propina y el papel dejaría de cuadrar.
 */
internal object TicketTotalsTolerante : KSerializer<TicketTotalsDto?> {
    private val estricto = TicketTotalsDto.serializer()
    override val descriptor: SerialDescriptor = estricto.nullable.descriptor

    override fun deserialize(decoder: Decoder): TicketTotalsDto? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(estricto.nullable)
        val elemento = json.decodeJsonElement()
        return try {
            json.json.decodeFromJsonElement(estricto, elemento).takeIf { it.kind == "COBRO" || it.kind == "DEVOLUCION" }
        } catch (e: IllegalArgumentException) { // SerializationException y NumberFormatException lo son
            null
        }
    }

    override fun serialize(encoder: Encoder, value: TicketTotalsDto?) = encoder.encodeSerializableValue(estricto.nullable, value)
}

/**
 * D1 «fotocopia del ticket» (servidor, aditivo, 28-oct): los renglones que imprimió el cobro de UNA parte de una venta dividida, tal
 * cual los mandó la app en `/pay` (`CopiaDelTicket`). El SERVIDOR sólo la guarda si cuadra exacto con lo que registró:
 * `subtotalCents − descuentoCents` = el importe del pago sin propina. Centavos enteros.
 */
@Serializable
data class LineasDelTicketDto(
    val lineas: List<LineaDelTicketDto>,
    val subtotalCents: Int,
    val descuentoCents: Int,
)

@Serializable
data class LineaDelTicketDto(
    val nombre: String,
    /** Número del contrato (`> 0`); las apps mandan enteros. */
    val cantidad: Double,
    val unitarioCents: Int,
    val totalCents: Int,
    val modificadores: List<String>? = null,
    val cortesia: Boolean? = null,
    /** Componente de un combo, en $0 (el combo lleva el precio). */
    val pieza: Boolean? = null,
    /** Resumen impreso de un producto pesado («0.435 kg × $420.00/kg»). */
    val peso: String? = null,
    val nota: String? = null,
    /** Etiqueta del área de un vale. */
    val area: String? = null,
)

/**
 * La copia se lee TOLERANTE, como el comprobante ([TicketTotalsTolerante]): una copia rara o sin renglones cuenta como AUSENTE y
 * la reimpresión queda como hoy, en vez de tumbar el detalle de la venta (sin detalle no se reimprime ni se devuelve).
 */
internal object LineasDelTicketTolerante : KSerializer<LineasDelTicketDto?> {
    private val estricto = LineasDelTicketDto.serializer()
    override val descriptor: SerialDescriptor = estricto.nullable.descriptor

    override fun deserialize(decoder: Decoder): LineasDelTicketDto? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(estricto.nullable)
        val elemento = json.decodeJsonElement()
        return try {
            json.json.decodeFromJsonElement(estricto, elemento).takeIf { it.lineas.isNotEmpty() }
        } catch (e: IllegalArgumentException) { // SerializationException y NumberFormatException lo son
            null
        }
    }

    override fun serialize(encoder: Encoder, value: LineasDelTicketDto?) = encoder.encodeSerializableValue(estricto.nullable, value)
}

@Serializable
data class Transaction(
    val id: String,
    val orderNumber: String? = null,
    val status: String = "COMPLETED",
    val method: String? = null,
    val amount: Double = 0.0,
    val tipAmount: Double = 0.0,
    val items: List<TransactionItem> = emptyList(),
    val refunds: List<TransactionRefund> = emptyList(),
    val remainingRefundable: Double = 0.0,
    /**
     * Saldo POR COMPONENTE, ya descontadas las devoluciones previas (servidor, aditivo,
     * 21-sep-2026). `null` = servidor anterior: la hoja cae a «disponible − propina».
     */
    val remainingRefundableSale: Double? = null,
    val remainingRefundableTip: Double? = null,
    /** ¿La devolución se abre en la terminal? (servidor, aditivo, 30-sep-2026). `null` = servidor anterior. */
    val refundOnTerminal: Boolean? = null,
    /** ¿El cajero puede escoger con qué devolver? (servidor, aditivo, 30-sep-2026). `null` = servidor anterior. */
    val canChooseRefundMethod: Boolean? = null,
    /** Nombre del método del negocio («Vale de despensa»), para la pastilla «mismo medio». */
    val tenderLabel: String? = null,
    val customerName: String? = null,
    val customerId: String? = null,
    val canAssignCustomer: Boolean? = null,
    val createdAt: String? = null,
    val staffName: String? = null,
    val cardBrand: String? = null,
    val maskedPan: String? = null,
    val referenceNumber: String? = null,
    /** Comprobante completo (servidor, aditivo, plan 7a). Null = servidor anterior (o comprobante raro, A7-R8): se cae a importe y propina del pago. */
    @Serializable(with = TicketTotalsTolerante::class)
    val ticketTotals: TicketTotalsDto? = null,
    /** D1: la copia del papel de esta parte, APARTE de [ticketTotals]. Null = sin copia (app vieja, tarjeta, cobro de antes). */
    @Serializable(with = LineasDelTicketTolerante::class)
    val lineasDelTicket: LineasDelTicketDto? = null,
    /**
     * D4 (mesas40), aditivos: el tipo de la orden (DINE_IN, TAKEOUT…) y el número de su mesa, para que la reimpresión diga
     * «Mesa 5» / «Para llevar» como el ticket del cobro. 🔴 El servidor todavía no los manda: sin ellos, «En tienda».
     */
    val orderType: String? = null,
    val tableNumber: String? = null,
    val refundToGiftCardBalance: Boolean? = null,
    val esSaldoDeTarjeta: Boolean? = null,
) {
    val paymentMethod: String? get() = method

    /** Total = amount + tip (in dollars) */
    val totalAmount: Double get() = amount + tipAmount

    val totalDisplay: String get() = formatMoney(totalAmount)
    val remainingRefundableDisplay: String get() = formatMoney(remainingRefundable)
    val formattedAmount: String get() = formatMoney(amount)
    val formattedTip: String get() = formatMoney(tipAmount)

    /** Smart description: "Visa ****1234", "Efectivo", "Tarjeta", etc. */
    val methodDescription: String
        get() = if (esSaldoDeTarjeta == true) "Tarjeta de regalo" else PaymentMethodDisplay.label(method, cardBrand, maskedPan)

    val statusDisplay: String
        get() = when (status) {
            "COMPLETED" -> "Completada"
            "CANCELLED" -> "Cancelada"
            "PENDING" -> "Pendiente"
            "REFUNDED" -> "Reembolsada"
            else -> status
        }

    // MARK: - Date parsing (UTC -> venue timezone)

    /** Parsed [createdAt] as a [ZonedDateTime] in the active venue's timezone. */
    val parsedDateTime: ZonedDateTime?
        get() = VenueDateTimeFormatter.parseIso(createdAt)?.let { VenueTimeZone.at(it) }

    val dateGroup: String
        get() {
            val date = parsedDateTime?.toLocalDate() ?: return "Sin fecha"
            val today = VenueTimeZone.now().toLocalDate()
            return when {
                date == today -> "Hoy"
                date == today.minusDays(1) -> "Ayer"
                date.year != today.year -> date.format(
                    DateTimeFormatter.ofPattern("d 'de' MMMM, yyyy", Locale("es")),
                )
                else -> date.format(
                    DateTimeFormatter.ofPattern("d 'de' MMMM", Locale("es")),
                )
            }
        }

    val timeDisplay: String?
        get() = parsedDateTime?.format(DateTimeFormatter.ofPattern("HH:mm"))

    /** Short formatted date for detail view: "27/01/26, 11:20" */
    val dateShortDisplay: String?
        get() = parsedDateTime?.format(DateTimeFormatter.ofPattern("dd/MM/yy, HH:mm"))
}

@Serializable
data class TransactionItem(
    val id: String? = null,
    val productName: String = "",
    val quantity: Int = 1,
    val unitPrice: Double = 0.0,
    @SerialName("total")
    val amount: Double = 0.0,
    /**
     * Lo que COBRÓ el renglón completo (sin su descuento propio ni su parte del de la cuenta;
     * cortesía = 0). Servidor aditivo (IVA C2, 9-oct-2026): ausente o `null` en servidores
     * anteriores y cuando no puede repartir los descuentos ⇒ se usa `amount`, el bruto.
     */
    val chargedTotal: Double? = null,
    val productImageUrl: String? = null,
    val trackInventory: Boolean = false,
    /** Quantity already refunded across prior REFUND payments on this payment. */
    val refundedQty: Int = 0,
    /** Total amount already refunded for this orderItem (decimal). */
    val refundedAmount: Double = 0.0,
    /** Quantity still available to refund = quantity − refundedQty. */
    val remainingQty: Int = 0,
    val modifiers: List<TransactionItemModifier> = emptyList(),
) {
    val name: String get() = productName
    val formattedTotal: String get() = formatMoney(amount)
    /** Base de la devolución por artículos: lo cobrado, o el bruto si el servidor no lo manda (espejo de iOS). */
    val chargedLineTotal: Double get() = chargedTotal ?: amount
    /** True when every unit on this line has been refunded. */
    val fullyRefunded: Boolean get() = refundedQty >= quantity && quantity > 0
    /** True when some — but not all — units have been refunded. */
    val partiallyRefunded: Boolean get() = refundedQty in 1 until quantity
    /** Piezas que aún se pueden devolver (espejo de `refundableQty` en iOS). Un server viejo sin
     *  `refundedQty` manda 0 ⇒ la línea completa. */
    val refundableQty: Int get() = (quantity - refundedQty).coerceAtLeast(0)
}

@Serializable
data class TransactionRefund(
    val id: String,
    val amount: Double = 0.0,
    /** Reparto del reembolso (servidor, aditivo, 21-sep-2026); `null` en servidores anteriores. */
    val saleAmount: Double? = null,
    val tipAmount: Double? = null,
    val reason: String? = null,
    val createdAt: String? = null,
    val status: String = "COMPLETED",
) {
    val formattedAmount: String get() = "-${formatMoney(amount)}"

    /** "01/10/26, 10:54" en la zona del negocio (el servidor manda UTC). */
    val dateShortDisplay: String?
        get() = VenueDateTimeFormatter.parseIso(createdAt)?.let { VenueTimeZone.at(it) }
            ?.format(DateTimeFormatter.ofPattern("dd/MM/yy, HH:mm"))

    val reasonDisplay: String
        get() = when (reason) {
            "RETURNED_GOODS" -> "Productos devueltos"
            "ACCIDENTAL_CHARGE" -> "Cargo accidental"
            "CANCELLED_ORDER" -> "Pedido cancelado"
            "FRAUDULENT_CHARGE" -> "Cargo fraudulento"
            "OTHER" -> "Otro"
            null -> "Reembolso"
            else -> reason.replace("_", " ").lowercase()
                .replaceFirstChar { it.titlecase() }
        }
}

/**
 * 🔴 `name` es ANULABLE, y el default `""` no bastaba.
 *
 * En kotlinx.serialization un valor por defecto sólo cubre que la llave FALTE; un `null`
 * explícito en un campo no anulable lanza y tumba la decodificación de la venta ENTERA.
 * Medido en la Sunmi el 2026-09-11: el servidor mandó `"modifiers":[{"name":null}]`, la
 * app no pudo abrir el detalle, y el cajero se quedó en «Selecciona una transacción» sin
 * poder llegar siquiera al botón de reembolsar. Producción está limpia hoy (0 de 4 006
 * modificadores con nombre nulo), pero el decodificador no puede ser más estricto que el
 * contrato: un modificador sin nombre no puede costar la venta completa.
 */
@Serializable
data class TransactionItemModifier(
    val id: String? = null,
    val name: String? = null,
    val price: Double = 0.0,
)

@Serializable
data class TransactionsResponse(
    val success: Boolean = true,
    val data: List<Transaction> = emptyList(),
    val meta: PaginationMeta? = null,
)

@Serializable
data class TransactionDetailResponse(
    val success: Boolean = true,
    val transaction: Transaction? = null,
)

@Serializable
data class PaginationMeta(
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val pageCount: Int = 1,
)

// MARK: - Filter Models

enum class TransactionActiveFilter {
    METHOD, STAFF, SUBTOTAL, TIP, TOTAL,
}

enum class AmountOperator(val label: String, val symbol: String) {
    GREATER_THAN("Mayor que", ">"),
    LESS_THAN("Menor que", "<"),
    EQUAL_TO("Igual a", "="),
    BETWEEN("Entre", "entre");

    fun matches(value: Double, v1: Double, v2: Double?): Boolean = when (this) {
        GREATER_THAN -> value > v1
        LESS_THAN -> value < v1
        EQUAL_TO -> kotlin.math.abs(value - v1) < 0.01
        BETWEEN -> {
            val low = v2?.let { minOf(v1, it) } ?: v1
            val high = v2?.let { maxOf(v1, it) } ?: v1
            value in low..high
        }
    }

    fun summary(v1: Double, v2: Double?): String = when (this) {
        GREATER_THAN -> "> $${String.format("%.0f", v1)}"
        LESS_THAN -> "< $${String.format("%.0f", v1)}"
        EQUAL_TO -> "= $${String.format("%.0f", v1)}"
        BETWEEN -> "$${String.format("%.0f", v1)} – $${v2?.let { String.format("%.0f", it) } ?: "?"}"
    }
}

data class TransactionFilters(
    val methods: Set<String> = emptySet(),
    val staffNames: Set<String> = emptySet(),
    val subtotalOperator: AmountOperator? = null,
    val subtotalValue1: Double? = null,
    val subtotalValue2: Double? = null,
    val tipOperator: AmountOperator? = null,
    val tipValue1: Double? = null,
    val tipValue2: Double? = null,
    val totalOperator: AmountOperator? = null,
    val totalValue1: Double? = null,
    val totalValue2: Double? = null,
) {
    val hasActiveFilters: Boolean
        get() = methods.isNotEmpty() || staffNames.isNotEmpty() ||
            subtotalOperator != null || tipOperator != null || totalOperator != null

    fun clearAll() = TransactionFilters()

    fun apply(transactions: List<Transaction>): List<Transaction> = transactions.filter { tx ->
        if (methods.isNotEmpty() && tx.method !in methods) return@filter false
        if (staffNames.isNotEmpty()) {
            val name = tx.staffName
            if (name.isNullOrEmpty() || name !in staffNames) return@filter false
        }
        if (subtotalOperator != null && subtotalValue1 != null) {
            if (!subtotalOperator.matches(tx.amount, subtotalValue1, subtotalValue2)) return@filter false
        }
        if (tipOperator != null && tipValue1 != null) {
            if (!tipOperator.matches(tx.tipAmount, tipValue1, tipValue2)) return@filter false
        }
        if (totalOperator != null && totalValue1 != null) {
            if (!totalOperator.matches(tx.totalAmount, totalValue1, totalValue2)) return@filter false
        }
        true
    }
}

/** Confirmación pendiente: se recupera sólo al abrir SU venta con la misma identidad, nunca por el outbox. */
@Serializable
data class PendingCustomerAssignment(
    val actorId: String,
    val venueId: String,
    val paymentId: String,
    val customerId: String,
    val customerName: String,
)
