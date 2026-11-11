package com.avoqado.pos.core.data.lan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * D12 · Opción D — el REGISTRO del dueño de cada cuenta abierta de Mostrador (la caja que la ABRIÓ: `CuentasAbiertasAqui`). LÓGICA
 * PURA, como [TableLease]: sin red, sin disco y con el reloj por parámetro. `DuenoDeCuentas` lo guarda en disco y lo contesta por la
 * red. Reglas: `docs/audit/2026-11-10-d12-cuenta-abierta-sin-red/opcion-d.md` (R1, R6, H1, H2).
 *
 * Por cuenta: LIBRE (ausente) → APARTADA (30 s, renovable) → COMPROMETIDA (pasó la revisión de justo antes del cajón; nunca vence por
 * tiempo) → CERRADA (FINAL: nada la regresa a LIBRE; confirmar la subida sólo quita «falta subirla»).
 *
 * ── Reloj ── Las duraciones se miden con el reloj MONOTÓNICO (`elapsedRealtime`) y cada instante guarda su ARRANQUE: tras un reinicio
 * real las APARTADA caen (sin dinero), las COMPROMETIDA se conservan y su espera de 2 min vuelve a empezar, y las CERRADA quedan. La
 * hora de pared ([Registro.cancelados], [CuentaEnElRegistro.hora]) sólo poda y se enseña: nunca decide un permiso.
 */
@Serializable
data class Instante(val arranque: String, val ms: Long)

@Serializable
enum class EstadoDeCuenta { APARTADA, COMPROMETIDA, CERRADA }

@Serializable
data class CuentaEnElRegistro(
    val estado: EstadoDeCuenta,
    /** `deviceId` de la caja que la aparta, la compromete o la cobró; [RegistroDeCuentas.SERVIDOR] si la cerró una lectura del servidor. */
    val por: String,
    /** Nombre visible de esa caja («Caja 2»); vacío si no se sabe. */
    val caja: String = "",
    /** Quién cobra (la persona). */
    val quien: String = "",
    /** La llave del intento (R1): «suelta» y el cancelado actúan sólo sobre ella. */
    val intento: String = "",
    /** APARTADA: base de los 30 s. COMPROMETIDA: base de los 2 min de «No se cobró». CERRADA: cuándo. */
    val desde: Instante,
    /** Reloj de pared, sólo para enseñar. */
    val hora: Long = 0,
    /** CERRADA: el cobro todavía no consta en el servidor. */
    val faltaSubirla: Boolean = false,
)

@Serializable
data class Registro(
    val cuentas: Map<String, CuentaEnElRegistro> = emptyMap(),
    /** Intentos que una persona declaró «No se cobró» (→ hora de pared, para podar): su apartar y comprometer tardíos se rechazan. */
    val cancelados: Map<String, Long> = emptyMap(),
)

/** Quién pide: la caja ([por], [caja]), la persona ([quien]) y su intento. [enLinea] = cobra con internet y el saldo vivo del servidor. */
data class Solicitud(val por: String, val caja: String, val quien: String, val intento: String, val enLinea: Boolean = false)

sealed interface Respuesta {
    data object Concedida : Respuesta
    data class Ocupada(val quien: String, val caja: String) : Respuesta
    data class Cerrada(val caja: String, val faltaSubirla: Boolean) : Respuesta

    /** El intento fue cancelado por «No se cobró» en el dueño. */
    data object Rechazada : Respuesta
}

data class Paso(val registro: Registro, val respuesta: Respuesta)

data class Declaracion(val registro: Registro, val hecho: Boolean)

object RegistroDeCuentas {
    const val TTL_MS = 30_000L
    const val RENOVAR_MS = 10_000L
    const val NO_CONFIRMO_MS = 120_000L

    /** R1: en la caja que cobra, la autorización del dueño vale esto desde que se ENVIÓ la pregunta, hasta guardar `EN_VUELO`. */
    const val AUTORIZACION_MS = 20_000L
    const val SERVIDOR = "servidor"
    private const val PODA_DE_CANCELADOS_MS = 7 * 24 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    fun escribir(r: Registro): String = json.encodeToString(Registro.serializer(), r)

    /** null = ilegible (quien lo guarda NO escribe encima). */
    fun leer(texto: String): Registro? = runCatching { json.decodeFromString(Registro.serializer(), texto) }.getOrNull()

    /** APARTADA vencida o de otro arranque → fuera; COMPROMETIDA de otro arranque → su espera empieza [ahora]; CERRADA intacta. */
    fun normalizar(r: Registro, ahora: Instante, hora: Long): Registro {
        val cuentas = r.cuentas.mapNotNull { (id, c) ->
            when {
                c.estado == EstadoDeCuenta.APARTADA && (c.desde.arranque != ahora.arranque || ahora.ms - c.desde.ms >= TTL_MS) -> null
                c.estado == EstadoDeCuenta.COMPROMETIDA && c.desde.arranque != ahora.arranque -> id to c.copy(desde = ahora)
                else -> id to c
            }
        }.toMap()
        val cancelados = r.cancelados.filterValues { hora - it < PODA_DE_CANCELADOS_MS }
        return if (cuentas == r.cuentas && cancelados == r.cancelados) r else Registro(cuentas, cancelados)
    }

