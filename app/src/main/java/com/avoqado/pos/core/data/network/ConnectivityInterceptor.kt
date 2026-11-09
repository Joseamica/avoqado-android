package com.avoqado.pos.core.data.network

import com.avoqado.pos.core.util.ConnectivityMonitor
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class ConnectivityInterceptor(
    private val connectivityMonitor: ConnectivityMonitor,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            if (e is com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.Changed) throw e
            // Cerrar una pantalla cancela su petición, y eso no dice nada del servidor. Con `callTimeout` (el cobro),
            // OkHttp también cancela al vencer el plazo y aquí no se distingue: ésa sigue contando como falla.
            val laCanceloLaApp = chain.call().isCanceled() && chain.call().timeout().timeoutNanos() == 0L
            if (!laCanceloLaApp) connectivityMonitor.reportServerError()
            throw e
        }

        if (isServerDown(response)) {
            connectivityMonitor.reportServerError()
        } else if (response.isSuccessful) {
            connectivityMonitor.reportServerSuccess()
        }

        return response
    }

    private fun isServerDown(response: Response): Boolean {
        // ngrok tunnel offline / edge errors
        if (response.header("ngrok-error-code") != null) return true

        // 5xx — upstream proxy / server failure
        if (response.code in 500..599) return true

        // HTML error pages from proxies/CDNs when backend is down — only treat as "down" when
        // the status code is also non-success. A working backend can return HTML 4xx pages
        // (e.g. Express's default 404 handler when a route is misconfigured); those are routing
        // bugs, not connectivity failures, and shouldn't flip the offline banner to "Sin
        // conexión". Mirrors the 200..499 = "server is answering" rule used by pingServer().
        val contentType = response.header("content-type")?.lowercase().orEmpty()
        return contentType.startsWith("text/html") && response.code !in 200..499
    }
}
