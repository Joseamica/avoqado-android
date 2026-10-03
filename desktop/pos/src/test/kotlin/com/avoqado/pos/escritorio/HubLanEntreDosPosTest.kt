package com.avoqado.pos.escritorio

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.pos.core.data.lan.LanPeer
import com.avoqado.pos.core.data.lan.LeaseClient
import com.avoqado.pos.core.data.lan.LeaseProtocol
import com.avoqado.pos.core.data.lan.LeaseServer
import com.avoqado.pos.core.data.lan.TransporteLan
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Hub LAN de punta a punta en escritorio, con mDNS de VERDAD: dos POS (dos inyectores, dos carpetas, dos deviceId) corren el
 * TransporteLan de Android tal cual sobre el sustituto NsdManager (jmDNS). Cada uno encuentra al otro por la red del local
 * (no por su propio 127.0.0.1) y uno le pide una mesa al otro por TCP. Fuera del CI: `-Pavoqado.redReal=true`.
 */
class HubLanEntreDosPosTest {

    private fun pos(nombre: String): TransporteLan =
        Inyector.crear(ActividadDeEscritorio(Files.createTempDirectory("Avoqado POS hub $nombre"))).getInstance(TransporteLan::class.java)

    private suspend fun esperarA(quien: TransporteLan, otro: TransporteLan): LanPeer = withTimeout(30_000) {
        var peer: LanPeer? = null
        while (peer == null) {
            peer = quien.peers.value.firstOrNull { it.deviceId == otro.deviceId }
            if (peer == null) delay(200)
        }
        peer
    }

    @Test fun `dos POS de escritorio se encuentran por mDNS y uno le pide una mesa al otro`() = runBlocking {
        assumeTrue("sólo con -Pavoqado.redReal=true", System.getProperty("avoqado.redReal") == "true")
        val a = pos("A")
        val b = pos("B")
        assertNotEquals(a.deviceId, b.deviceId)
        val venue = "venue-hub-" + System.nanoTime()
        try {
            a.iniciar(venue); a.conectarHub(LeaseServer()::respondTo)
            b.iniciar(venue); b.conectarHub(LeaseServer()::respondTo)

            val bVistoPorA = esperarA(a, b)
            val aVistoPorB = esperarA(b, a)
            assertNotEquals("127.0.0.1", bVistoPorA.host, "A tiene que ver a B por la red del local, no por loopback")
            assertEquals(b.puerto, bVistoPorA.port)
            assertEquals(a.puerto, aVistoPorB.port)

            val r = LeaseClient().acquire(bVistoPorA, "mesa-5", a.deviceId, "s1", "Juan")
            assertEquals(LeaseProtocol.STATUS_GRANTED, r?.status)
        } finally {
            a.detener(); b.detener()
        }
    }
}
