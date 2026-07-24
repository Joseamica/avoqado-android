package com.avoqado.pos.core.data.lan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Hub LAN, capa 2 — PROTOCOLO de leases sobre la red local.
 *
 * JSON delimitado por saltos de línea sobre TCP crudo. Sin HTTP ni dependencias
 * nuevas: el protocolo son 4 operaciones y meter un servidor HTTP en un POS de
 * batería para eso sería desproporcionado.
 *
 * Este archivo es PURO (encode/decode) a propósito, igual que el núcleo de
 * leases: el socket queda como una capa tonta encima, y la compatibilidad entre
 * plataformas se prueba con tests en vez de con dos dispositivos en la mano.
 *
 * Espejo EXACTO en avoqado-ios: Services/LAN/LeaseProtocol.swift. El formato de
 * cable tiene que ser IDÉNTICO byte a byte — una tablet Android y un iPad tienen
 * que poder arbitrarse entre sí.
 *
 * ── Por qué va versionado ──────────────────────────────────────────────────
 * Las apps móviles tardan DÍAS en llegar a todos los dispositivos (regla del
 * workspace: primero el backend, luego los APK). Durante esa ventana convive un
 * POS nuevo con uno viejo en la misma red. [PROTOCOL_VERSION] deja que el
 * árbitro detecte a un peer que habla otra versión y lo rechace explícitamente
 * en vez de malinterpretar su payload.
 */
object LeaseProtocol {

    /** Subir SOLO en cambios incompatibles del formato de cable. */
    const val PROTOCOL_VERSION = 1

    /** Tipo de servicio mDNS/NSD que anuncian y buscan todos los POS. */
    const val SERVICE_TYPE = "_avoqado-pos._tcp"

    /** Claves de los TXT records del anuncio (la elección se resuelve con esto,
     *  sin necesidad de conectarse a cada peer). */
    const val TXT_DEVICE_ID = "did"
    const val TXT_WIRED = "wired"
    const val TXT_BOOTED_AT = "boot"
    const val TXT_VENUE_ID = "venue"

    val json = Json {
        ignoreUnknownKeys = true // un peer más nuevo puede mandar campos extra
        encodeDefaults = true
    }

    fun encode(request: LeaseRequest): String = json.encodeToString(LeaseRequest.serializer(), request)

    fun encode(response: LeaseResponse): String = json.encodeToString(LeaseResponse.serializer(), response)

    fun decodeRequest(line: String): LeaseRequest? =
        runCatching { json.decodeFromString(LeaseRequest.serializer(), line.trim()) }.getOrNull()

    fun decodeResponse(line: String): LeaseResponse? =
        runCatching { json.decodeFromString(LeaseResponse.serializer(), line.trim()) }.getOrNull()

    /** Traduce el resultado del registro (núcleo) a respuesta de cable. */
    fun toResponse(result: LeaseResult): LeaseResponse = when (result) {
        is LeaseResult.Granted -> LeaseResponse(status = STATUS_GRANTED, lease = result.lease.toWire())
        is LeaseResult.Denied -> LeaseResponse(status = STATUS_DENIED, holder = result.holder.toWire())
        is LeaseResult.Stale -> LeaseResponse(status = STATUS_STALE, currentEpoch = result.currentEpoch)
    }

    const val OP_ACQUIRE = "acquire"
    const val OP_RENEW = "renew"
    const val OP_RELEASE = "release"
    const val OP_LIST = "list"

    const val STATUS_GRANTED = "granted"
    const val STATUS_DENIED = "denied"
    const val STATUS_STALE = "stale"
    const val STATUS_LEASES = "leases"
    const val STATUS_ERROR = "error"
    const val STATUS_OK = "ok"

    /** Rechazo explícito a un peer que habla otra versión del protocolo. */
    const val ERROR_VERSION_MISMATCH = "VERSION_MISMATCH"
}

/** Lease tal como viaja por el cable (plano, sin lógica). */
@Serializable
data class WireLease(
    val tableId: String,
    val holderDeviceId: String,
    val holderStaffId: String,
    val holderName: String,
    val epoch: Long,
    val expiresAtMillis: Long,
)

fun TableLease.toWire(): WireLease = WireLease(tableId, holderDeviceId, holderStaffId, holderName, epoch, expiresAtMillis)

fun WireLease.toDomain(): TableLease = TableLease(tableId, holderDeviceId, holderStaffId, holderName, epoch, expiresAtMillis)

@Serializable
data class LeaseRequest(
    @SerialName("v") val version: Int = LeaseProtocol.PROTOCOL_VERSION,
    val op: String,
    val tableId: String = "",
    val deviceId: String = "",
    val staffId: String = "",
    val staffName: String = "",
    /** Solo en renew/release: la época que el emisor CREE tener. */
    val epoch: Long = 0,
)

@Serializable
data class LeaseResponse(
    @SerialName("v") val version: Int = LeaseProtocol.PROTOCOL_VERSION,
    val status: String,
    val lease: WireLease? = null,
    val holder: WireLease? = null,
    val currentEpoch: Long? = null,
    val leases: List<WireLease>? = null,
    val message: String? = null,
)
