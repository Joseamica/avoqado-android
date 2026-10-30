package com.avoqado.pos.giftcards.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class SolicitudDeTarjeta(
    val v: Int = 1, val tipo: String, val llave: String, val venueId: String, val creadaEn: Long,
    val orderId: String? = null, val orderNumber: String? = null, val impresos: Set<String> = emptySet(), val cuerpo: String,
    val nuevaCuenta: Boolean = false, val foto: String? = null, val porRecuperar: Boolean = false,
    val porTerminar: Boolean = false, val restanteCents: Int? = null, val mandado: Boolean = false,
    val iniciadoPor: String? = null, val iniciadoPorNombre: String? = null,
    val deMostrador: Boolean = false,
) { override fun toString() = "SolicitudDeTarjeta(****)" }
object SolicitudesDeTarjeta {
    const val TOPE = 50
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun arreglo(crudo: String?): JsonArray? = if (crudo == null) JsonArray(emptyList()) else runCatching { json.parseToJsonElement(crudo) as? JsonArray }.getOrNull()
    private fun registro(row: JsonElement): SolicitudDeTarjeta? = runCatching {
        val obj = row.jsonObject
        if (obj.containsKey("entregaDestino") || obj["v"]?.jsonPrimitive?.intOrNull != 1 || obj["tipo"]?.jsonPrimitive?.content !in setOf("GIFT_CARD_SALE", "GIFT_CARD_REDEEM")) null
        else json.decodeFromJsonElement(SolicitudDeTarjeta.serializer(), row)
    }.getOrNull()
    fun leer(crudo: String?): List<SolicitudDeTarjeta> = arreglo(crudo)?.mapNotNull(::registro).orEmpty()
    private fun reserva(row: JsonElement): SolicitudDeTarjeta? {
        val obj = row as? JsonObject ?: return null
        if (obj["entregaDestino"] != obj["llave"]) return null
        return registro(JsonObject(obj - "entregaDestino"))?.takeIf {
            it.tipo == "GIFT_CARD_REDEEM" && it.nuevaCuenta && it.orderId == null &&
                it.porRecuperar && !it.porTerminar && it.foto != null && it.cuerpo.isEmpty()
        }
    }
    fun reservas(crudo: String?): List<SolicitudDeTarjeta> = arreglo(crudo)?.mapNotNull(::reserva).orEmpty()
    fun reservarEntrega(crudo: String?, llave: String): String? {
        val rows = arreglo(crudo) ?: return null
        val i = rows.indexOfFirst { registro(it)?.let { s -> s.llave == llave && s.tipo == "GIFT_CARD_REDEEM" && s.nuevaCuenta && s.orderId == null && s.foto != null } == true }
        if (i < 0) return crudo.takeIf { reservas(it).any { s -> s.llave == llave } }
        return JsonArray(rows.mapIndexed { n, row -> if (n != i) row else JsonObject(row.jsonObject + mapOf(
            "entregaDestino" to JsonPrimitive(llave), "porRecuperar" to JsonPrimitive(true),
            "porTerminar" to JsonPrimitive(false), "cuerpo" to JsonPrimitive(""))) }).toString()
    }
    fun devolverEntrega(crudo: String?, s: SolicitudDeTarjeta): String? {
        val rows = arreglo(crudo) ?: return null
        if (rows.none { reserva(it) == s }) return null
        return JsonArray(rows.map { if (reserva(it) == s) JsonObject(it.jsonObject - "entregaDestino") else it }).toString()
    }
    fun confirmarEntrega(crudo: String?, llave: String): String? {
        val rows = arreglo(crudo) ?: return null
        if (rows.none { reserva(it)?.llave == llave }) return null
        return JsonArray(rows.filter { reserva(it)?.llave != llave }).toString()
    }
    fun cuentaTrabada(crudo: String?, venueId: String, orderId: String): Boolean = leer(crudo).any {
        it.tipo == "GIFT_CARD_REDEEM" && it.venueId == venueId && it.orderId == orderId &&
            it.mandado && !it.porTerminar && !it.porRecuperar
    }
    fun agregar(crudo: String?, s: SolicitudDeTarjeta): String? {
        val rows = arreglo(crudo) ?: return null
        val existente = rows.firstOrNull { (it as? JsonObject)?.get("llave") == JsonPrimitive(s.llave) }
        if (existente != null) return if (registro(existente) == s) crudo else null
        if (rows.size >= TOPE) return null
        return JsonArray(rows + json.encodeToJsonElement(SolicitudDeTarjeta.serializer(), s)).toString()
    }
    fun quitar(crudo: String?, llave: String): String {
        val rows = arreglo(crudo) ?: return crudo ?: "[]"
        val nuevas = rows.filter { registro(it)?.llave != llave }
        return if (nuevas.size == rows.size) crudo ?: "[]" else JsonArray(nuevas).toString()
    }
    private fun mutar(crudo: String?, llave: String, cambio: (JsonObject) -> JsonObject): String {
        val rows = arreglo(crudo) ?: return crudo ?: "[]"
        val nuevas = rows.map { if (registro(it)?.llave == llave) cambio(it.jsonObject) else it }
        return if (nuevas == rows) crudo ?: "[]" else JsonArray(nuevas).toString()
    }
    fun conOrden(crudo: String?, llave: String, orderId: String, orderNumber: String?): String = mutar(crudo, llave) {
        JsonObject(it + mapOf("orderId" to JsonPrimitive(orderId), "orderNumber" to JsonPrimitive(orderNumber)))
    }
    fun conImpreso(crudo: String?, llave: String, giftCardId: String): String = mutar(crudo, llave) {
        val ids = it["impresos"]?.jsonArray.orEmpty().toSet() + JsonPrimitive(giftCardId)
        JsonObject(it + ("impresos" to JsonArray(ids.toList())))
    }
    fun conMandado(crudo: String?, llave: String): String = mutar(crudo, llave) { JsonObject(it + ("mandado" to JsonPrimitive(true))) }
    fun conPorTerminar(crudo: String?, llave: String, orderId: String, orderNumber: String?, restanteCents: Int?): String = mutar(crudo, llave) {
        JsonObject(it + mapOf("porTerminar" to JsonPrimitive(true), "porRecuperar" to JsonPrimitive(false),
            "orderId" to JsonPrimitive(orderId), "orderNumber" to JsonPrimitive(orderNumber),
            "restanteCents" to (restanteCents?.let(::JsonPrimitive) ?: JsonNull), "cuerpo" to JsonPrimitive("")))
    }
    fun conPorRecuperar(crudo: String?, llave: String): String = mutar(crudo, llave) {
        JsonObject(it + mapOf("porRecuperar" to JsonPrimitive(true), "porTerminar" to JsonPrimitive(false), "cuerpo" to JsonPrimitive("")))
    }
    fun reenviarDesde(crudo: String?, llave: String): Long? = arreglo(crudo)?.firstOrNull { registro(it)?.llave == llave }
        ?.jsonObject?.get("reenviarDesde")?.jsonPrimitive?.longOrNull
    fun conReenvioDesde(crudo: String?, llave: String, hasta: Long): String = mutar(crudo, llave) {
        JsonObject(it + ("reenviarDesde" to JsonPrimitive(hasta)))
    }
    fun ilegible(crudo: String?): String? = crudo?.takeIf { arreglo(it) == null }
}
