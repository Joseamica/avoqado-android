package com.avoqado.pos.core.data.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D12 · tubería de D (hz:e5ee98bc:D4) en el transporte de verdad: NSD de mentira (se disparan sus callbacks a mano, como en el ensayo
 * del 11-nov: la D3 vio a la N86 y después NSD dijo «perdido» para siempre) y el `quien` de la N86 inyectado. El socket propio y el
 * `quien` por loopback son reales.
 */
class CajasPorTcpEnElTransporteTest {
    private val config = MutableStateFlow(PrintConfig())
    private val printConfig = mockk<PrintConfigRepository> {
        every { this@mockk.config } returns this@CajasPorTcpEnElTransporteTest.config
        every { getCurrentConfig() } answers { this@CajasPorTcpEnElTransporteTest.config.value }
    }
    private val outbox = mockk<SyncOutbox> { every { deviceId } returns "tablet-prueba" }
    private val disco = Collections.synchronizedMap(mutableMapOf<String, PayloadCache.Cached>())
    /** Qué tipos se leyeron del disco, en orden; y si leer el puerto propio truena (ronda 2). */
    private val leidos = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var puertoIlegible = false
    private val cache = mockk<PayloadCache> {
        coEvery { loadDurable(any(), any()) } answers {
            leidos += firstArg<String>()
            if (puertoIlegible && firstArg<String>() == PayloadCache.TYPE_PUERTO_PROPIO) throw java.io.IOException("Room no contestó")
            disco["${firstArg<String>()}|${secondArg<String>()}"]
        }
        coEvery { saveDurable(any(), any(), any()) } coAnswers {
            val retenido = retenerPuerto?.takeIf { firstArg<String>() == PayloadCache.TYPE_PUERTO_PROPIO }
            if (retenido == null) {
                disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
            } else {
                retenerPuerto = null
                // Como Room: la escritura que ya empezó termina aunque su corrutina se cancele.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    retenido.first.complete(Unit)
                    retenido.second.await()
                    disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
                }
            }
        }
    }
    /** Familia B: (guardando, soltar) detiene a medias la SIGUIENTE escritura del puerto propio. */
    @Volatile private var retenerPuerto: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
    private val busquedas = Collections.synchronizedList(mutableListOf<NsdManager.DiscoveryListener>())
    private val resoluciones = Collections.synchronizedList(mutableListOf<NsdManager.ResolveListener>())
    private val nsd = mockk<NsdManager>(relaxed = true) {
        every { discoverServices(any<String>(), any<Int>(), capture(busquedas)) } just runs
        every { resolveService(any(), capture(resoluciones)) } just runs
    }

    private val laN86 = "n86-aaaa"
    private val n86 = mockk<NsdServiceInfo>(relaxed = true) {
        every { serviceType } returns "._avoqado-pos._tcp."
        every { serviceName } returns "Avoqado-POS-n86-aa"
        every { attributes } returns mapOf("did" to laN86, "venue" to "venue-1", "hub" to "1", "d" to "1", "caja" to "Caja 2")
            .mapValues { it.value.toByteArray() }
        every { host } returns InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 100, 152.toByte()))
        every { port } returns 36056
    }

    /** Lo que contesta el `quien` en la dirección de la N86 (null = nada); [freno] lo detiene. */
    @Volatile private var respuesta: LeaseResponse? = null
    @Volatile private var freno: CompletableDeferred<Unit>? = null
    private val preguntas = AtomicInteger()
    private val soyLaN86 = LeaseResponse(
        status = LeaseProtocol.STATUS_OK,
        txt = mapOf("did" to laN86, "venue" to "venue-1", "hub" to "1", "d" to "1", "caja" to "Caja 2 · Barra"),
    )

    private fun transporte() = TransporteLan(mockk<Context>(relaxed = true) { every { getSystemService(Context.NSD_SERVICE) } returns nsd }, outbox, printConfig, cache)
        .also { t ->
            t.preguntarQuien = { p ->
                freno?.await()
                preguntas.incrementAndGet()
                if (p.host == "192.168.100.152" && p.port == 36056) respuesta else null
            }
        }

    private suspend fun encendido(t: TransporteLan): TransporteLan {
        t.iniciar("venue-1")
        t.conectarHub(LeaseServer()::respondTo)
        withTimeout(10_000) { while (t.puerto <= 0 || t.peers.value.none { it.deviceId == "tablet-prueba" }) delay(25) }
        return t
    }

    private fun TransporteLan.laN86(): LanPeer? = peers.value.firstOrNull { it.deviceId == laN86 }

    private suspend fun esperar(que: String, cond: () -> Boolean) = withTimeout(5_000) { while (!cond()) delay(25) }.also { assertTrue(que, cond()) }

    private suspend fun vistaPorNsd(t: TransporteLan) {
        busquedas.last().onServiceFound(n86)
        resoluciones.last().onServiceResolved(n86)
        esperar("NSD la ve") { t.laN86() != null }
    }

    /** La puerta de D sobre ESTE transporte: la N86 abrió la cuenta y concede; nadie más contesta. */
    private val clienteDeCuentas = mockk<LeaseClient> {
        coEvery { preguntarCuenta(any(), any(), any(), any()) } answers {
            if (firstArg<LanPeer>().deviceId == laN86) LeaseResponse(status = LeaseProtocol.STATUS_GRANTED) else null
        }
    }

    private fun cuentasSobre(t: TransporteLan) = CuentasEntreCajas(t, mockk<DuenoDeCuentas>(relaxed = true) {
        every { deviceId } returns "tablet-prueba"
        every { nombreCaja } returns "Caja 1"
        coEvery { apartar(any(), any(), any()) } returns null
    }, cache) { null }.also { it.client = clienteDeCuentas; it.encender("venue-1") }

    @Test fun `NSD la pierde y su quien contesta - sigue en la lista, D la encuentra como dueña, y si muere sale en la misma revisión`() = runBlocking {
        val t = encendido(transporte())
        try {
            vistaPorNsd(t)
            respuesta = soyLaN86
            busquedas.last().onServiceLost(n86)
            assertTrue("no se cae mientras se confirma", t.laN86() != null)
            esperar("su TXT sale del quien") { t.laN86()?.nombreCaja == "Caja 2 · Barra" }

            val entre = cuentasSobre(t)
            try {
                val r = entre.apartar("venue-1", "o1", "i-1", "Beto", enLinea = false)
                assertEquals(Respuesta.Concedida, r.respuesta)
                assertEquals(laN86, r.deviceId)
                assertEquals("Caja 2 · Barra", r.caja)
            } finally {
                entre.apagar()
            }

            respuesta = null
            t.revisarPorTcp()
            assertNull("dos seguidas, en la misma revisión (Menor 4): fuera; mesas no se queda en isla ~2 min", t.laN86())
            assertEquals("abajo de Android 14 (detalle 7) es TCP puro: ningún resolve más de NSD", 1, resoluciones.size)
        } finally {
            t.detener()
        }
    }

    @Test fun `misma IP con otro aparato - sale y D no le pregunta nada de una cuenta`() = runBlocking {
        val t = encendido(transporte())
        try {
            vistaPorNsd(t)
            respuesta = LeaseResponse(status = LeaseProtocol.STATUS_OK, txt = mapOf("did" to "otra-tablet", "venue" to "venue-1", "d" to "1"))
            busquedas.last().onServiceLost(n86)
            esperar("fuera") { t.laN86() == null }

            val entre = cuentasSobre(t)
            try {
                assertNull(entre.apartar("venue-1", "o1", "i-1", "Beto", enLinea = false).respuesta)
                coVerify(exactly = 0) { clienteDeCuentas.preguntarCuenta(any(), any(), any(), any()) }
            } finally {
                entre.apagar()
            }
        } finally {
            t.detener()
        }
    }

    @Test fun `reinicio de la app - la recordada que contesta vuelve a la lista sin NSD`() = runBlocking {
        val antes = encendido(transporte())
        try {
            vistaPorNsd(antes)
            esperar("quedó en disco") { disco.keys.any { it.startsWith(PayloadCache.TYPE_CAJAS_RECORDADAS) } }
        } finally {
            antes.detener()
        }
        respuesta = soyLaN86
        val despues = encendido(transporte()) // NSD nunca la vuelve a ver
        try {
            esperar("la primera revisión la trae") { despues.laN86()?.nombreCaja == "Caja 2 · Barra" }
        } finally {
            despues.detener()
        }
    }

    @Test fun `el sondeo nunca corre en el hilo de quien llama`() = runBlocking {
        val t = encendido(transporte())
        try {
            vistaPorNsd(t)
            respuesta = soyLaN86
            freno = CompletableDeferred() // el quien de la N86 tarda lo que tarde
            val inicio = System.nanoTime()
            busquedas.last().onServiceLost(n86) // el hilo de NSD
            t.refrescarPeers() // la entrega de cocina
            val ms = (System.nanoTime() - inicio) / 1_000_000
            assertTrue("ni NSD ni la entrega esperan al quien ($ms ms)", ms < 500)
            assertTrue("y sigue en la lista mientras tanto", t.laN86() != null)
            freno?.complete(Unit)
            esperar("ya contestó") { t.laN86()?.nombreCaja == "Caja 2 · Barra" }
        } finally {
            freno?.complete(Unit)
            t.detener()
        }
    }

    @Test fun `quien por el socket real contesta el TXT del anuncio`() = runBlocking {
        val t = encendido(transporte())
        try {
            t.conectarCuentas({ "" }, "Caja 1")
            val r = LeaseClient().quien(LanPeer("x", "127.0.0.1", t.puerto))
            assertEquals(LeaseProtocol.STATUS_OK, r?.status)
            val txt = requireNotNull(r?.txt)
            assertEquals("tablet-prueba", txt["did"])
            assertEquals("venue-1", txt["venue"])
            assertEquals("1", txt["hub"])
            assertEquals("1", txt["d"])
            assertEquals("Caja 1", txt["caja"])
            assertTrue(txt.containsKey("boot") && txt.containsKey("wired"))
        } finally {
            t.detener()
        }
    }

    @Test fun `quien respeta 1_5 s por caja aunque la otra acepte y no conteste`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { mudo ->
            val abiertas = Collections.synchronizedList(mutableListOf<Socket>())
            val aceptar = Thread { runCatching { while (true) abiertas += mudo.accept() } }.apply { isDaemon = true; start() }
            val inicio = System.nanoTime()
            val r = runBlocking { LeaseClient().quien(LanPeer("x", "127.0.0.1", mudo.localPort)) }
            val ms = (System.nanoTime() - inicio) / 1_000_000
            assertNull(r)
            assertTrue("≈ 1.5 s, nunca los 3 s de una lectura de mesas ($ms ms)", ms in 1_400..2_300)
            abiertas.forEach { runCatching { it.close() } }
            aceptar.interrupt()
        }
    }

    @Test fun `una caja que NSD nunca vio no se sondea`() = runBlocking {
        val t = encendido(transporte())
        try {
            t.revisarPorTcp()
            assertEquals(0, preguntas.get())
            assertFalse(t.peers.value.any { it.deviceId != "tablet-prueba" })
        } finally {
            t.detener()
        }
    }

    // MARK: - Ronda de arreglos 1, Menor 1: el puerto propio sobrevive al reinicio de la app

    /**
     * Al cerrar el socket, el hilo que espera en `accept` lo suelta un instante después (el JDK difiere el cierre real): se espera a que
     * el puerto quede libre, como cuando el proceso de la app muere de verdad.
     */
    private suspend fun esperarQueSeLibere(puerto: Int) = withTimeout(5_000) {
        while (runCatching { ServerSocket().use { it.reuseAddress = true; it.bind(java.net.InetSocketAddress(puerto)) } }.isFailure) delay(25)
    }

    @Test fun `la caja que reinicia la app conserva su puerto y vuelve a contestar quien en la dirección recordada`() = runBlocking {
        val antes = encendido(transporte())
        val recordado = antes.puerto
        // Ronda 2: `encendido` regresa al ver el puerto, pero se guarda justo después; sin esperar, una nube cargada lo pierde al azar.
        esperar("guardó su puerto") { disco["${PayloadCache.TYPE_PUERTO_PROPIO}|venue-1"]?.json == recordado.toString() }
        antes.detener() // la app se cierra (actualización, cierre de sesión, reinicio nocturno)
        esperarQueSeLibere(recordado)
        val despues = encendido(transporte()) // la misma tablet: mismo disco
        try {
            assertEquals(recordado, despues.puerto)
            val r = LeaseClient().quien(LanPeer("tablet-prueba", "127.0.0.1", recordado))
            assertEquals(LeaseProtocol.STATUS_OK, r?.status)
            assertEquals("tablet-prueba", r?.txt?.get("did"))
        } finally {
            despues.detener()
        }
    }

    @Test fun `si su puerto está ocupado abre otro como hoy y guarda el nuevo`() = runBlocking {
        ServerSocket(0).use { ocupado -> // otro proceso tomó el puerto guardado
            disco["${PayloadCache.TYPE_PUERTO_PROPIO}|venue-1"] = PayloadCache.Cached(ocupado.localPort.toString(), 0)
            val t = encendido(transporte())
            try {
                assertTrue("otro puerto, no se queda sin red", t.puerto > 0 && t.puerto != ocupado.localPort)
                esperar("guardó el nuevo") { disco["${PayloadCache.TYPE_PUERTO_PROPIO}|venue-1"]?.json == t.puerto.toString() }
            } finally {
                t.detener()
            }
        }
    }

    /** Ronda 2: si LEER el puerto guardado truena, se abre uno efímero como hoy, pero en esa sesión no se escribe encima del bueno. */
    @Test fun `si leer el puerto guardado falla, abre otro pero no pisa el guardado`() = runBlocking {
        disco["${PayloadCache.TYPE_PUERTO_PROPIO}|venue-1"] = PayloadCache.Cached("40404", 0)
        puertoIlegible = true
        val t = encendido(transporte())
        try {
            assertTrue(t.puerto > 0)
            // En la vuelta, guardar el puerto va antes de la revisión de cajas: ésta lee su disco DESPUÉS.
            esperar("la vuelta llegó a la revisión") { PayloadCache.TYPE_CAJAS_RECORDADAS in leidos }
            assertEquals("lo guardado sigue intacto", "40404", disco["${PayloadCache.TYPE_PUERTO_PROPIO}|venue-1"]?.json)
        } finally {
            t.detener()
        }
    }

    // MARK: - Pasada por familia (familia B, el puerto propio)

    /**
     * Familia B, el puerto propio: el guardado del encendido anterior tarda (se reinicia la red local a media escritura). El encendido nuevo
     * leía el disco sin esperar, abría otro puerto y lo guardaba; luego aterrizaba el guardado viejo y el disco se quedaba para siempre con
     * un puerto que no es el del socket (la memoria decía que ya estaba guardado). Ahora leer y escribir van bajo el mismo candado, y se
     * escribe el puerto del socket de ESE momento.
     */
    @Test fun `familia B - un guardado del puerto que tarda no deja en disco otro puerto que el del socket`() = runBlocking {
        val guardando = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        retenerPuerto = guardando to soltar
        val t = transporte()
        try {
            val llave = "${PayloadCache.TYPE_PUERTO_PROPIO}|venue-1"
            t.iniciar("venue-1")
            t.conectarHub(LeaseServer()::respondTo)
            withTimeout(10_000) { guardando.await() } // el primer encendido ya guarda su puerto, y tarda
            t.detener()
            t.iniciar("venue-1") // se reinicia la red local dentro del proceso
            // Antes, el encendido nuevo leía sin esperar y guardaba el suyo enseguida; ahora espera a que termine el guardado viejo.
            kotlinx.coroutines.withTimeoutOrNull(2_000) { while (!(t.puerto > 0 && disco[llave]?.json == t.puerto.toString())) delay(25) }
            soltar.complete(Unit)
            esperar("el disco dice el puerto del socket") { t.puerto > 0 && disco[llave]?.json == t.puerto.toString() }
        } finally {
            soltar.complete(Unit)
            t.detener()
        }
    }
}
