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
import org.junit.Assert.assertTrue
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

    // MARK: - D12 · RendirseRapido: las fechas de «contestó» y «nadie contestó» (hz:e5ee98bc)

    @Test fun `el ping anota cuando contesta y cuando nadie contesta`() {
        val server = MockWebServer().also { it.start() }
        val m = monitor(server)
        m.reportServerError()
        server.enqueue(MockResponse().setResponseCode(503))
        m.pingServer()
        assertTrue("un 503 es una respuesta", m.ultimaRespuestaMs > m.ultimaSinRespuestaMs)

        server.shutdown()
        m.pingServer()
        assertTrue("nadie contestó después", m.ultimaSinRespuestaMs > m.ultimaRespuestaMs)
    }

    /** Codex D12 r4 #2: la salud contestó un 200 y luego se cortó el cuerpo: alguien SÍ contestó; no es noticia de «nadie contesta». */
    @Test fun `la salud que contesta y corta el cuerpo no anota nadie contesta`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val m = monitor(server)
        m.reportServerError()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"ok","database":"connected"}""")
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))

        m.nadieContesta()

        assertTrue("contestó: ${m.ultimaRespuestaMs} vs ${m.ultimaSinRespuestaMs}", m.ultimaRespuestaMs > m.ultimaSinRespuestaMs)
        server.shutdown()
    }

    // MARK: - D12 · familia C (Codex r4 #2): la salud que pregunta la puerta de las cuentas

    /**
     * Plazo TOTAL: un servidor que contesta los encabezados y gotea el cuerpo (cada lectura llega antes de los 5 s del `readTimeout`) ya no
     * detiene el cobro lo que tarde el cuerpo. Al vencer cuenta como «no» (falla cerrado) y se cierra la conexión; ese plazo propio no dice
     * «nadie contesta» (alguien sí contestó).
     */
    @Test fun `familia C - con el cuerpo goteando la salud de la puerta dice no al vencer su plazo total`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val m = monitor(server)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"ok","database":"connected"}""")
            .throttleBody(1, 400, java.util.concurrent.TimeUnit.MILLISECONDS)) // ~15 s de cuerpo, 1 byte cada 400 ms (< 5 s por lectura)
        try {
            val antes = System.nanoTime()
            val contesta = m.elServidorContestaEn(1_000)
            val tardo = (System.nanoTime() - antes) / 1_000_000
            assertEquals("venció: cuenta como sin red", false, contesta)
            // Holgura para la máquina cargada; sin plazo total tardaría lo que el cuerpo (~15 s).
            assertTrue("plazo total, no lo que tarde el cuerpo: $tardo ms", tardo < 5_000)
            Thread.sleep(500) // la consulta que se cerró termina en su hilo
            assertEquals("un plazo propio no prueba que nadie conteste", false, m.sinRespuesta.value)
        } finally {
            runCatching { server.shutdown() }
        }
    }

    /** «No» al instante, sin preguntar, si el monitor ya sabe que nadie contesta (el último ping o la última salud). */
    @Test fun `familia C - si el monitor ya sabe que nadie contesta la puerta no pregunta`() = runBlocking {
        val muerto = MockWebServer().also { it.start() }
        val m = monitor(muerto)
        muerto.shutdown()
        m.pingServer()
        assertTrue("premisa: nadie contestó", m.sinRespuesta.value)
        val vivo = MockWebServer().also { it.start() }
        vivo.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"ok"}"""))
        m.urlBase = vivo.url("/api/v1").toString()

        assertEquals(false, m.elServidorContestaEn(1_000))
        assertEquals("no se le preguntó", 0, vivo.requestCount)
        vivo.shutdown()
    }

    /** Con el servidor bien, contesta que sí dentro de su plazo. */
    @Test fun `familia C - con el servidor bien la puerta oye que si`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val m = monitor(server)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"ok"}"""))
        assertEquals(true, m.elServidorContestaEn(1_000))
        server.shutdown()
    }
}
