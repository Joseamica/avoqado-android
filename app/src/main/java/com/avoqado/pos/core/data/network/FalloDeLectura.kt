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
class LecturaFallida(val tipo: FalloDeLectura, causa: Throwable? = null) : Exception("Lectura fallida: $tipo", causa)
