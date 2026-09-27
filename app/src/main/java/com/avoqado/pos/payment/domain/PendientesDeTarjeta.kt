package com.avoqado.pos.payment.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 🔴 Founder, 25-sep-2026: «ninguna duda apaga la tablet». Los cobros con tarjeta cuyo desenlace NO consta, VARIOS a la vez:
 * la duda de una venta ya no frena el cobro de las demás. Se escriben ANTES del POST (sin esto, una app que muere a media
 * venta perdía el único modo de preguntar «¿cómo quedó?»). Puro, sin Android: se prueba en la JVM.
 */
@Serializable
data class CobroPendiente(
    val requestId: String,
    val orderId: String? = null,
    val contexto: String,
    /**
     * H2 (26-sep): consta que ese cobro SÍ pasó. Ya no se borra al probarse: se queda MARCADO su ventana de 10 min (founder,
     * 26-sep: la misma que las dudas), o hasta el «Entendido» de ESE cobro, o hasta que el flujo que lo mandó lo aplica como
     * su pago. Aditivo: una lista vieja no lo trae y se lee `false`.
     */
    val cobrado: Boolean = false,
    /** Cuándo se confirmó (la primera vez): la ventana arranca aquí sólo si el contexto no trae `creadoEn`. Aditivo. */
    val cobradoEn: Long? = null,
)

object PendientesDeTarjeta {
    /**
     * Sólo el tope de la MEMORIA de soltados del servicio. La lista durable NO se trunca (H1, Codex 26-sep): el 21 tiraba al
     * más viejo sin desenlace, antes del POST. Una entrada sale sólo con un desenlace acreditado, con «Entendido», o —un
     * cobro que SÍ pasó— al vencer su ventana de 10 min.
     */
    const val TOPE = 20

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CobroPendiente.serializer())

    fun leer(crudo: String?): List<CobroPendiente> =
        crudo?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    /**
     * Idempotente por `requestId`: el mismo cobro ni se duplica ni cambia de lugar (su edad es la de la 1ª escritura).
     *
     * @param cobrado H2: consta que ese cobro SÍ pasó. Lo MARCA en su lugar, con sus datos de siempre, o lo agrega ya
     *   marcado si no estaba (lo había soltado este proceso).
     * @param cobradoEn cuándo se confirmó; se conserva el de la primera confirmación.
     */
    fun agregar(
        crudo: String?, requestId: String, orderId: String?, contexto: String, cobrado: Boolean = false, cobradoEn: Long? = null,
    ): String {
        val lista = leer(crudo)
        val nueva = when {
            lista.none { it.requestId == requestId } -> lista + CobroPendiente(requestId, orderId, contexto, cobrado, cobradoEn)
            cobrado -> lista.map { if (it.requestId == requestId) it.copy(cobrado = true, cobradoEn = it.cobradoEn ?: cobradoEn) else it }
            else -> lista
        }
        return json.encodeToString(serializer, nueva)
    }

    /**
     * M-10: el crudo que NO se pudo leer, para respaldarlo tal cual antes de escribir encima. Se leía como lista vacía y la
     * escritura siguiente lo borraba de verdad. `null` = se leyó bien, o no había nada.
     */
    fun ilegible(crudo: String?): String? =
        crudo?.takeIf { it.isNotBlank() && runCatching { json.decodeFromString(serializer, it) }.isFailure }

    fun quitar(crudo: String?, requestId: String): String =
        json.encodeToString(serializer, leer(crudo).filterNot { it.requestId == requestId })

    /**
     * La llave ÚNICA de versiones anteriores, pasada a la lista. null = no había nada que migrar.
     *
     * El contexto sólo se hereda si es de ESA solicitud (misma regla que `TerminalPaymentService.contextoDe`): la llave
     * vieja se re-armaba sin reescribir el contexto, así que éste podía ser de otro cobro, y su `orderId` le colgaría el
     * pendiente a la venta equivocada.
     */
    fun desdeLlaveUnica(requestId: String?, contexto: String?): String? {
        val id = requestId?.takeIf { it.isNotBlank() } ?: return null
        val propio = contexto?.takeIf { campo(it, "requestId") == id }
        return agregar(null, id, propio?.let { campo(it, "orderId") }, propio ?: "{}")
    }

    /** Un campo de texto del contexto guardado; null si falta, está en blanco o el JSON no se entiende. */
    private fun campo(contexto: String, llave: String): String? =
        runCatching { Json.parseToJsonElement(contexto).jsonObject[llave]?.jsonPrimitive?.contentOrNull }
            .getOrNull()?.takeIf { it.isNotBlank() }
}
