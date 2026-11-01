package com.avoqado.pos.transactions.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * La prueba de origen de una respuesta: la ÚNICA del Reembolso y del Cambio (Codex apps r2-r3).
 *
 * - Cada petición sale con un `X-Correlation-ID` nuevo. Sólo NUESTRO servidor lo devuelve: lo hace su `requestLogger`, en
 *   producción desde el 9-ago-2026 (3868f6501).
 * - Un proxy, un portal o un túnel no lo devuelven. Su rechazo no prueba que el servidor no escribió nada, así que lo
 *   pendiente se conserva.
 * - Un reintento interno de la misma petición (sesión renovada, PIN de gerente) reusa su id. El eco sigue saliendo de quien la vio.
 */
internal fun <T> OkHttpClient.conPruebaDeOrigen(request: Request, leer: (respuesta: Response, delServidor: Boolean) -> T): T {
    val id = java.util.UUID.randomUUID().toString()
    return newCall(request.newBuilder().header(CORRELACION, id).build()).execute()
        .use { leer(it, it.header(CORRELACION) == id) }
}

private const val CORRELACION = "X-Correlation-ID"
