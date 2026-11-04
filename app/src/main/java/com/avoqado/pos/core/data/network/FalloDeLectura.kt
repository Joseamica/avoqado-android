package com.avoqado.pos.core.data.network

import java.io.IOException

/**
 * Por qué no se pudo leer algo del servidor (D18, 3-nov-2026). Las pantallas que sólo leen del servidor (Ventas,
 * Informes) dicen cosas distintas en cada caso: sin red es un estado normal («Sin conexión», nunca en rojo); el servidor
 * que contesta mal, no. Antes las dos se veían como «no hay nada» (Sunmi OrderPAD 3 con el WiFi apagado).
 */
enum class FalloDeLectura {
    /** Nadie contestó: sin red, DNS, tiempo agotado, conexión rechazada (un [IOException]). */
    SIN_RED,

    /** El servidor sí contestó, pero mal: un HTTP que no es 2xx o un cuerpo que no se puede leer. */
    SERVIDOR;

    companion object {
        /**
         * El fallo que deja una excepción al leer. Un [IOException] es que nadie contestó, salvo la sesión que no se
         * renovó porque el SERVIDOR tropezó (5xx/429): ahí sí hubo respuesta (mismo criterio que el detalle de una
         * venta, D3). Lo demás (un JSON que no se puede leer, por ejemplo) es del servidor.
         */
        fun de(error: Throwable): FalloDeLectura = when {
            error is LecturaFallida -> error.tipo
            error is SesionSinRenovar && error.motivo.startsWith("servidor:") -> SERVIDOR
            error is IOException -> SIN_RED
            else -> SERVIDOR
        }
    }
}

/** Un fallo de lectura con su motivo, para que viaje dentro de un `Result`. */
class LecturaFallida(
    val tipo: FalloDeLectura,
    causa: Throwable? = null,
    /** D22: qué fue cada error de la carga (Informes junta cuatro consultas). Decide si la recarga automática insiste. */
    val clases: List<ClaseDeFallo> = listOf(ClaseDeFallo.REINTENTABLE),
) : Exception("Lectura fallida: $tipo", causa)

/**
 * D22 (3-nov-2026): qué hace la recarga automática de Ventas e Informes con un fallo. La pantalla no cambia (D18); esto sólo
 * decide si se vuelve a pedir sola o se detiene hasta que llegue algo nuevo.
 */
enum class ClaseDeFallo {
    /** 401, o un 403 que es de nuestra API: el «no» es de verdad. Insistir sólo repite el «no» (o el PIN de gerente). */
    DENEGACION_PROPIA,

    /** Sin red, 5xx, 408, 429, un 403 de un intermediario (portal, WAF, HTML) o un cuerpo que no se puede leer. */
    REINTENTABLE,

    /** Cualquier otro 4xx: la misma petición tendría la misma respuesta. */
    DEFINITIVO;

    companion object {
        /** La clase de una respuesta que no sirvió (un HTTP que no es 2xx, o un 2xx vacío o ilegible). */
        fun deRespuesta(codigo: Int, cuerpo: String): ClaseDeFallo = when {
            codigo in 200..299 -> REINTENTABLE
            codigo == 401 -> DENEGACION_PROPIA
            codigo == 403 -> if (RespuestaProhibida.esDenegacionPropia(cuerpo)) DENEGACION_PROPIA else REINTENTABLE
            codigo == 408 || codigo == 429 || codigo >= 500 -> REINTENTABLE
            else -> DEFINITIVO
        }

        /** Las clases que deja un error: las suyas si es una [LecturaFallida]; si nadie contestó o el cuerpo no se leyó, reintentable. */
        fun de(error: Throwable): List<ClaseDeFallo> = when (error) {
            is LecturaFallida -> error.clases
            is IllegalStateException -> listOf(DEFINITIVO) // sin sucursal, sin sesión, la sesión cambió: no lo arregla repetir
            else -> listOf(REINTENTABLE)
        }
    }
}

/**
 * D22: la precedencia de una carga con varios errores. Si alguno fue una denegación propia, se detiene; si no, si alguno es
 * reintentable, se reintenta; si no (otro 4xx), se detiene.
 */
fun List<ClaseDeFallo>.laAutomaticaReintenta(): Boolean =
    ClaseDeFallo.DENEGACION_PROPIA !in this && ClaseDeFallo.REINTENTABLE in this
