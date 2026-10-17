package com.avoqado.pos.core.data.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * La sesión venció y NO se pudo renovar porque no hubo red (o el servidor tropezó: 5xx/429).
 *
 * 🔴 Testarudo, 15-oct-2026 16:28: el internet del local parpadeó, el refresco no llegó al
 * servidor y la app dejaba pasar el 401 «Token has expired» tal cual: el cajero leyó «Error en el
 * pago · Token has expired» y la venta en efectivo no se encoló (para la cola un 401 no es red).
 * Es un `IOException` a propósito: todo lo que ya sabe tratar la falta de red (encolar, «Sin
 * conexión», reintentar) lo trata bien sin tocarlo. La sesión NO se cierra.
 *
 * [motivo] es para el reporte (`red:<clase>` o `servidor:<código>`): nunca lleva tokens.
 */
class SesionSinRenovar(val motivo: String) : IOException("Sin conexión: no se pudo renovar la sesión.")

/**
 * Las conexiones abiertas que comparten TODOS los clientes HTTP de la app, incluido el del
 * refresco. Con internet débil, una conexión ya abierta aguanta y marcar una nueva falla: el
 * refresco, que sólo corre cada 10 min, era el que siempre tenía que marcar desde cero.
 */
object ConexionesCompartidas {
    val pool = ConnectionPool()

    /**
     * El cliente del que NACEN el principal y el del refresco (`newBuilder()`). OkHttp sólo reusa una conexión si la
     * «dirección» coincide, y eso incluye la fábrica TLS: dos `OkHttpClient.Builder()` independientes traen cada uno la
     * suya y nunca comparten una conexión HTTPS aunque compartan el pool (auditoría de Codex, 16-oct).
     */
    val base: OkHttpClient = OkHttpClient.Builder().connectionPool(pool).build()
}
