package com.avoqado.pos.core.data.lan

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

    /**
     * Etapa 3 del KDS (3.5, D1): estaciones cuyo Tablero está EN PANTALLA en ese aparato, separadas por coma. Ausente
     * = ese aparato no recibe comandas por WiFi (una tablet que eligió estación una vez y hoy está en Cobrar NO acusa).
     */
    const val TXT_KDS = "kds"

    /** Etapa 3 del KDS (3.5, D1): `1` si el aparato contesta leases (hub Premium encendido), `0` si no. Ausente = app vieja = sí. */
    const val TXT_HUB = "hub"

    /** D12 · Opción D: `1` si el aparato contesta las cuentas que abrió (R5: sólo se confía en quien lo anuncia). Ausente = no. */
    const val TXT_D = "d"

    /** D12 · Opción D: el nombre visible de la caja («Caja 1 · Barra»); una etiqueta, nunca la identidad. */
    const val TXT_CAJA = "caja"

    /** Hub D3 (mesa declarada): `1` si el aparato habla [OP_ESTADO] (cada caja declara en qué mesa está). Ausente = habla acquire/renew/release. */
    const val TXT_PRESENCIA = "pm"

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

    /** Hub D3: la caja declara su estado (mesas en las que está y aperturas que pide); el árbitro contesta [STATUS_OK]. */
    const val OP_ESTADO = "estado"
    const val APERTURA_CONCEDIDA = "concedida"
    const val APERTURA_OCUPADA = "ocupada"
    const val APERTURA_ESPERAR = "esperar"

    const val STATUS_GRANTED = "granted"
    const val STATUS_DENIED = "denied"
    const val STATUS_STALE = "stale"
    const val STATUS_LEASES = "leases"
    const val STATUS_ERROR = "error"
    const val STATUS_OK = "ok"

    /** D12 · Opción D (`CuentasPorLaRed`): sólo para llaves `cuenta:<orderId>`, y sólo las contesta la caja que la abrió. */
    const val OP_CUENTA_APARTAR = "cuenta_apartar"
    const val OP_CUENTA_COMPROMETER = "cuenta_comprometer"
    const val OP_CUENTA_SOLTAR = "cuenta_soltar"
    const val OP_CUENTA_COBRADA = "cuenta_cobrada"
    const val STATUS_NO_ES_MIA = "no_es_mia"
    const val STATUS_CERRADA = "cerrada"
    const val STATUS_RECHAZADA = "rechazada"

    /**
     * D12 · tubería (hz:e5ee98bc:D4): «¿quién eres?» por TCP, para la caja que NSD perdió entre las bandas del WiFi. Contesta
     * [STATUS_OK] con el MISMO TXT del anuncio en `txt`; una app vieja contesta error y esa caja no cuenta por TCP (`CajasRecordadas`).
     */
    const val OP_QUIEN = "quien"

    /** Rechazo explícito a un peer que habla otra versión del protocolo. */
    const val ERROR_VERSION_MISMATCH = "VERSION_MISMATCH"

    /** Hub D3: acquire/renew/release de un aparato que ya declara su estado (sus permisos viejos se adoptaron). */
    const val ERROR_EN_PRESENCIA = "en presencia"
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

/** Hub D3: una apertura de mesa que la caja pide (`id` la identifica al contestar). */
@Serializable
data class WireApertura(val id: String, val tableId: String)

/** Hub D3: el veredicto del árbitro a una [WireApertura]: concedida, ocupada (`quien`) o esperar (`esperarMs`). */
@Serializable
data class WireResultadoApertura(val id: String, val resultado: String, val quien: String? = null, val esperarMs: Long? = null)

@OptIn(ExperimentalSerializationApi::class)
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
    /** D12 · Opción D (ops `cuenta_*`): la llave del intento, el nombre de la caja que pregunta, si cobra con internet, y si ya subió. */
    val intento: String = "",
    val caja: String = "",
    val enLinea: Boolean = false,
    val subida: Boolean = false,
    /** D12 · tubería, ronda 1 (Menor 2): la sucursal de la cuenta que se pregunta; una caja de otra sucursal contesta error. */
    val venueId: String = "",
    /**
     * D12 · W2 (sólo en `cuenta_cobrada`): ese cobro dejó saldo (true) o la cobró completa (false). null = no se sabe (un aviso rearmado
     * desde su fila tras irse el flujo): la dueña lo toma como abono, nunca como «Cobrada».
     */
    val conAbono: Boolean? = null,
    /** Hub D3 (sólo `estado`): generación, número de secuencia, mesas en las que está la caja y aperturas que pide. Fuera del cable de las ops viejas. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val gen: Long = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val seq: Long = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mesas: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val aperturas: List<WireApertura>? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LeaseResponse(
    @SerialName("v") val version: Int = LeaseProtocol.PROTOCOL_VERSION,
    val status: String,
    val lease: WireLease? = null,
    val holder: WireLease? = null,
    val currentEpoch: Long? = null,
    val leases: List<WireLease>? = null,
    val message: String? = null,
    /** D12 · Opción D: quién la cobra o la cobró y en qué caja, y si a ese cobro le falta subir. */
    val quien: String? = null,
    val caja: String? = null,
    val faltaSubirla: Boolean? = null,
    /** D12 · tubería: la respuesta a [LeaseProtocol.OP_QUIEN], el TXT del anuncio tal cual (`did`, `venue`, `boot`, `hub`, `d`, `caja`…). */
    val txt: Map<String, String>? = null,
    /** Hub D3 (sólo `estado`): quién arbitra y el veredicto de cada apertura pedida. Fuera del cable de las ops viejas. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val arbitro: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val aperturas: List<WireResultadoApertura>? = null,
)
