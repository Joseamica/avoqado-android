package com.avoqado.pos.maquina

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Random

/**
 * El protocolo de la QuickPay Mini de APS (manual de integración 2.0.0, 18-nov-2026): JSON por WebSocket, sin estado.
 * Spec: `docs/superpowers/specs/2026-11-17-maquina-de-efectivo-quickpay-design.md`, «El protocolo de la máquina».
 *
 * Los importes salen siempre en centavos exactos: la máquina manda número en `status`/`sockets` y texto en
 * `updateCredit` (a veces con formato de moneda, `"$1,500.00"`).
 */
sealed interface MensajeDeLaMaquina {
    /** Llega al conectar y cada vez que se abre o cierra una conexión. Puede NO llegar (manual 2.2). */
    data class Sockets(val cobrando: Boolean, val dueCents: Int, val creditCents: Int, val changeBar: String?) :
        MensajeDeLaMaquina

    /**
     * Lo recibido hasta ahora. No trae referencia: es informativo y nunca cierra un cobro (regla 7). Codex código r2 H14: trae el `Due`
     * de la operación en curso; con otro `Due` es de OTRA operación (null = no vino o no se lee).
     */
    data class UpdateCredit(val creditCents: Int, val dueCents: Int? = null) : MensajeDeLaMaquina

    /** El único mensaje que puede cerrar un cobro (ver [cierre]). [estado] es el campo `Status`. */
    data class Status(
        val cobrando: Boolean,
        val estado: String?,
        val referenceCC: String?,
        val creditCents: Int,
        val dueCents: Int,
        val changeDispensedCents: Int,
        val changeBar: String?,
        /** Codex código r1 #15: la hora LOCAL de la máquina (manual 2.0.0: `AAAA-MM-DD HH:MM:SS`), tal cual llegó. */
        val fecha: String? = null,
    ) : MensajeDeLaMaquina

    /** En el manual 2.0.0 el `pong` sólo trae `conexiones` y `tarjeta`: [cobrando] casi siempre es null. */
    data class Pong(val cobrando: Boolean?) : MensajeDeLaMaquina

    /** Cualquier otro comando (QR, báscula, administración): se ignora (regla 11). */
    data class Otro(val comando: String) : MensajeDeLaMaquina
}

/** Lee un mensaje de la máquina. null si no es un objeto JSON, no trae `comando` o un importe que importa es ilegible. */
fun leerMensaje(json: String): MensajeDeLaMaquina? {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
    return when (val comando = o.opt("comando") as? String ?: return null) {
        "sockets" -> MensajeDeLaMaquina.Sockets(
            cobrando = o.opt("cobrando") as? Boolean ?: return null,
            dueCents = centavos(o.opt("Due")) ?: return null,
            creditCents = centavos(o.opt("Credit")) ?: return null,
            changeBar = o.opt("changeBar") as? String,
        )
        "updateCredit" -> MensajeDeLaMaquina.UpdateCredit(centavos(o.opt("Credit")) ?: return null, centavos(o.opt("Due")))
        "status" -> MensajeDeLaMaquina.Status(
            cobrando = o.opt("cobrando") as? Boolean ?: return null,
            estado = o.opt("Status") as? String,
            referenceCC = o.opt("referenceCC") as? String,
            creditCents = centavos(o.opt("Credit")) ?: return null,
            dueCents = centavos(o.opt("Due")) ?: return null,
            changeDispensedCents = centavos(o.opt("changeDispensed")) ?: return null,
            changeBar = o.opt("changeBar") as? String,
            fecha = o.opt("fecha") as? String,
        )
        "pong" -> MensajeDeLaMaquina.Pong(o.opt("cobrando") as? Boolean)
        else -> MensajeDeLaMaquina.Otro(comando)
    }
}

/**
 * Pesos (número o texto `"$1,500.00"`; la coma es separador de miles, regla 3) → centavos exactos. Un número pasa por su
 * texto decimal (`toString`), nunca por aritmética de doble: `0.3` son 30 centavos. Medio centavo o ilegible → null.
 */
