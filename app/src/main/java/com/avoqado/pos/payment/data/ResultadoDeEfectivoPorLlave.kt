package com.avoqado.pos.payment.data

import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import kotlinx.serialization.json.*
import java.math.BigDecimal

/** Adapta una lectura de Payment al contrato original; no decide importes ni vuelve a cobrar. */
internal object ResultadoDeEfectivoPorLlave {
    data class Prueba(val orderId: String, val externalId: String?, val respuesta: String)

    fun captura(fila: PendingPaymentEntity): CapturaDelCobro? {
        if (fila.syncStatus != "PENDING" || fila.q15Reason != ConciliacionDelCobro.RESULTADO_NO_COMPROBADO ||
            fila.q15InitialResultJson != null || fila.q15ResultJson != null || fila.q15ReviewedAt != null ||
            fila.q15ReviewedBy != null || fila.q15ReviewRole != null || fila.method != "CASH" ||
            fila.tenderTypeId != null || fila.tenderRevision != null || fila.id.isBlank() || fila.id.length > 64 ||
            fila.venueId.isBlank() || fila.paymentType !in setOf("ORDER", "FAST") || fila.orderId?.isBlank() == true) return null
        val c = ConciliacionDelCobro.leerCaptura(fila.q15CaptureJson, fila.venueId, fila.id) ?: return null
        if (ConciliacionDelCobro.guardarCaptura(c) != fila.q15CaptureJson || c.method != "CASH" || c.tenderTypeId != null || c.tenderRevision != null ||
            c.amountCents != fila.amountCents || c.tipCents != fila.tipCents || c.splitType != fila.splitType ||
            c.customerId != fila.customerId || c.cashTenderedCents != fila.cashTenderedCents ||
            c.changeCents != fila.changeCents || c.orderRequestJson != fila.orderRequestJson ||
            c.orderId?.isBlank() == true || c.localOrderId?.isBlank() == true) return null
        val referencias = listOfNotNull(fila.orderId, c.orderId, c.localOrderId, external(c.orderRequestJson))
        if (fila.capturedOrderRef?.let { it.isBlank() || it !in referencias } == true ||
            (fila.paymentType == "ORDER" && referencias.isEmpty()) ||
            (fila.paymentType == "FAST" && fila.orderRequestJson != null)) return null
        return c
    }

    fun external(pedido: String?): String? = pedido?.let {
        runCatching { (Json.parseToJsonElement(it) as? JsonObject)?.texto("externalId") }.getOrNull()
    }

    fun normalizar(fila: PendingPaymentEntity, body: String): Prueba? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if ((root["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != true) return null
        val p = root["data"] as? JsonObject ?: return null
        if (p.texto("idempotencyKey") != fila.id || p.texto("venueId") != fila.venueId ||
            p.texto("status") != "COMPLETED" || p.texto("method") != "CASH" ||
            p.texto("type") != if (fila.paymentType == "FAST") "FAST" else "REGULAR") return null
        val id = p.texto("paymentId") ?: return null
        val order = p.texto("orderId") ?: return null
        if (p["orderExternalId"] != JsonNull && p.texto("orderExternalId") == null) return null
        val amount = p.entero("amountCents") ?: return null
        val tip = p.entero("tipCents") ?: return null
        val retained = p.entero("cashRetainedCents") ?: return null
        if (amount.toLong() + tip > Int.MAX_VALUE) return null
        val allocation = if (p.containsKey("deliveryCashAllocation")) p["deliveryCashAllocation"] as? JsonObject ?: return null else null
        val terceros = if (allocation != null) {
            if (fila.paymentType != "ORDER" || allocation.entero("saleCents") != amount || allocation.entero("tipCents") != tip) return null
            allocation.entero("thirdPartyCents") ?: return null
        } else 0
        if (amount.toLong() + tip + terceros != retained.toLong()) return null
        val flujo = p["fundsFlow"]
        if (flujo != JsonNull && p.texto("fundsFlow") != "CASH_DRAWER") return null
        if (p["tenderTypeId"] == JsonNull) {
            if (p["tenderRevision"] != JsonNull) return null
        } else if (fila.paymentType != "ORDER" || p.texto("fundsFlow") != "CASH_DRAWER" ||
            allocation == null || p.texto("tenderTypeId") == null || (p.entero("tenderRevision") ?: 0) <= 0) return null
        val saldo = p.entero("remainingBalanceCents") ?: return null
        val estado = p.texto("orderPaymentStatus") ?: return null
        // PAID también representa una orden reembolsada y manda aunque el saldo actual sea positivo.
        if (estado !in setOf("PAID", "PARTIAL")) return null
        val split = p.texto("splitType") ?: return null
        val pago = buildJsonObject {
            put(if (fila.paymentType == "FAST") "id" else "paymentId", id)
            put("orderId", order); put("venueId", fila.venueId); put("status", "COMPLETED"); put("method", "CASH")
            if (fila.paymentType == "FAST") {
                put("amount", BigDecimal(amount).movePointLeft(2).toPlainString())
                put("tipAmount", BigDecimal(tip).movePointLeft(2).toPlainString())
            } else {
                put("amount", amount); put("tipAmount", tip)
                put("remainingBalanceCents", saldo); put("orderPaymentStatus", estado)
            }
            put("splitType", split); put("cashRetainedCents", retained)
            put("tenderTypeId", p.getValue("tenderTypeId")); put("tenderRevision", p.getValue("tenderRevision"))
            allocation?.let { put("deliveryCashAllocation", it) }
        }
        return Prueba(order, p.texto("orderExternalId"), buildJsonObject {
            put("success", true); put(if (fila.paymentType == "FAST") "data" else "payment", pago)
        }.toString())
    }

    private fun JsonObject.texto(key: String): String? = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotBlank() }?.content
    private fun JsonObject.entero(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }
        ?.content?.let { runCatching { BigDecimal(it).intValueExact() }.getOrNull() }?.takeIf { it >= 0 }
}
