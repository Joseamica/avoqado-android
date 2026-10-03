package com.avoqado.escritorio.red

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * mDNS de VERDAD (jmDNS por la tarjeta del local): dos instancias se encuentran y se resuelven. Fuera del CI a propósito —
 * depende de la red del equipo—; se corre con `-Pavoqado.redReal=true`.
 */
class RedLocalMdnsRealTest {
    @Test fun `dos instancias se encuentran y se resuelven por mDNS de verdad`() {
        assumeTrue("sólo con -Pavoqado.redReal=true", System.getProperty("avoqado.redReal") == "true")
        val tipo = "_avoqado-prueba._tcp"
        val nombre = "Prueba-" + System.nanoTime()
        val a = RedLocalMdns()
        val b = RedLocalMdns()
        try {
            val registrado = CountDownLatch(1)
            a.anunciar(tipo, nombre, 4321, mapOf("quien" to "a"), { registrado.countDown() }, { throw it })
            assertTrue(registrado.await(10, TimeUnit.SECONDS), "no se registró")
            val encontrado = CountDownLatch(1)
            b.buscar(tipo, { if (it == nombre) encontrado.countDown() }, {})
            assertTrue(encontrado.await(15, TimeUnit.SECONDS), "b no encontró a a")
            val listo = CountDownLatch(1)
            var r: Resuelto? = null
            b.resolver(tipo, nombre, { r = it; listo.countDown() }, { listo.countDown() })
            assertTrue(listo.await(10, TimeUnit.SECONDS))
            val res = assertNotNull(r, "no se resolvió")
            assertEquals(4321, res.puerto)
            assertEquals("a", res.txt["quien"])

            // Lo que hace LanDiscovery cuando cambia el TXT (la pantalla abre su Tablero): baja y alta con el MISMO nombre.
            // El siguiente resolve tiene que traer el TXT NUEVO, no una copia del primero.
            val anuncio = a.anunciar(tipo, nombre + "-kds", 4322, mapOf("kds" to ""), {}, { throw it })
            Thread.sleep(1_500)
            a.retirar(anuncio)
            val otraVez = CountDownLatch(1)
            a.anunciar(tipo, nombre + "-kds", 4322, mapOf("kds" to "st_barra"), { otraVez.countDown() }, { throw it })
            assertTrue(otraVez.await(10, TimeUnit.SECONDS))
            var nuevo: Resuelto? = null
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && nuevo?.txt?.get("kds") != "st_barra") {
                val l = CountDownLatch(1)
                b.resolver(tipo, nombre + "-kds", { nuevo = it; l.countDown() }, { l.countDown() })
                l.await(5, TimeUnit.SECONDS)
                if (nuevo?.txt?.get("kds") != "st_barra") Thread.sleep(500)
            }
            assertEquals("st_barra", nuevo?.txt?.get("kds"), "el resolve se quedó con el TXT viejo")
        } finally {
            a.cerrar(); b.cerrar()
        }
    }
}
