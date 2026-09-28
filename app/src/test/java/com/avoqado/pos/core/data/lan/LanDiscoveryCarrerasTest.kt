package com.avoqado.pos.core.data.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Las carreras de NSD que dejaban un anuncio fantasma o metían peers viejos al plano (revisión de la Task 4, I1/I2).
 * NSD no existe en la JVM: un `NsdManager` de mentira CAPTURA los listeners y la prueba dispara los callbacks a mano,
 * en el orden malo — que es justo lo que en un aparato depende del tiempo y no se puede forzar.
 */
class LanDiscoveryCarrerasTest {

    private val registros = mutableListOf<NsdManager.RegistrationListener>()
    private val busquedas = mutableListOf<NsdManager.DiscoveryListener>()
    private val resoluciones = mutableListOf<NsdManager.ResolveListener>()
    private val nsd = mockk<NsdManager>(relaxed = true) {
        every { registerService(any(), any(), capture(registros)) } just runs
        every { discoverServices(any<String>(), any<Int>(), capture(busquedas)) } just runs
        every { resolveService(any(), capture(resoluciones)) } just runs
    }
    private val contexto = mockk<Context>(relaxed = true) { every { getSystemService(Context.NSD_SERVICE) } returns nsd }
    private val recibidos = mutableListOf<List<LanPeer>>()
    private val discovery = LanDiscovery(contexto, "yo-123456", "venue-1") { recibidos += it }

    private val txtHub = mapOf("did" to "yo-123456", "venue" to "venue-1", "hub" to "1")
    private val txtSinHub = mapOf("did" to "yo-123456", "venue" to "venue-1", "hub" to "0")

    private fun servicio(did: String) = mockk<NsdServiceInfo>(relaxed = true) {
        every { serviceType } returns "._avoqado-pos._tcp."
        every { serviceName } returns "Avoqado-POS-${did.take(6)}"
        every { attributes } returns mapOf("did" to did.toByteArray(), "venue" to "venue-1".toByteArray(), "hub" to "1".toByteArray())
        every { host } returns InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        every { port } returns 4321
    }

    @Test
    fun `P1 un fallo tardio del registro viejo no borra al vigente, que sigue dandose de baja al parar`() {
        discovery.anunciar(5000, txtHub)
        discovery.anunciar(5000, txtSinHub) // cambió el TXT: baja del viejo, alta del nuevo
        val (viejo, vigente) = registros

        // NSD entrega el fallo del registro VIEJO después de que el nuevo ya está pedido.
        viejo.onRegistrationFailed(mockk(relaxed = true), NsdManager.FAILURE_INTERNAL_ERROR)

        discovery.anunciar(5000, txtSinHub) // mismo TXT que el vigente: no hace nada
        assertEquals("el vigente sigue registrado: nadie re-anuncia el TXT viejo", 2, registros.size)
        discovery.parar()
        verify { nsd.unregisterService(vigente) }
    }

    @Test
    fun `P1 una instancia parada no vuelve a anunciar ni a buscar aunque llegue un fallo tardio`() {
        discovery.anunciar(5000, txtHub)
        discovery.parar()

        // Cambio de sucursal o cierre de sesión: el fallo del registro llega DESPUÉS de parar.
        registros.single().onRegistrationFailed(mockk(relaxed = true), NsdManager.FAILURE_INTERNAL_ERROR)
        discovery.anunciar(5000, txtHub) // lo que haría el reintento programado
        discovery.buscar()

        assertEquals("ni un anuncio fantasma con el venue viejo y el puerto cerrado", 1, registros.size)
        assertTrue("ni una búsqueda que vuelva a tomar el multicast", busquedas.isEmpty())
    }

    @Test
    fun `P1 un resolve en vuelo de una instancia parada no publica peers y la cola se vacia`() {
        discovery.buscar()
        busquedas.single().onServiceFound(servicio("otro-aaa"))
        busquedas.single().onServiceFound(servicio("otro-bbb")) // en cola: los resolves van de uno en uno

        discovery.parar()
        resoluciones.single().onServiceResolved(servicio("otro-aaa")) // el resolve en vuelo contesta tarde

        assertTrue("ningún peer de la red apagada llega al transporte: $recibidos", recibidos.all { it.isEmpty() })
        assertEquals("lo encolado no se resuelve", 1, resoluciones.size)
    }
}
