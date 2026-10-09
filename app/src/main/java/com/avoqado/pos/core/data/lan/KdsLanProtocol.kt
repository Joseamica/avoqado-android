package com.avoqado.pos.core.data.lan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Etapa 3 del KDS, fase 3.5 — la operación `comanda` sobre la red local (spec §6, ruling D4). Su propio tipo de
 * mensaje: `LeaseRequest`/`LeaseResponse` quedan intactos y `PROTOCOL_VERSION` NO sube — un aparato viejo decodifica
 * `{"v":1,"op":"comanda",…}` como `LeaseRequest` (ignora lo que no conoce) y contesta «Operación desconocida: comanda»,
 * que aquí es SIN ACUSE ⇒ la caja imprime el papel de respaldo. Compatibilidad sin negociar nada.
 *
 * Espejo EXACTO en avoqado-ios: Services/LAN/KdsLanProtocol.swift. Los JSON literales de `KdsLanProtocolTest` son el
 * contrato. Kotlin manda los nulos EXPLÍCITOS (`encodeDefaults = true`, el mismo `json` del hub); Swift los omite.
 */
object KdsLanProtocol {

    const val OP_COMANDA = "comanda"

    private val json get() = LeaseProtocol.json

    fun encode(comanda: KdsComanda): String = json.encodeToString(KdsComanda.serializer(), comanda)

    fun encode(ack: KdsComandaAck): String = json.encodeToString(KdsComandaAck.serializer(), ack)

    fun decodeComanda(line: String): KdsComanda? =
        runCatching { json.decodeFromString(KdsComanda.serializer(), line.trim()) }.getOrNull()

    fun decodeAck(line: String): KdsComandaAck? =
        runCatching { json.decodeFromString(KdsComandaAck.serializer(), line.trim()) }.getOrNull()

    /** Sólo el `op` de una línea, sin decodificar el resto: con esto rutea el transporte. `null` = ilegible o sin `op`. */
    fun opDe(line: String): String? =
        runCatching { json.parseToJsonElement(line.trim()).jsonObject["op"]?.jsonPrimitive?.content }.getOrNull()

    /**
     * 🔴 Acuse = SÓLO `{"v":1,"status":"ok","sourceKey":<el mismo>}`. Un error, otro folio, otra versión, basura o nada
     * son «sin acuse» ⇒ papel. Un acuse laxo escondería una comanda que nadie vio: la respuesta de un aparato viejo, o
     * la de otra entrega que se cruzó.
     */
    fun esAcuse(line: String?, sourceKey: String, preparationVersion: Int = 0): Boolean {
        val ack = line?.let { decodeAck(it) } ?: return false
        return ack.version == LeaseProtocol.PROTOCOL_VERSION && ack.status == LeaseProtocol.STATUS_OK && ack.sourceKey == sourceKey &&
            (preparationVersion == 0 || ack.preparationVersion == preparationVersion)
    }

    fun acuse(sourceKey: String, preparationVersion: Int = 0) = KdsComandaAck(status = LeaseProtocol.STATUS_OK,
        sourceKey = sourceKey, preparationVersion = preparationVersion.takeIf { it > 0 })

    fun rechazo(mensaje: String) = KdsComandaAck(status = LeaseProtocol.STATUS_ERROR, message = mensaje)
}

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class KdsComandaItem(
    /** `ConsolidatedLine.orderItemIds.first()`, o `"<sourceKey>#<idx>"` si no hay. Sólo para mostrar y para UNIR cursos. */
    val id: String,
    val productName: String,
    val quantity: Int,
    val modifiers: List<String> = emptyList(),
    val notes: String? = null,
    /** KDS 3.6: el tiempo del platillo («Aperitivos»); `null` = sin tiempo. Swift lo OMITE cuando es nil. */
    val course: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val serviceCourse: com.avoqado.pos.pos.data.model.ServiceCourseSnapshot? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val externalId: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val orderPromotionId: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val preparation: com.avoqado.pos.kds.domain.PreparationCounts? = null,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class KdsComanda(
    @SerialName("v") val version: Int = LeaseProtocol.PROTOCOL_VERSION,
    val op: String = KdsLanProtocol.OP_COMANDA,
    val venueId: String,
    /** El emisor (el `deviceId` del outbox de la caja). */
    val deviceId: String,
    /** El folio de la comanda — el MISMO del servidor (`KitchenDeliveryPolicy.folio`). */
    val sourceKey: String,
    val stationId: String,
    val orderNumber: String,
    /** Texto para mostrar arriba: «En tienda», «Mesa 8». El tiempo va en cada renglón ([KdsComandaItem.course]). */
    val orderType: String,
    val orderId: String? = null,
    val createdAtMillis: Long,
    val items: List<KdsComandaItem>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val preparationVersion: Int = 0,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class KdsComandaAck(
    @SerialName("v") val version: Int = LeaseProtocol.PROTOCOL_VERSION,
    val status: String,
    val sourceKey: String? = null,
    val message: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val preparationVersion: Int? = null,
)
