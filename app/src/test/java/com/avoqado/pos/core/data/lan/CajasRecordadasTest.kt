package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.local.PayloadCache
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D12 · tubería de D (hz:e5ee98bc:D4): en el ensayo del 11-nov NSD perdió a la N86 entre las bandas del WiFi y nunca la volvió a ver,
 * aunque contestaba por TCP. Aquí, con un disco de memoria y un `quien` de mentira por dirección: la caja que NSD pierde sigue mientras
 * conteste con su misma identidad, y sale con dos fallas, otra identidad o una app vieja.
 */
class CajasRecordadasTest {
    private val v = "venue-1"
    private var hora = 1_700_000_000_000L
    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private var escrituras = 0
    private var lecturaFalla = false
    private val cache = mockk<PayloadCache> {
        coEvery { loadDurable(any(), any()) } answers {
            if (lecturaFalla) throw java.io.IOException("Room no contestó")
            disco["${firstArg<String>()}|${secondArg<String>()}"]
        }
        coEvery { saveDurable(any(), any(), any()) } answers {
            escrituras++
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
        }
    }

    /** host → lo que contesta `quien` (sin entrada o null = no contesta). */
    private val contesta = mutableMapOf<String, LeaseResponse?>()
    private val preguntas = mutableListOf<String>()

    private fun cajas() = CajasRecordadas(cache, v, "yo", { p -> preguntas += p.host; contesta[p.host] }, { hora })

    private fun txt(did: String, venue: String = v, caja: String = "Caja 2", kds: String? = null) =
        LanTxt.construir(did, venue, false, 1_000, LanTxt.estacionesDe(kds), hub = true, cajaConD = caja)

    private fun soy(did: String, venue: String = v, caja: String = "Caja 2", kds: String? = null) =
        LeaseResponse(status = LeaseProtocol.STATUS_OK, txt = txt(did, venue, caja, kds))

    private fun vistaPorNsd(did: String, host: String) = requireNotNull(LanTxt.peerDesde(txt(did), host, 4321, "yo", v))

    private val n86 = vistaPorNsd("n86-aaaa", "192.168.100.152")

    private fun enDisco(): Map<String, CajasRecordadas.Recordada> =
        disco["${PayloadCache.TYPE_CAJAS_RECORDADAS}|$v"]?.let { Json.decodeFromString(CajasRecordadas.Disco.serializer(), it.json).cajas }.orEmpty()

    /** NSD la ve, la pierde (onServiceLost) y su `quien` contesta: sigue en la lista, con el TXT de esa respuesta. */
    private suspend fun perdidaQueContesta(c: CajasRecordadas, caja: String = "Caja 2", kds: String? = null) {
        c.recordar(listOf(n86))
        c.sostener(listOf(n86))
        contesta[n86.host] = soy(n86.deviceId, caja = caja, kds = kds)
        c.confirmarPerdidas(listOf(n86))
    }

    @Test fun `NSD la pierde y su quien contesta - sigue en la lista con su TXT al día`() = runTest {
        val c = cajas()
        c.recordar(listOf(n86))
        c.sostener(listOf(n86))
        assertEquals("mientras se confirma no se cae", listOf(n86.deviceId), c.porTcp(emptyList()).map { it.deviceId })
        contesta[n86.host] = soy(n86.deviceId, caja = "Caja 2 · Barra", kds = "cocina")
        c.confirmarPerdidas(listOf(n86))

        val p = c.porTcp(emptyList()).single()
        assertEquals(n86.deviceId, p.deviceId)
        assertEquals("192.168.100.152", p.host)
        assertTrue("D la sigue viendo", p.participaEnD)
        assertEquals("Caja 2 · Barra", p.nombreCaja)
        assertEquals(setOf("cocina"), p.kdsStations)
        assertTrue("lo que ve NSD manda", c.porTcp(listOf(n86)).isEmpty())
    }

    /** Ronda 1, Menor 4: a una sostenida que no contesta se le pregunta otra vez enseguida; dos seguidas y sale en la MISMA revisión. */
    @Test fun `una sostenida que deja de contestar sale en la misma revisión`() = runTest {
        val c = cajas()
        perdidaQueContesta(c)
        preguntas.clear()
        contesta[n86.host] = null
        c.revisar(emptyList())
        assertTrue("dos seguidas en la misma revisión: fuera", c.porTcp(emptyList()).isEmpty())
        assertEquals("la segunda pregunta va enseguida", listOf(n86.host, n86.host), preguntas)
    }