private fun centavos(valor: Any?): Int? {
    val pesos = when (valor) {
        is Number -> valor.toString().toBigDecimalOrNull()
        is String -> valor.trim().removePrefix("$").replace(",", "").toBigDecimalOrNull()
        else -> null
    } ?: return null
    return runCatching { pesos.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact() }.getOrNull()
}

enum class Cierre { PAGADO, CANCELADO }

private val FECHA_DE_LA_MAQUINA = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
    .withResolverStyle(java.time.format.ResolverStyle.STRICT)

/**
 * Codex código r1 #15: la hora física de un `status`, de SU `fecha` (la hora local de la máquina, `AAAA-MM-DD HH:MM:SS`, manual 2.0.0) en
 * la zona del negocio. Sin `fecha` o ilegible → null: el cierre sube sin hora conocida («por conciliar»), nunca con el reloj de la PC.
 */
fun horaDeLaMaquina(fecha: String?, zona: java.time.ZoneId): Long? = fecha?.trim()?.let {
    runCatching { java.time.LocalDateTime.parse(it, FECHA_DE_LA_MAQUINA).atZone(zona).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * El desenlace en efectivo de este `status`, o null si no cierra. Sólo con `cobrando:false` y el texto completo, con
 * mayúsculas y acentos (manual 4.5 y 7.2). Que el `status` sea NUESTRO (folio, `Due`, generación) lo decide quien llama.
 */
fun MensajeDeLaMaquina.Status.cierre(): Cierre? {
    if (cobrando) return null
    // NFC: la misma «ó» precompuesta o descompuesta es el mismo texto; no relaja nada más.
    return when (estado?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }) {
        "Pago Completado" -> Cierre.PAGADO
        "Transacción Cancelada" -> Cierre.CANCELADO
        else -> null
    }
}

private val REFERENCIA_NUESTRA = Regex("^AQ[0-9A-Z]{12}$")
private const val MAXIMO_BYTES = 1024
private const val BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

/**
 * `paycash`. El monto va como texto con punto decimal (`"187.50"`): con coma la máquina lee miles (regla 3), y uno
 * ilegible, cero o negativo la deja trabada en `cobrando:true` (manual 3.3, regla 4). Por eso se valida antes.
 */
fun pedirCobro(dueCents: Int, referencia: String, fecha: String): String {
    require(cobrableEnMaquina(dueCents)) { "La máquina no puede cobrar $dueCents centavos" }
    require(REFERENCIA_NUESTRA.matches(referencia)) { "Referencia ajena: $referencia" }
    // Sin String.format: el idioma del aparato no puede cambiar los dígitos ni el punto.
    val due = "${dueCents / 100}." + (dueCents % 100).toString().padStart(2, '0')
    return comando("paycash", fecha) { put("due", due).put("referenceCC", referencia) }
}

/** `cancel`: `"1"` devuelve lo insertado; `"2"` cancela y la máquina se lo queda (manual 4.3). */
fun cancelar(devolver: Boolean, fecha: String): String =
    comando("cancel", fecha) { put("cancelvalue", if (devolver) "1" else "2") }

fun ping(fecha: String): String = comando("ping", fecha) {}

private fun comando(nombre: String, fecha: String, campos: JSONObject.() -> Unit): String {
    val json = JSONObject().put("fecha", fecha).put("comando", nombre).apply(campos).toString()
    require(json.toByteArray(Charsets.UTF_8).size <= MAXIMO_BYTES) { "El mensaje pasa de $MAXIMO_BYTES bytes" }
    return json
}

/**
 * Nuestra `referenceCC`: `AQ` + 8 caracteres del milisegundo en base 36 + 4 al azar. Empieza con `AQ`, así que nunca
 * con `NDF` (reservado a los QR) ni con forma de fecha ISO (la máquina la reescribiría, manual 4.7).
 */
fun nuevaReferencia(ahoraMs: Long, aleatorio: Random): String {
    val tiempo = ahoraMs.coerceAtLeast(0).toString(36).uppercase().padStart(8, '0').takeLast(8)
    val azar = String(CharArray(4) { BASE36[aleatorio.nextInt(BASE36.length)] })
    return "AQ$tiempo$azar"
}