    fun apartar(r: Registro, orderId: String, s: Solicitud, ahora: Instante, hora: Long): Paso =
        pedir(r, orderId, s, ahora, hora, EstadoDeCuenta.APARTADA)

    /** Desde su propio apartado, su propio compromiso (re-comprometer reinicia los 2 min) o LIBRE: el dueño es la única autoridad. */
    fun comprometer(r: Registro, orderId: String, s: Solicitud, ahora: Instante, hora: Long): Paso =
        pedir(r, orderId, s, ahora, hora, EstadoDeCuenta.COMPROMETIDA)

    private fun pedir(r0: Registro, orderId: String, s: Solicitud, ahora: Instante, hora: Long, a: EstadoDeCuenta): Paso {
        val r = normalizar(r0, ahora, hora)
        if (s.intento in r.cancelados) return Paso(r, Respuesta.Rechazada)
        val c = r.cuentas[orderId]
        return when {
            c?.estado == EstadoDeCuenta.CERRADA ->
                // Con internet manda el saldo vivo del servidor; sólo un cobro que aún no sube lo bloquea. Sin red, siempre (H1).
                Paso(r, if (s.enLinea && !c.faltaSubirla) Respuesta.Concedida else Respuesta.Cerrada(c.caja, c.faltaSubirla))
            c != null && c.intento != s.intento -> Paso(r, Respuesta.Ocupada(c.quien, c.caja))
            // Un apartado no baja un compromiso del mismo intento (la renovación de fondo puede llegar tarde).
            c?.estado == EstadoDeCuenta.COMPROMETIDA && a == EstadoDeCuenta.APARTADA -> Paso(r, Respuesta.Concedida)
            else -> Paso(
                r.copy(cuentas = r.cuentas + (orderId to CuentaEnElRegistro(a, s.por, s.caja, s.quien, s.intento, ahora, hora))),
                Respuesta.Concedida,
            )
        }
    }

    /** «Suelta»: sólo la APARTADA o COMPROMETIDA de ESE intento (R1). */
    fun soltar(r: Registro, orderId: String, intento: String): Registro {
        val c = r.cuentas[orderId] ?: return r
        if (c.estado == EstadoDeCuenta.CERRADA || c.intento != intento) return r
        return r.copy(cuentas = r.cuentas - orderId)
    }

    /**
     * «Cobrada» (de otra caja o del dueño mismo). Se acepta siempre, también de un intento cancelado: es dinero que sí entró. El mismo
     * intento sólo puede quitar «falta subirla»; otro cobro la conserva si el anterior aún no subía.
     */
    fun cobrada(r: Registro, orderId: String, s: Solicitud, faltaSubirla: Boolean, ahora: Instante, hora: Long): Registro {
        val c = r.cuentas[orderId]
        val falta = when {
            c?.estado != EstadoDeCuenta.CERRADA -> faltaSubirla
            c.intento == s.intento -> c.faltaSubirla && faltaSubirla
            else -> c.faltaSubirla || faltaSubirla
        }
        if (c?.estado == EstadoDeCuenta.CERRADA && c.intento == s.intento) {
            return if (c.faltaSubirla == falta) r else r.copy(cuentas = r.cuentas + (orderId to c.copy(faltaSubirla = falta)))
        }
        return r.copy(cuentas = r.cuentas + (orderId to CuentaEnElRegistro(EstadoDeCuenta.CERRADA, s.por, s.caja, s.quien, s.intento, ahora, hora, falta)))
    }

    /** Una lectura del servidor la da pagada, cerrada o cancelada: CERRADA y ya nada «falta subirla». */
    fun servidorLaCerro(r: Registro, orderId: String, ahora: Instante, hora: Long): Registro {
        val c = r.cuentas[orderId]
        val nueva = if (c?.estado == EstadoDeCuenta.CERRADA) c.copy(faltaSubirla = false)
        else CuentaEnElRegistro(EstadoDeCuenta.CERRADA, SERVIDOR, desde = ahora, hora = hora)
        return if (nueva == c) r else r.copy(cuentas = r.cuentas + (orderId to nueva))
    }

    /** ¿Ya puede una persona declarar «No se cobró»? Sólo una COMPROMETIDA cuyos 2 min corrieron en ESTE arranque. */
    fun puedeDeclararNoSeCobro(c: CuentaEnElRegistro?, ahora: Instante): Boolean =
        c?.estado == EstadoDeCuenta.COMPROMETIDA && c.desde.arranque == ahora.arranque && ahora.ms - c.desde.ms >= NO_CONFIRMO_MS

    /** «No se cobró: cobrar aquí»: la cuenta vuelve a LIBRE y su intento queda cancelado en disco. Antes de tiempo, nada. */
    fun noSeCobro(r0: Registro, orderId: String, ahora: Instante, hora: Long): Declaracion {
        val r = normalizar(r0, ahora, hora)
        val c = r.cuentas[orderId]
        if (!puedeDeclararNoSeCobro(c, ahora)) return Declaracion(r, false)
        return Declaracion(Registro(r.cuentas - orderId, r.cancelados + (c!!.intento to hora)), true)
    }
}
