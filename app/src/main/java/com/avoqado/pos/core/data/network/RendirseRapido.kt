package com.avoqado.pos.core.data.network

import com.avoqado.pos.core.util.ConnectivityMonitor
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * D12 (hz:e5ee98bc, ensayo general tanda 2, 11-nov): «rendirse rápido ES el diseño en la ruta del dinero» (regla offline §2.5), y
 * con el WiFi «conectado pero sin datos» no se cumplía en dos lugares: abrir una mesa usaba el plazo general (30 s: la hoja se
 * congelaba ~35 s) y cada cobro en efectivo volvía a esperar su plazo (~13 s) aunque el anterior acabara de quedarse sin respuesta.
 *
 * Actúa sólo sobre las peticiones con la marca [HEADER] (el cliente del dinero de `OrderRepository` y `ApiService.openTable`; la
 * marca no llega al servidor):
 * 1. **Plazo corto:** 5 s para conectar y 10 s para leer o escribir, aunque el cliente traiga 30 s.
 * 2. **Recordar que nadie contesta:** si la última marcada se quedó SIN respuesta (o el ping de cada 10 s tampoco tuvo, D12
 *    `sinRespuesta`) y el servidor sigue sin contestar, la siguiente no toca la red: sale al instante con [SinRespuestaReciente], un
 *    `ConnectException` («no se conectó»), que cada camino ya trata como sin red (el efectivo se encola con su llave, la mesa se
 *    abre sin red, la tarjeta dice «Sin conexión» y conserva su llave). Como nada sale por el cable, nada se puede duplicar.
 *
 * Un servidor que contesta, aunque sea con un 5xx, NO es «nadie contesta»: un 5xx de cualquier pantalla apaga
 * `isServerReachable`, pero no deja el recuerdo, y ahí se intenta como siempre. El recuerdo caduca en cuanto alguien contesta (el ping
 * de cada 10 s con cualquier código, o cualquier respuesta HTTP) y vence a [VENTANA_MS].
 *
 * ponytail: un DNS colgado no lo acota el plazo de conectar; en la ruta del dinero lo acota el plazo total de 15 s del cliente, y
 * abrir mesa (sin plazo total) dependería del resolver del sistema. Si se ve en un aparato, un plazo total por petición.
 */
class RendirseRapido(
    private val conectividad: ConnectivityMonitor,
    private val ahoraMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Calibración (regla offline §2.5). Las pruebas los acortan para no esperar. */
    private val conectarMs: Long = 5_000,
    private val leerMs: Long = 10_000,
) : Interceptor {

    /** Cuándo se quedó sin respuesta la última marcada (reloj monotónico). null = contestó, o nunca falló. */
    @Volatile private var sinRespuestaEnMs: Long? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER) == null) return chain.proceed(request)
        if (nadieContestaAhora()) throw SinRespuestaReciente()
        val response = try {
            chain.withConnectTimeout(conectarMs.toInt(), TimeUnit.MILLISECONDS)
                .withReadTimeout(leerMs.toInt(), TimeUnit.MILLISECONDS)
                .withWriteTimeout(leerMs.toInt(), TimeUnit.MILLISECONDS)
                .proceed(request.newBuilder().removeHeader(HEADER).build())
        } catch (e: IOException) {
            if (nadieContesto(e, chain.call().timeout().timeoutNanos())) sinRespuestaEnMs = ahoraMs()
            throw e
        }
        sinRespuestaEnMs = null
        return response
    }

    /**
     * Codex r1 #2: el recuerdo vale sólo si NADIE contestó después (el ping de cada 10 s con cualquier código, o cualquier respuesta
     * HTTP: `ConnectivityMonitor.ultimaRespuestaMs`). Un 500 del ping dice que el servidor contesta: se vuelve a intentar.
     */
    private fun nadieContestaAhora(): Boolean {
        if (conectividad.isServerReachable.value) return false
        val en = sinRespuestaEnMs
        val recuerdoVigente = en != null && en > conectividad.ultimaRespuestaMs && ahoraMs() - en < VENTANA_MS
        return recuerdoVigente || conectividad.sinRespuesta.value
    }

    /**
     * ¿Esta falla dice que NADIE contestó? Sin ruta, sin conexión o el plazo de un socket. El plazo TOTAL del cliente cuenta sólo si
     * es al menos el de leer (el del dinero, 15 s): uno más corto (la consulta del saldo, 2 s) no prueba que nadie conteste (familia C
     * del D12). Cerrar una pantalla cancela con «Canceled», que no es nada de esto.
     */
    private fun nadieContesto(e: IOException, plazoTotalNs: Long): Boolean = when (e) {
        is SocketTimeoutException, is ConnectException, is UnknownHostException, is NoRouteToHostException -> true
        is InterruptedIOException -> plazoTotalNs >= TimeUnit.MILLISECONDS.toNanos(leerMs)
        else -> false
    }

    /** Salió sin tocar la red: la última marcada se quedó sin respuesta y el servidor sigue sin contestar. Nada se mandó. */
    class SinRespuestaReciente : ConnectException("Sin conexión: el servidor no contestó hace unos segundos")

    companion object {
        /** La marca: sólo la leen este interceptor y quien la pone; se quita antes de salir. */
        const val HEADER = "X-Avoqado-Rendirse-Rapido"
        const val VENTANA_MS = 60_000L
    }
}
