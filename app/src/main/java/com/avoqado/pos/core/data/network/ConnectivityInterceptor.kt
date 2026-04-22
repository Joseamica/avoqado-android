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
            connectivityMonitor.reportServerError()
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

        // HTML error pages from proxies/CDNs when backend is down
        val contentType = response.header("content-type")?.lowercase().orEmpty()
        return contentType.startsWith("text/html")
    }
}
