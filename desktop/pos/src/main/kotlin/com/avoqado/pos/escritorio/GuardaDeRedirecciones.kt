package com.avoqado.pos.escritorio

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Interceptor de RED de escritorio: ningún pedido de los clientes de la API sale hacia un servidor distinto del de este
 * build ([servidor], el que pasó por `Urls.api`). Mismo servidor = mismo esquema, host y puerto.
 *
 * 🔴 Por qué: OkHttp sigue las redirecciones solo y no vuelve a pasar por `Urls`. Un 302 del backend de prueba hacia
 * api.avoqado.io sacaría a la prueba hacia producción (o a producción hacia otro host), y en un 307/308 OkHttp reenvía
 * además el CUERPO del POST: el refresh token, el PIN del gerente, un cobro. El servidor de Avoqado no redirige ninguna
 * llamada de la API (medido el 1-oct: sus `res.redirect` son de OAuth en navegador y rutas viejas), así que cortar no
 * rompe nada. Como interceptor de RED corre con la conexión ya abierta pero ANTES de escribir un solo byte del pedido; la
 * app ve una IOException (= «sin red»): encola lo encolable y nunca cierra sesión.
 */
class GuardaDeRedirecciones(servidor: String) : Interceptor {
    private val permitido: HttpUrl = servidor.toHttpUrl()

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (url.scheme != permitido.scheme || url.host != permitido.host || url.port != permitido.port) {
            throw IOException(
                "redirección bloqueada hacia ${url.host} (${url.scheme}, puerto ${url.port}): este POS sólo habla con " +
                    "${permitido.scheme}://${permitido.host}:${permitido.port}",
            )
        }
        return chain.proceed(chain.request())
    }

    /** El mismo cliente (comparte conexiones e hilos) con esta guarda como interceptor de red. */
    fun en(cliente: OkHttpClient): OkHttpClient = cliente.newBuilder().addNetworkInterceptor(this).build()
}
