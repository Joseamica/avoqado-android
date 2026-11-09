package com.avoqado.pos.core.data.network

import com.avoqado.pos.core.util.ConnectivityMonitor
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ConnectivityInterceptorCancelacionTest {
    private val monitor = mockk<ConnectivityMonitor>(relaxed = true)
    private val server = MockWebServer()

    @Before fun arrancar() = server.start()
    @After fun apagar() { runCatching { server.shutdown() } }

    private fun cliente(ajustar: OkHttpClient.Builder.() -> Unit = {}) = OkHttpClient.Builder()
        .addInterceptor(ConnectivityInterceptor(monitor)).apply(ajustar).build()
    private fun peticion() = Request.Builder().url(server.url("/x")).build()

    @Test fun `cancelar la peticion no dice que el servidor se cayo`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val llamada = cliente().newCall(peticion())
        thread { Thread.sleep(200); llamada.cancel() }
        assertThrows(IOException::class.java) { llamada.execute() }
        verify(exactly = 0) { monitor.reportServerError() }
    }

    @Test fun `un plazo total vencido si cuenta como servidor caido`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val llamada = cliente { callTimeout(300, TimeUnit.MILLISECONDS) }.newCall(peticion())
        assertThrows(IOException::class.java) { llamada.execute() }
        verify(exactly = 1) { monitor.reportServerError() }
    }

    @Test fun `una falla de red real cuenta como servidor caido`() {
        val url = server.url("/x")
        server.shutdown()
        val llamada = cliente().newCall(Request.Builder().url(url).build())
        assertThrows(IOException::class.java) { llamada.execute() }
        verify(exactly = 1) { monitor.reportServerError() }
    }
}
