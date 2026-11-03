package com.avoqado.pos.payment.data

import kotlinx.serialization.json.*

/** Both the live creation and durable replay must prove the same original comp before any first payment. */
internal object CortesiaPersistida {
    const val AVISO = "El servidor no confirmó la cortesía original. El intento se conserva para revisión; no vuelvas a cobrar."
    private fun objeto(raw: String) = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    private fun JsonObject.texto(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.esCortesia() = (get("isCortesia") as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true
    fun solicitada(pedido: String?) = pedido?.let(::objeto)?.get("items")?.let { it as? JsonArray }
        ?.any { (it as? JsonObject)?.esCortesia() == true } == true
    fun confirmada(pedido: String, respuesta: String): Boolean {
        val original = objeto(pedido) ?: return false
        val solicitadas = (original["items"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return false
        val cortesias = solicitadas.filter { it.esCortesia() }
        if (cortesias.isEmpty()) return true
        val root = objeto(respuesta) ?: return false
        val order = (root["data"] ?: root["order"]) as? JsonObject ?: return false
        val total = order["total"] as? JsonPrimitive ?: return false
        if (total.isString || total.doubleOrNull?.let { it.isFinite() && it >= 0 } != true) return false
        // The order request stores cents; the server returns pesos. A higher canonical total would turn
        // the captured amount into a silent partial payment. A lower price keeps the existing price policy.
        val previsto = (original["total"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 } ?: return false
        val canonico = runCatching { total.content.toBigDecimal().movePointRight(2) }.getOrNull() ?: return false
        if (canonico > previsto.toBigDecimal()) return false
        val guardadas = (order["items"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return false
        val ids = cortesias.map { it.texto("externalId")?.takeIf(String::isNotBlank) ?: return false }
        if (ids.distinct().size != ids.size) return false
        return cortesias.all { original ->
            val motivo = original.texto("cortesiaReason")?.trim()?.takeIf { it.isNotEmpty() } ?: return@all false
            val actual = guardadas.singleOrNull { it.texto("externalId") == original.texto("externalId") } ?: return@all false
            actual.esCortesia() && actual.texto("cortesiaReason")?.trim() == motivo
        }
    }
}
