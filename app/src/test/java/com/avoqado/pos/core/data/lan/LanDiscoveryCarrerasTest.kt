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
import java.util.concurrent.ConcurrentLinkedQueue

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
    // Android 14+ (34): ahí el re-resolve corre. Abajo de 34 `refrescar` no hace nada (ver las pruebas de I1).
    private val discovery = LanDiscovery(contexto, "yo-123456", "venue-1", apiNivel = 34) { recibidos += it }

    private val txtHub = mapOf("did" to "yo-123456", "venue" to "venue-1", "hub" to "1")
    private val txtSinHub = mapOf("did" to "yo-123456", "venue" to "venue-1", "hub" to "0")

    private fun servicio(did: String, kds: String? = null, nombre: String = "Avoqado-POS-${did.take(6)}") = mockk<NsdServiceInfo>(relaxed = true) {
        every { serviceType } returns "._avoqado-pos._tcp."
        every { serviceName } returns nombre
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
        val d = LanDiscovery(contexto, "yo-123456", "venue-1", ahoraMs = { ahora }, apiNivel = 34) { recibidos += it }
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

    // MARK: - Ronda 1 de la revisión del QA D1

    /**
     * I1: antes de Android 14 un resolve NO se puede cancelar, y el NsdService de esas versiones admite UNO por cliente. Un
     * aparato que se fue sin despedirse dejaba su resolve sin contestar y, desde ahí, todo `resolveService` del proceso
     * fallaba con FAILURE_ALREADY_ACTIVE (ningún aparato nuevo se veía). Abajo de 34 el refresco no hace nada: como antes.
     */
    @Test
    fun `P1 I1 abajo de Android 14 refrescar no hace nada - ni re-resuelve ni suelta al colgado`() {
        var ahora = 0L
        val d = LanDiscovery(contexto, "yo-123456", "venue-1", ahoraMs = { ahora }, apiNivel = 33) { recibidos += it }
        d.buscar()
        busquedas.single().onServiceFound(servicio("cpad01"))
        resoluciones.single().onServiceResolved(servicio("cpad01"))
        busquedas.single().onServiceFound(servicio("ipad02")) // se cuelga: el aparato se fue sin despedirse
        busquedas.single().onServiceFound(servicio("otro03")) // en cola detrás del colgado
        assertEquals(2, resoluciones.size)

        ahora = LanDiscovery.PLAZO_DE_RESOLVE_MS + 1
        assertFalse("abajo de 34 no hay a quien preguntar", d.refrescar())
        busquedas.single().onServiceFound(servicio("otro04")) // ni siquiera un found nuevo suelta al colgado

        assertEquals("ni un resolve más: la cola espera al colgado, como antes del refresco", 2, resoluciones.size)
        verify(exactly = 0) { nsd.stopServiceResolution(any()) }
    }

    /** I1, la otra rama: en 34+ el resolve colgado SÍ se cancela (`stopServiceResolution`) al soltarlo. */
    @Test
    fun `P1 I1 en Android 14 el resolve colgado se cancela al soltarlo`() {
        var ahora = 0L
        val d = LanDiscovery(contexto, "yo-123456", "venue-1", ahoraMs = { ahora }, apiNivel = 34) { recibidos += it }
        d.buscar()
        busquedas.single().onServiceFound(servicio("cpad01")) // se cuelga
        busquedas.single().onServiceFound(servicio("ipad02")) // en cola

        ahora = LanDiscovery.PLAZO_DE_RESOLVE_MS + 1
        d.refrescar()

        verify(exactly = 1) { nsd.stopServiceResolution(resoluciones[0]) }
        assertEquals("y sigue con el siguiente", 2, resoluciones.size)
    }

    /**
     * I2: NSD también descubre el anuncio de ESTE aparato (por eso `peerDesde` filtra el `did` propio). Contarlo como
     * «conocido» hacía que una caja sola esperara 1 s en CADA venta antes de imprimir. Se compara por el prefijo del
     * `did` dentro del nombre (`contains`: el SO puede renombrar a «(2)» si hay colisión).
     */
    @Test
    fun `P1 I2 el propio anuncio no cuenta como conocido - una caja sola no espera ni se re-resuelve`() {
        discovery.buscar()
        busquedas.single().onServiceFound(servicio("yo-123456"))
        resoluciones.single().onServiceResolved(servicio("yo-123456"))
        busquedas.single().onServiceFound(servicio("yo-123456", nombre = "Avoqado-POS-yo-123 (2)")) // renombrado por colisión
        resoluciones[1].onServiceResolved(servicio("yo-123456", nombre = "Avoqado-POS-yo-123 (2)"))

        assertFalse("sólo se conoce a sí misma: no hay a quien esperar", discovery.refrescar())
        assertEquals("ni gasta un resolve en sí misma", 2, resoluciones.size)

        busquedas.single().onServiceFound(servicio("cpad01"))
        resoluciones[2].onServiceResolved(servicio("cpad01"))
        assertTrue("con otro aparato conocido sí", discovery.refrescar())
        assertEquals("y sólo re-resuelve al ajeno", 4, resoluciones.size)
    }

    /**
     * M1: el plazo no lo vigila un reloj, así que sólo se evalúa cuando pasa algo. Antes sólo lo miraba `refrescar` (cada
     * 60 s): un aparato que llega mientras otro está colgado esperaba a la siguiente revisión. Ahora cualquier arranque de
     * la cola lo mira.
     */
    @Test
    fun `P1 M1 un servicio nuevo tambien suelta al resolve colgado sin esperar al refresco`() {
        var ahora = 0L
        val d = LanDiscovery(contexto, "yo-123456", "venue-1", ahoraMs = { ahora }, apiNivel = 34) { recibidos += it }
        d.buscar()
        busquedas.single().onServiceFound(servicio("cpad01")) // se cuelga
        busquedas.single().onServiceFound(servicio("ipad02")) // en cola

        ahora = LanDiscovery.PLAZO_DE_RESOLVE_MS + 1
        busquedas.single().onServiceFound(servicio("otro03"))

        assertEquals("el arranque de la cola soltó al colgado y siguió con ipad02", 2, resoluciones.size)
    }

    /**
     * M2: `refrescar` drena desde otro hilo que el de NSD. Entre «la cola salió vacía» y «suelto el turno» puede llegar un
     * servicio: su drenado falla porque el turno sigue puesto, y nadie lo retoma. Se simula esa ventana con una cola cuyo
     * `poll` vacío dispara el found (reflexión: no hay otra costura en esa ventana sin tocar producción).
     */
    @Test
    fun `P1 M2 un servicio que llega cuando la cola salio vacia no se queda varado`() {
        discovery.buscar()
        busquedas.single().onServiceFound(servicio("cpad01")) // en vuelo
        var armada = true
        LanDiscovery::class.java.getDeclaredField("resolveQueue").apply { isAccessible = true }.set(
            discovery,
            object : ConcurrentLinkedQueue<NsdServiceInfo>() {
                override fun poll(): NsdServiceInfo? = super.poll().also {
                    if (it == null && armada) { armada = false; busquedas.single().onServiceFound(servicio("ipad02")) }
                }
            },
        )

        resoluciones[0].onServiceResolved(servicio("cpad01")) // libera el turno: el drenado ve la cola vacía y en ese instante llega ipad02

        assertEquals("ipad02 se retoma al soltar el turno", 2, resoluciones.size)
    }
}