    @Test fun `una falla suelta no la saca - la segunda pregunta contesta`() = runTest {
        var n = 0
        val c = CajasRecordadas(cache, v, "yo", { p -> n++; if (n == 2) null else soy(p.deviceId) }, { hora })
        perdidaQueContesta(c) // pregunta 1: contesta
        c.revisar(emptyList()) // pregunta 2: no contesta; pregunta 3: contesta
        assertEquals(3, n)
        assertEquals(1, c.porTcp(emptyList()).size)
    }

    @Test fun `al perderla NSD y no contestar dos veces seguidas sale enseguida, como hoy`() = runTest {
        val c = cajas()
        c.recordar(listOf(n86))
        c.sostener(listOf(n86))
        c.confirmarPerdidas(listOf(n86)) // cerró la app: nadie escucha en su puerto
        assertTrue(c.porTcp(emptyList()).isEmpty())
        assertEquals(listOf(n86.host, n86.host), preguntas)
    }

    @Test fun `misma IP con otro deviceId u otro venue - fuera y se olvida su dirección`() = runTest {
        val c = cajas()
        perdidaQueContesta(c)
        contesta[n86.host] = soy("otra-tablet") // el DHCP le dio la IP a otro aparato
        c.revisar(emptyList())
        assertTrue(c.porTcp(emptyList()).isEmpty())
        assertFalse(n86.deviceId in enDisco())
        preguntas.clear()
        c.revisar(emptyList())
        assertTrue("ya no se le pregunta nada a esa dirección", preguntas.isEmpty())

        val d = cajas()
        perdidaQueContesta(d)
        contesta[n86.host] = soy(n86.deviceId, venue = "otro-negocio") // la plaza comparte WiFi
        d.revisar(emptyList())
        assertTrue(d.porTcp(emptyList()).isEmpty())
    }

    @Test fun `el quien de una app vieja (error) no cuenta`() = runTest {
        val c = cajas()
        c.recordar(listOf(n86))
        c.sostener(listOf(n86))
        contesta[n86.host] = LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Operación desconocida: quien")
        c.confirmarPerdidas(listOf(n86))
        assertTrue(c.porTcp(emptyList()).isEmpty())
        assertEquals("un error no se reintenta: no es la red", 1, preguntas.size)
    }

    @Test fun `reinicio de la app - las recordadas que contestan vuelven a la lista sin NSD`() = runTest {
        val d3 = vistaPorNsd("d3-bbbb", "192.168.100.60")
        cajas().recordar(listOf(n86, d3))

        val alArrancar = cajas() // otra instancia, mismo disco
        contesta[n86.host] = soy(n86.deviceId)
        alArrancar.revisar(emptyList())
        assertEquals("la que no contesta no entra", listOf(n86.deviceId), alArrancar.porTcp(emptyList()).map { it.deviceId })
        assertEquals(setOf(n86.host, d3.host), preguntas.toSet())
    }

    @Test fun `nunca este aparato, nunca un anuncio sin sucursal, y a las 24 h sin contestar se olvida`() = runTest {
        val c = cajas()
        c.recordar(listOf(n86, LanPeer("yo", "10.0.0.9", 1, venueExplicito = true), LanPeer("vieja", "10.0.0.8", 1, venueExplicito = false)))
        assertEquals(setOf(n86.deviceId), enDisco().keys)
        assertEquals(v, enDisco().getValue(n86.deviceId).venue)

        hora += CajasRecordadas.OLVIDAR_MS - 1
        c.revisar(emptyList())
        assertEquals("aún no: se le pregunta", listOf(n86.host), preguntas)
        hora += 1
        c.revisar(emptyList())
        assertTrue(enDisco().isEmpty())
        assertEquals("olvidada: ya no se le pregunta", 1, preguntas.size)
    }

