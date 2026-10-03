package com.avoqado.escritorio.red

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NsdManagerDeEscritorioTest {
    private val hostsOriginal = BarridoDeImpresoras.hostsLocales
    private val puertoOriginal = BarridoDeImpresoras.puerto
    @AfterTest fun restaurar() { BarridoDeImpresoras.hostsLocales = hostsOriginal; BarridoDeImpresoras.puerto = puertoOriginal }

    private open class Escucha : NsdManager.DiscoveryListener {
        val eventos = java.util.Collections.synchronizedList(mutableListOf<String>())
        val encontrado = CountDownLatch(1)
        val detenido = CountDownLatch(1)
        @Volatile var info: NsdServiceInfo? = null
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { eventos += "fallo" }
        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) { eventos += "fallo-stop" }
        override fun onDiscoveryStarted(serviceType: String?) { eventos += "iniciado" }
        override fun onDiscoveryStopped(serviceType: String?) { eventos += "detenido"; detenido.countDown() }
        override fun onServiceFound(serviceInfo: NsdServiceInfo?) { eventos += "encontrado"; info = serviceInfo; encontrado.countDown() }
        override fun onServiceLost(serviceInfo: NsdServiceInfo?) { eventos += "perdido" }
    }

    @Test fun `impresora cruda se descubre por barrido, se resuelve en el acto y se detiene`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { servidor ->
            BarridoDeImpresoras.puerto = servidor.localPort
            BarridoDeImpresoras.hostsLocales = { listOf("127.0.0.1") }
            val nsd = NsdManager(); val escucha = Escucha()
            nsd.discoverServices("_pdl-datastream._tcp", NsdManager.PROTOCOL_DNS_SD, escucha)
            assertTrue(escucha.encontrado.await(10, TimeUnit.SECONDS))
            assertEquals("iniciado", escucha.eventos.first())
            val info = assertNotNull(escucha.info)
            assertEquals("127.0.0.1", info.host.hostAddress); assertEquals(servidor.localPort, info.port)
            var resuelto: NsdServiceInfo? = null
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) { resuelto = serviceInfo }
            })
            assertEquals("127.0.0.1", resuelto?.host?.hostAddress)
            nsd.stopServiceDiscovery(escucha)
            assertTrue(escucha.detenido.await(2, TimeUnit.SECONDS))
        }
    }

    /**
     * En la app el stop llega por la MISMA instancia (ContextoDeEscritorio guarda un servicio por nombre); el mapa de
     * barridos es estático sólo para blindar otra instancia (otro contexto, pruebas). Tiene que alcanzar al barrido igual,
     * y después de onDiscoveryStopped no puede llegar ningún onServiceFound.
     */
    @Test fun `el stop desde otra instancia detiene el barrido`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { servidor ->
            BarridoDeImpresoras.puerto = servidor.localPort
            BarridoDeImpresoras.hostsLocales = { listOf("127.0.0.1") }
            val escucha = Escucha()
            NsdManager().discoverServices("_pdl-datastream._tcp", NsdManager.PROTOCOL_DNS_SD, escucha)
            NsdManager().stopServiceDiscovery(escucha)
            assertTrue(escucha.detenido.await(2, TimeUnit.SECONDS), "el stop de otra instancia no alcanzó al barrido: ${escucha.eventos.toList()}")
            Thread.sleep(800)                                                    // lo que el barrido habría tardado en avisar
            val eventos = escucha.eventos.toList()
            assertEquals(listOf("detenido"), eventos.dropWhile { it != "detenido" }, "nada después de detenido: $eventos")
        }
    }

    /** #25: un listener que se detiene DENTRO de onDiscoveryStarted no deja un barrido corriendo (ni hallazgos después). */
    @Test fun `parar dentro de onDiscoveryStarted no deja el barrido corriendo`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { servidor ->
            BarridoDeImpresoras.puerto = servidor.localPort
            BarridoDeImpresoras.hostsLocales = { listOf("127.0.0.1") }
            val nsd = NsdManager()
            val escucha = object : Escucha() {
                override fun onDiscoveryStarted(serviceType: String?) {
                    super.onDiscoveryStarted(serviceType)
                    nsd.stopServiceDiscovery(this)
                }
            }
            nsd.discoverServices("_pdl-datastream._tcp", NsdManager.PROTOCOL_DNS_SD, escucha)
            Thread.sleep(800)                                                    // lo que el barrido habría tardado en avisar
            assertEquals(listOf("iniciado", "detenido"), escucha.eventos.toList())
        }
    }

    @Test fun `el mismo listener dos veces detiene el barrido anterior antes de empezar`() {
        BarridoDeImpresoras.hostsLocales = { emptyList() }
        val nsd = NsdManager(); val escucha = Escucha()
        nsd.discoverServices("_pdl-datastream._tcp", NsdManager.PROTOCOL_DNS_SD, escucha)
        nsd.discoverServices("_pdl-datastream._tcp", NsdManager.PROTOCOL_DNS_SD, escucha)
        nsd.stopServiceDiscovery(escucha)
        assertEquals(listOf("iniciado", "detenido", "iniciado", "detenido"), escucha.eventos.toList())
    }

    // El Hub LAN (`_avoqado-pos._tcp`) ya no es isla: su contrato se prueba en NsdManagerHubLanTest (mDNS falso).
}
