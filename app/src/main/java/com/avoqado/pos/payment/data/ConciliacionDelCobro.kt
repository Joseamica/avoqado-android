package com.avoqado.pos.payment.data

import kotlinx.serialization.json.*
import java.math.BigDecimal

enum class CanalDelCobro { ORDER, FAST, ACK }

/** Evidencia local del importe elegido; no es prueba servidor del precio antiguo (Q16). */
data class CapturaDelCobro(
    val venueId: String, val key: String, val orderId: String? = null,
    val amountCents: Int, val tipCents: Int, val splitType: String? = null,
    val method: String? = null, val tenderTypeId: String? = null, val tenderRevision: Int? = null,
    val cashTenderedCents: Int? = null, val changeCents: Int? = null,
    val customerId: String? = null, val localOrderId: String? = null, val version: Int = 1,
    val orderRequestJson: String? = null, val intentoPayloadJson: String? = null,
)

data class EvaluacionDelCobro(
    val aplicado: Boolean, val motivo: String?, val resultadoJson: String?,
    val camposDiferentes: List<String> = emptyList(), val camposNoComprobados: List<String> = emptyList(),
)

object ConciliacionDelCobro {
    const val DIFERENCIA = "DIFERENCIA_DE_COBRO"
    const val RESULTADO_NO_COMPROBADO = "RESULTADO_NO_COMPROBADO"
    const val CAPTURA_SIN_EVIDENCIA = "CAPTURA_SIN_EVIDENCIA"
    const val PRICE_PROOF_PENDING = "PRICE_PROOF_PENDING"

    fun guardarCaptura(c: CapturaDelCobro): String {
        require(c.version == 1 && c.venueId.isNotBlank() && c.key.isNotBlank()) { "No se pudo guardar la captura del cobro" }
        require(c.amountCents >= 0 && c.tipCents >= 0 && c.amountCents.toLong() + c.tipCents <= Int.MAX_VALUE) { "Importe del cobro fuera de rango" }
        require(c.cashTenderedCents == null || c.cashTenderedCents >= 0) { "Efectivo recibido fuera de rango" }
        require(c.changeCents == null || c.changeCents >= 0) { "Cambio fuera de rango" }
        require(c.tenderTypeId == null || c.tenderTypeId.isNotBlank() && c.tenderRevision != null && c.tenderRevision >= 0) { "Tipo de cobro sin revisión" }
        return buildJsonObject {
            put("version", c.version); put("venueId", c.venueId); put("key", c.key)
            c.orderId?.let { put("orderId", it) }; c.localOrderId?.let { put("localOrderId", it) }
            put("amountCents", c.amountCents); put("tipCents", c.tipCents)
            c.splitType?.let { put("splitType", it) }; c.method?.let { put("method", it) }
            c.tenderTypeId?.let { put("tenderTypeId", it) }; c.tenderRevision?.let { put("tenderRevision", it) }
            c.cashTenderedCents?.let { put("cashTenderedCents", it) }; c.changeCents?.let { put("changeCents", it) }
            c.customerId?.let { put("customerId", it) }
            c.orderRequestJson?.let { put("orderRequestJson", it) }; c.intentoPayloadJson?.let { put("intentoPayloadJson", it) }
        }.toString()
    }

    fun capturarIntent(venueId: String, key: String, payloadJson: String,
        cashTenderedCents: Int? = null, changeCents: Int? = null, splitType: String? = null): String? {
        val p = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return null
        val local = text(p["localOrderId"])?.takeIf { it.isNotBlank() }
        val order = text(p["orderId"])?.takeIf { it.isNotBlank() }
        if (local == null && order == null) return null
        val amount = integer(p["amountCents"]) ?: return null
        val tip = integer(p["tipCents"]) ?: return null
        val tender = text(p["tenderTypeId"])
        return guardarCaptura(CapturaDelCobro(venueId, key, order, amount, tip, splitType,
            if (tender == null) text(p["method"]) ?: "CASH" else null,
            tender, integer(p["tenderRevision"]), cashTenderedCents, changeCents,
            text(p["customerId"]), local, intentoPayloadJson = payloadJson))
    }