    @Test fun `una que contesta se vuelve a fechar y una que NSD ve no se sondea`() = runTest {
        val c = cajas()
        c.recordar(listOf(n86))
        hora += CajasRecordadas.OLVIDAR_MS - 1
        c.revisar(listOf(n86)) // NSD la ve: al día, sin preguntarle
        assertTrue(preguntas.isEmpty())
        hora += CajasRecordadas.OLVIDAR_MS - 1
        contesta[n86.host] = soy(n86.deviceId)
        c.revisar(emptyList())
        assertEquals(listOf(n86.deviceId), c.porTcp(emptyList()).map { it.deviceId })
    }

    @Test fun `en paralelo y con su plazo - dos cajas lentas tardan lo de una`() = runTest {
        val lentas = CajasRecordadas(cache, v, "yo", { p ->
            preguntas += p.host
            delay(LeaseClient.PLAZO_QUIEN_MS)
            null
        }, { hora })
        lentas.recordar(listOf(n86, vistaPorNsd("d3-bbbb", "192.168.100.60")))
        val antes = currentTime
        lentas.revisar(emptyList())
        assertEquals(2, preguntas.size)
        assertEquals("1.5 s por caja, no 1.5 s × cajas", LeaseClient.PLAZO_QUIEN_MS, currentTime - antes)
    }

    // MARK: - Ronda de arreglos 1

    /** Menor 3: el candado no se tiene durante la red. Dos pérdidas a la vez y la revisión preguntan en paralelo, no en fila. */
    @Test fun `dos cajas perdidas a la vez y la revisión se preguntan en paralelo`() = runTest {
        val d3 = vistaPorNsd("d3-bbbb", "192.168.100.60")
        val lentas = CajasRecordadas(cache, v, "yo", { p -> delay(LeaseClient.PLAZO_QUIEN_MS); soy(p.deviceId) }, { hora })
        lentas.sostener(listOf(n86, d3))
        val antes = currentTime
        listOf(
            launch { lentas.confirmarPerdidas(listOf(n86)) },
            launch { lentas.confirmarPerdidas(listOf(d3)) },
            launch { lentas.revisar(emptyList()) },
        ).joinAll()
        assertEquals("1.5 s en total, no 1.5 s en fila por cada una", LeaseClient.PLAZO_QUIEN_MS, currentTime - antes)
        assertEquals(setOf(n86.deviceId, d3.deviceId), lentas.porTcp(emptyList()).map { it.deviceId }.toSet())
    }

    /** Menor 5a: el disco sólo se escribe si cambió la dirección o la fecha guardada tiene más de 1 h. */
    @Test fun `el disco sólo se escribe si cambió la dirección o la fecha tiene más de 1 h`() = runTest {
        val c = cajas()
        c.recordar(listOf(n86))
        assertEquals(1, escrituras)
        hora += 60_000
        c.recordar(listOf(n86))
        c.revisar(listOf(n86))
        assertEquals("un minuto después no hay nada nuevo que escribir", 1, escrituras)
        hora += 60 * 60_000
        c.recordar(listOf(n86))
        assertEquals("más de 1 h: se vuelve a fechar", 2, escrituras)
        c.recordar(listOf(vistaPorNsd(n86.deviceId, "192.168.100.153")))
        assertEquals("cambió la dirección", 3, escrituras)
        assertEquals("192.168.100.153", enDisco().getValue(n86.deviceId).host)
    }

    /** Menor 5b: un error al LEER el disco no cuenta como «vacío»: no se pisa lo guardado (se pierden las cajas del otro lado de la banda). */
    @Test fun `si leer el disco falla, lo guardado queda intacto`() = runTest {
        val d3 = vistaPorNsd("d3-bbbb", "192.168.100.60")
        val otra = vistaPorNsd("otra-cccc", "192.168.100.70")
        cajas().recordar(listOf(n86, d3))
        val c = cajas()
        lecturaFalla = true
        c.recordar(listOf(otra))
        c.revisar(listOf(otra))
        assertEquals(setOf(n86.deviceId, d3.deviceId), enDisco().keys)
        lecturaFalla = false
        c.recordar(listOf(otra))
        assertEquals("al volver a leer se junta, sin perder nada", setOf(n86.deviceId, d3.deviceId, otra.deviceId), enDisco().keys)
    }
}
