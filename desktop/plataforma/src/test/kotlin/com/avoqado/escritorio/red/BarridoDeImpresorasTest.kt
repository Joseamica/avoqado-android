package com.avoqado.escritorio.red

import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BarridoDeImpresorasTest {
    private val hostsOriginal = BarridoDeImpresoras.hostsLocales
    private val puertoOriginal = BarridoDeImpresoras.puerto
    private val sondaOriginal = BarridoDeImpresoras.sonda
    @AfterTest fun restaurar() {
        BarridoDeImpresoras.hostsLocales = hostsOriginal; BarridoDeImpresoras.puerto = puertoOriginal; BarridoDeImpresoras.sonda = sondaOriginal
    }

    @Test fun `un slash24 da 253 hosts sin red, broadcast ni uno mismo`() {
        val hosts = BarridoDeImpresoras.hostsDeLaSubred("192.168.1.37", 24)
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.0" in hosts); assertFalse("192.168.1.255" in hosts); assertFalse("192.168.1.37" in hosts)
    }

    @Test fun `un slash16 se capa al slash24 que contiene la IP`() {
        val hosts = BarridoDeImpresoras.hostsDeLaSubred("10.1.7.20", 16)
        assertTrue(hosts.all { it.startsWith("10.1.7.") }); assertEquals(253, hosts.size)
    }

    @Test fun `entradas raras dan vacio`() {
        assertEquals(emptyList(), BarridoDeImpresoras.hostsDeLaSubred("no-es-ip", 24))
        assertEquals(emptyList(), BarridoDeImpresoras.hostsDeLaSubred("192.168.1.10", 31))
        assertEquals(emptyList(), BarridoDeImpresoras.hostsDeLaSubred("+192.168.1.10", 24))
    }

    @Test fun `el barrido toca a lo mucho 1024 direcciones, sumando todas las interfaces`() {
        val cinco = (1..5).flatMap { BarridoDeImpresoras.hostsDeLaSubred("10.0.$it.10", 24) }     // 5 interfaces × 253 = 1,265
        assertEquals(1_265, cinco.size)
        val aProbar = BarridoDeImpresoras.hostsAProbar(cinco + cinco.take(10))                     // con repetidos
        assertEquals(1_024, aProbar.size)
        assertEquals(cinco.take(1_024), aProbar)
        val tres = cinco.take(3 * 253)
        assertEquals(tres, BarridoDeImpresoras.hostsAProbar(tres + tres))                            // abajo del tope: todas, sin repetir
    }

    @Test fun `encuentra al que escucha y termina`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { servidor ->
            BarridoDeImpresoras.puerto = servidor.localPort
            BarridoDeImpresoras.hostsLocales = { listOf("127.0.0.1", "127.0.0.2") }   // .2 no escucha en macOS/Windows: no contesta
            val hallados = java.util.Collections.synchronizedList(mutableListOf<String>())
            val fin = CountDownLatch(1)
            BarridoDeImpresoras.iniciar(alEncontrar = { hallados += it.hostAddress }, alTerminar = { fin.countDown() })
            assertTrue(fin.await(10, TimeUnit.SECONDS), "el barrido tiene que terminar solo")
            assertEquals(listOf("127.0.0.1"), hallados.toList())
        }
    }

    /**
     * #24: detener con sondas EN CURSO. Las que esperaban turno no arrancan, y las que ya iban (un connect ya iniciado no
     * se corta con shutdownNow) contestan «sí» DESPUÉS de detener: ese hallazgo no puede llegar.
     */
    @Test fun `detener con sondas en curso - no arrancan las pendientes ni llegan hallazgos despues`() {
        BarridoDeImpresoras.hostsLocales = { List(200) { "10.255.255.${it + 1}" } }
        val enCurso = CountDownLatch(BarridoDeImpresoras.CONCURRENCIA)
        val suelta = CountDownLatch(1)
        val sondas = AtomicInteger()
        BarridoDeImpresoras.sonda = { _, _ ->
            sondas.incrementAndGet(); enCurso.countDown()
            var interrumpida = false
            while (true) {                                                              // como un connect: no se deja interrumpir
                try { suelta.await(); break } catch (e: InterruptedException) { interrumpida = true }
            }
            if (interrumpida) Thread.currentThread().interrupt()
            true                                                                        // «contesta»
        }
        val hallados = Collections.synchronizedList(mutableListOf<String>())
        val fin = CountDownLatch(1)
        val detener = BarridoDeImpresoras.iniciar(alEncontrar = { hallados += it.hostAddress }, alTerminar = { fin.countDown() })
        assertTrue(enCurso.await(10, TimeUnit.SECONDS), "no arrancaron las primeras ${BarridoDeImpresoras.CONCURRENCIA} sondas")
        detener()
        suelta.countDown()
        assertTrue(fin.await(10, TimeUnit.SECONDS), "detener termina el barrido")
        assertEquals(BarridoDeImpresoras.CONCURRENCIA, sondas.get(), "arrancaron sondas que esperaban turno después de detener")
        assertEquals(emptyList(), hallados.toList(), "llegaron hallazgos después de detener")
    }
}