    fun leerCaptura(json: String?, venueId: String, key: String): CapturaDelCobro? = runCatching {
        val o = Json.parseToJsonElement(json ?: return null).jsonObject
        if (integer(o["version"]) != 1 || text(o["venueId"]) != venueId || text(o["key"]) != key) return null
        CapturaDelCobro(venueId, key, text(o["orderId"]), integer(o["amountCents"]) ?: return null,
            integer(o["tipCents"]) ?: return null, text(o["splitType"]), text(o["method"]),
            text(o["tenderTypeId"]), integer(o["tenderRevision"]), integer(o["cashTenderedCents"]),
            integer(o["changeCents"]), text(o["customerId"]), text(o["localOrderId"]),
            orderRequestJson = text(o["orderRequestJson"]), intentoPayloadJson = text(o["intentoPayloadJson"]))
            .also { guardarCaptura(it) }
    }.getOrNull()

    fun evaluar(captureJson: String?, venueId: String, key: String, canal: CanalDelCobro, body: String, aliasComprobado: Pair<String, String>? = null): EvaluacionDelCobro {
        val c = leerCaptura(captureJson, venueId, key)
            ?: return EvaluacionDelCobro(false, CAPTURA_SIN_EVIDENCIA, null)
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return EvaluacionDelCobro(false, RESULTADO_NO_COMPROBADO, null)
        val payment = when (canal) {
            CanalDelCobro.ACK -> root
            CanalDelCobro.ORDER -> if ((root["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true) root["payment"] as? JsonObject else null
            CanalDelCobro.FAST -> if ((root["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true) root["data"] as? JsonObject else null
        } ?: return EvaluacionDelCobro(false, RESULTADO_NO_COMPROBADO, null)
        val paymentId = text(payment[if (canal == CanalDelCobro.FAST) "id" else "paymentId"])
        val orderId = text(payment["orderId"])
        val validIdentity = !paymentId.isNullOrBlank() && !orderId.isNullOrBlank() &&
            (c.orderId == null || c.orderId == orderId || (aliasComprobado?.first == c.orderId && aliasComprobado?.second == orderId)) &&
            (text(payment["venueId"]) == null || text(payment["venueId"]) == venueId) &&
            (canal == CanalDelCobro.ACK || text(payment["status"]) == "COMPLETED")
        if (!validIdentity) return EvaluacionDelCobro(false, RESULTADO_NO_COMPROBADO, null)

        val amount = if (canal == CanalDelCobro.FAST) pesos(payment["amount"]) else integer(payment["amount"])
        val tip = if (canal == CanalDelCobro.FAST) pesos(payment["tipAmount"]) else integer(payment["tipAmount"])
        val missing = mutableListOf<String>()
        val different = mutableListOf<String>()
        fun compare(field: String, original: Any?, actual: Any?) {
            if (original == null) return
            if (actual == null) missing.add(field) else if (original != actual) different.add(field)
        }
        compare("amountCents", c.amountCents, amount); compare("tipCents", c.tipCents, tip)
        compare("method", c.method, text(payment["method"]))
        compare("splitType", c.splitType, text(payment["splitType"]))
        compare("tenderTypeId", c.tenderTypeId, text(payment["tenderTypeId"]))
        compare("tenderRevision", c.tenderRevision, integer(payment["tenderRevision"]))
        val result = buildJsonObject {
            put("paymentId", paymentId); put("orderId", orderId)
            amount?.let { put("amountCents", it) }; tip?.let { put("tipCents", it) }
            for (field in listOf("method", "splitType", "tenderTypeId")) text(payment[field])?.let { put(field, it) }
            integer(payment["tenderRevision"])?.let { put("tenderRevision", it) }
            put("camposDiferentes", JsonArray(different.map(::JsonPrimitive)))
            put("camposNoComprobados", JsonArray(missing.map(::JsonPrimitive)))
        }.toString()
        // Sólo importe/propina son obligatorios en el contrato económico común.
        // Los campos opcionales ausentes quedan explícitos; jamás se certifica su igualdad.
        val motivo = when {
            amount == null || tip == null || amount.toLong() + tip > Int.MAX_VALUE -> RESULTADO_NO_COMPROBADO
            different.isNotEmpty() -> DIFERENCIA
            else -> null
        }
        return EvaluacionDelCobro(true, motivo, result, different, missing)
    }

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun integer(value: JsonElement?): Int? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let {
        runCatching { BigDecimal(it).intValueExact() }.getOrNull()?.takeIf { n -> n >= 0 }
    }
    private fun pesos(value: JsonElement?): Int? = (value as? JsonPrimitive)?.content?.let {
        runCatching { BigDecimal(it).movePointRight(2).intValueExact() }.getOrNull()?.takeIf { n -> n >= 0 }
    }
}
