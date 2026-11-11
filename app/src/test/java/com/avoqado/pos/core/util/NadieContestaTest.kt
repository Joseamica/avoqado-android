package com.avoqado.pos.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D12, final fix ronda 2 (R2-2): «nadie contesta» es un ESTADO del monitor que cada ping (y cada consulta de la salud) pone al día:
 * cualquier respuesta HTTP (también un 5xx) = alguien contestó; una IOException (conexión rechazada, tiempo agotado, DNS) = nadie.
 * Con el aparato con red: el WiFi conectado y el módem sin internet sólo se distinguen así.
 */
class NadieContestaTest {
    private fun monitor(server: MockWebServer): ConnectivityMonitor {
        // `NetworkRequest.Builder` es un stub del SDK en la JVM (devuelve null): se simula para que el `init` del monitor arme.
        io.mockk.mockkConstructor(android.net.NetworkRequest.Builder::class)
        every { anyConstructed<android.net.NetworkRequest.Builder>().addCapability(any()) } answers { self as android.net.NetworkRequest.Builder }
        every { anyConstructed<android.net.NetworkRequest.Builder>().build() } returns mockk()
        val red = mockk<Network>()
        val capacidades = mockk<NetworkCapabilities> { every { hasCapability(any()) } returns true }
        val cm = mockk<ConnectivityManager>(relaxed = true) {
            every { activeNetwork } returns red
            every { getNetworkCapabilities(red) } returns capacidades
        }
        val contexto = mockk<Context> { every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm }
        return ConnectivityMonitor(contexto).also { it.urlBase = server.url("/api/v1").toString() }
    }

    @Test fun `cada ping pone al dia si nadie contesta`() {
        val server = MockWebServer().also { it.start() }
        val m = monitor(server)
        assertEquals("montaje: el aparato tiene red", true, m.isConnected.value)
        m.reportServerError() // un 5xx apagó la bandera

        server.enqueue(MockResponse().setResponseCode(503))
        m.pingServer()
        assertEquals("un 503 sí contestó", false, m.sinRespuesta.value)

        server.shutdown() // el módem perdió internet: la conexión ya no se puede abrir
        m.pingServer()
        assertEquals("nadie contestó", true, m.sinRespuesta.value)
        assertEquals("la bandera sigue caída", false, m.isServerReachable.value)
    }

    @Test fun `la salud tambien lo pone al dia y una respuesta buena lo apaga`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val m = monitor(server)
        m.reportServerError()
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(false, m.nadieContesta())
        assertEquals(false, m.sinRespuesta.value)
        server.shutdown()
        assertEquals(true, m.nadieContesta())
        assertEquals(true, m.sinRespuesta.value)
        m.reportServerSuccess()
        assertEquals("contestó bien: ya no es «nadie contesta»", false, m.sinRespuesta.value)
    }
}
