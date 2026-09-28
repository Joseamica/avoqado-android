package com.avoqado.pos.core.data.lan

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * El cliente de la caja con su presupuesto (etapa 3 del KDS, 3.5, D5): una pantalla que no contesta consume A LO MÁS
 * 1.5 s, y sólo el acuse de SU folio cuenta. Sockets reales por loopback. Espejo de `ClienteDeComandasTests` de iOS.
 */
class ClienteDeComandasTest {

    private val mensaje = KdsComanda(
        venueId = "venue-1", deviceId = "tablet-1", sourceKey = "sale:ext-1:st_barra", stationId = "st_barra",
        orderNumber = "1234", orderType = "En tienda", createdAtMillis = 1, items = emptyList(),
    )
    private val servidores = mutableListOf<ServerSocket>()

    @After
    fun cerrar() { servidores.forEach { runCatching { it.close() } } }

    /** Un servidor que lee UNA línea y contesta lo que diga [responder]; `null` = se queda mudo. */
    private fun servidor(responder: (String) -> String?): LanPeer {
        val ss = ServerSocket(0).also { servidores += it }
        thread(isDaemon = true) {
            runCatching {
                ss.accept().use { s: Socket ->
                    val linea = LineaAcotada.leer(BufferedInputStream(s.getInputStream())) ?: return@use
                    val r = responder(linea) ?: run { Thread.sleep(5_000); return@use }
                    s.getOutputStream().run { write((r + "\n").toByteArray()); flush() }
                }
            }
        }
        return LanPeer("otro", "127.0.0.1", ss.localPort)
    }

    @Test
    fun `con acuse de su folio entrega true`() = runBlocking {
        val peer = servidor { KdsLanProtocol.encode(KdsLanProtocol.acuse("sale:ext-1:st_barra")) }
        assertTrue(ClienteDeComandas().entregar(peer, mensaje))
    }

    @Test
    fun `P1 un acuse de otro folio o la respuesta de una app vieja NO es acuse`() = runBlocking {
        assertFalse(ClienteDeComandas().entregar(servidor { KdsLanProtocol.encode(KdsLanProtocol.acuse("sale:ext-2:st_barra")) }, mensaje))
        assertFalse(ClienteDeComandas().entregar(servidor { LeaseProtocol.encode(LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Operación desconocida: comanda")) }, mensaje))
    }

    @Test
    fun `P1 una pantalla que no contesta consume a lo mas 1_5 s`() = runBlocking {
        val peer = servidor { null }
        val inicio = System.currentTimeMillis()
        assertFalse(ClienteDeComandas().entregar(peer, mensaje))
        val tardo = System.currentTimeMillis() - inicio
        assertTrue("tardó $tardo ms", tardo in 1_000..2_500)
    }

    @Test
    fun `sin nadie escuchando se rinde rapido`() = runBlocking {
        val cerrado = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val inicio = System.currentTimeMillis()
        assertFalse(ClienteDeComandas().entregar(LanPeer("otro", "127.0.0.1", cerrado), mensaje))
        assertTrue(System.currentTimeMillis() - inicio < 1_500)
    }
}
