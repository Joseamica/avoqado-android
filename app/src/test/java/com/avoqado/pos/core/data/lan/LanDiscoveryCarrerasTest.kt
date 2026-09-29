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
import org.junit.Assert.assertFalse
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

    private fun servicio(did: String, kds: String? = null) = mockk<NsdServiceInfo>(relaxed = true) {
        every { serviceType } returns "._avoqado-pos._tcp."
        every { serviceName } returns "Avoqado-POS-${did.take(6)}"
        every { attributes } returns mapOf("did" to did.toByteArray(), "venue" to "venue-1".toByteArray(), "hub" to "1".toByteArray()) +
            listOfNotNull(kds?.let { "kds" to it.toByteArray() })
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

    // MARK: - QA D1 (29-sep): una pantalla ya conocida cambia su TXT y NSD no avisa

    private fun peersVistos() = recibidos.lastOrNull().orEmpty()

    /**
     * QA D1 (29-sep, dos Sunmi con Android 14): la caja descubrió la pantalla ANTES de que abriera su Tablero (sin `kds=`);
     * la pantalla se re-anunció con `kds=qa35-barra` y NSD no avisó nada — ni perdido ni encontrado. Resolver una sola vez
     * dejaba el peer viejo para siempre y toda comanda salía en papel hasta reiniciar la caja. El refresco re-resuelve.
     */
    @Test
    fun `P1 una pantalla conocida que cambia su TXT se actualiza al refrescar`() {
        assertFalse("sin nada conocido no hay a quien preguntar", discovery.refrescar())
        discovery.buscar()
        busquedas.single().onServiceFound(servicio("cpad01"))
        resoluciones.single().onServiceResolved(servicio("cpad01"))
        assertEquals(emptySet<String>(), peersVistos().single().kdsStations)

        assertTrue("hay una conocida: vale la pena esperarla", discovery.refrescar())
        assertEquals("el refresco la vuelve a resolver", 2, resoluciones.size)
        resoluciones[1].onServiceResolved(servicio("cpad01", kds = "qa35-barra"))

        assertEquals(setOf("qa35-barra"), peersVistos().single().kdsStations)
    }

    /** Cada minuto y por cada peer: un re-resolve que trae lo mismo no despierta a nadie (ni reordena la lista). */
    @Test
    fun `P1 un re-resolve con los mismos datos no vuelve a avisar`() {
        discovery.buscar()
        busquedas.single().onServiceFound(servicio("cpad01"))
        resoluciones.single().onServiceResolved(servicio("cpad01"))
        val avisos = recibidos.size

        discovery.refrescar()
        assertEquals(2, resoluciones.size)
        resoluciones[1].onServiceResolved(servicio("cpad01"))

        assertEquals("sin cambios no hay emision", avisos, recibidos.size)
    }

    /**
     * Ledger T4 (carrera onServiceLost contra onPeerResolved): un re-resolve que estaba en vuelo cuando NSD dijo «perdido»
     * no revive al peer; uno que seguía en cola ni se pide (el resolve de un servicio que ya no está puede no contestar nunca).
     */
    @Test
    fun `P1 un re-resolve en vuelo o en cola no revive a un peer perdido`() {
        discovery.buscar()
        busquedas.single().onServiceFound(servicio("cpad01"))
        busquedas.single().onServiceFound(servicio("ipad02"))
        resoluciones[0].onServiceResolved(servicio("cpad01"))
        resoluciones[1].onServiceResolved(servicio("ipad02"))
        assertEquals(2, peersVistos().size)

        discovery.refrescar() // cpad01 en vuelo, ipad02 en cola
        assertEquals(3, resoluciones.size)
        busquedas.single().onServiceLost(servicio("cpad01"))
        busquedas.single().onServiceLost(servicio("ipad02"))
        resoluciones[2].onServiceResolved(servicio("cpad01")) // contesta tarde, con los datos de antes

        assertTrue("ninguno revive: ${peersVistos()}", peersVistos().isEmpty())
        assertEquals("el de la cola ya no se pide", 3, resoluciones.size)
    }

    /**
     * Android no le pone plazo a un resolve (y antes de Android 14 no se puede cancelar): uno que nunca contesta —un
     * servicio que se fue sin despedirse— trababa la cola EN SERIE para siempre, y con el refresco de cada minuto eso
     * pasaria seguido. El refresco lo suelta tras el plazo; su respuesta tardia no arranca otro resolve encima.
     */
    @Test
    fun `P1 un resolve que nunca contesta no traba la cola - el refresco lo suelta tras el plazo`() {
        var ahora = 0L
        val d = LanDiscovery(contexto, "yo-123456", "venue-1", ahoraMs = { ahora }) { recibidos += it }
        d.buscar()
        busquedas.single().onServiceFound(servicio("cpad01")) // se cuelga
        busquedas.single().onServiceFound(servicio("ipad02")) // en cola

        ahora = LanDiscovery.PLAZO_DE_RESOLVE_MS
        d.refrescar()
        assertEquals("dentro del plazo se espera", 1, resoluciones.size)

        ahora = LanDiscovery.PLAZO_DE_RESOLVE_MS + 1
        d.refrescar()
        assertEquals("vencido: sigue con el siguiente", 2, resoluciones.size)
        resoluciones[0].onServiceResolved(servicio("cpad01")) // el colgado contesta tarde
        assertEquals("en serie: la respuesta tardia no arranca otro con el siguiente en vuelo", 2, resoluciones.size)
        resoluciones[1].onServiceResolved(servicio("ipad02"))

        assertEquals(setOf("cpad01", "ipad02"), peersVistos().map { it.deviceId }.toSet())
    }
}
