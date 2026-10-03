package com.avoqado.escritorio.red

import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedLocalMdnsTest {

    private val wifi = InetAddress.getByName("192.168.100.70")
    private val cable = InetAddress.getByName("192.168.100.71")
    private var direccion: InetAddress? = wifi
    private val creados = mutableListOf<MdnsFalso>()
    private val directo = Executor { it.run() }
    private val red = RedLocalMdns(
        direccionDelLocal = { direccion },
        crearMdns = { d -> MdnsFalso(d).also { creados += it } },
        avisos = directo, trabajo = directo, resolviendo = directo,
    )
    private val mdns get() = creados.last()

    @Test fun `anunciar registra en la tarjeta del local y avisa el nombre final`() {
        var registrado: String? = null
        red.anunciar("_avoqado-pos._tcp", "Avoqado-POS-abc123", 4321, mapOf("did" to "abc123"), { registrado = it }, { error("no") })
        assertEquals(wifi, mdns.direccion)
        assertEquals(listOf("Avoqado-POS-abc123:4321:{did=abc123}"), mdns.registrados)
        assertEquals("Avoqado-POS-abc123", registrado)
    }

    @Test fun `retirar da de baja con el nombre que quedo tras el renombre`() {
        mdns()   // crea la instancia
        creados.last().renombrar = true
        val a = red.anunciar("_avoqado-pos._tcp", "Avoqado-POS-abc123", 4321, emptyMap(), {}, {})
        red.retirar(a)
        assertEquals(listOf("Avoqado-POS-abc123 (2)"), creados.last().retirados)
    }

    @Test fun `buscar entrega encontrado y perdido, y detener deja de entregar`() {
        val eventos = mutableListOf<String>()
        val b = red.buscar("_avoqado-pos._tcp", { eventos += "+$it" }, { eventos += "-$it" })
        mdns.aparece("Avoqado-POS-zzz111")
        mdns.desaparece("Avoqado-POS-zzz111")
        red.detener(b)
        mdns.aparece("Avoqado-POS-yyy222")
        assertEquals(listOf("+Avoqado-POS-zzz111", "-Avoqado-POS-zzz111"), eventos)
        assertEquals(0, mdns.escuchas.size)
    }

    @Test fun `resolver entrega host puerto y txt, o falla si no contesta`() {
        mdns()
        creados.last().resueltos["Avoqado-POS-zzz111"] = Resuelto(cable, 5555, mapOf("did" to "zzz111", "kds" to "st_barra"))
        var ok: Resuelto? = null
        var fallas = 0
        red.resolver("_avoqado-pos._tcp", "Avoqado-POS-zzz111", { ok = it }, { fallas++ })
        red.resolver("_avoqado-pos._tcp", "Avoqado-POS-nadie", { error("no") }, { fallas++ })
        assertEquals(5555, ok?.puerto)
        assertEquals("st_barra", ok?.txt?.get("kds"))
        assertEquals(1, fallas)
    }

    @Test fun `un resolve cancelado no avisa nada`() {
        val enEspera = mutableListOf<Runnable>()
        val r = RedLocalMdns({ direccion }, { MdnsFalso(it).also { m -> creados += m } }, directo, directo, Executor { enEspera += it })
        r.anunciar("_avoqado-pos._tcp", "x", 1, emptyMap(), {}, {})
        creados.last().resueltos["Avoqado-POS-zzz111"] = Resuelto(cable, 5555, emptyMap())
        val res = r.resolver("_avoqado-pos._tcp", "Avoqado-POS-zzz111", { error("cancelado no avisa") }, { error("cancelado no avisa") })
        r.cancelar(res)
        enEspera.forEach { it.run() }
    }

    @Test fun `sin direccion del local queda pendiente y se aplica cuando aparece`() {
        direccion = null
        var registrado = false
        val eventos = mutableListOf<String>()
        red.anunciar("_avoqado-pos._tcp", "Avoqado-POS-abc123", 4321, emptyMap(), { registrado = true }, { error("no") })
        red.buscar("_avoqado-pos._tcp", { eventos += it }, {})
        assertTrue(creados.isEmpty())
        direccion = wifi
        red.revisarDireccion()
        assertTrue(registrado)
        assertEquals(1, mdns.registrados.size)
        mdns.aparece("Avoqado-POS-zzz111")
        assertEquals(listOf("Avoqado-POS-zzz111"), eventos)
    }

    @Test fun `si cambia la direccion se cierra jmDNS y se vuelve a anunciar y buscar en la nueva, sin duplicar`() {
        val eventos = mutableListOf<String>()
        red.anunciar("_avoqado-pos._tcp", "Avoqado-POS-abc123", 4321, emptyMap(), {}, {})
        red.buscar("_avoqado-pos._tcp", { eventos += it }, {})
        val viejo = mdns
        red.revisarDireccion()   // misma dirección: nada
        assertEquals(1, creados.size)
        direccion = cable
        red.revisarDireccion()
        assertTrue(viejo.cerrado)
        assertEquals(cable, mdns.direccion)
        assertEquals(1, mdns.registrados.size)
        assertEquals(1, mdns.escuchas.size)
        viejo.aparece("Avoqado-POS-viejo")   // la instancia cerrada ya no entrega nada
        mdns.aparece("Avoqado-POS-nuevo")
        assertEquals(listOf("Avoqado-POS-nuevo"), eventos)
    }

    @Test fun `al cambiar de red se avisa perdido lo que se habia encontrado, antes de lo nuevo`() {
        val eventos = mutableListOf<String>()
        red.buscar("_avoqado-pos._tcp", { eventos += "+$it" }, { eventos += "-$it" })
        mdns.aparece("A"); mdns.aparece("B"); mdns.desaparece("B")
        direccion = cable
        red.revisarDireccion()
        mdns.aparece("C")
        assertEquals(listOf("+A", "+B", "-B", "-A", "+C"), eventos)
    }

    @Test fun `una respuesta que llega de la red anterior no se da por resuelta`() {
        val enEspera = mutableListOf<Runnable>()
        val r = RedLocalMdns({ direccion }, { MdnsFalso(it).also { m -> creados += m } }, Executor { enEspera += it }, directo, directo)
        r.buscar("_avoqado-pos._tcp", {}, {})
        creados.last().resueltos["X"] = Resuelto(wifi, 1, emptyMap())
        var ok = false
        var fallo = false
        r.resolver("_avoqado-pos._tcp", "X", { ok = true }, { fallo = true })   // se resolvió en la red vieja; el aviso espera
        direccion = cable
        r.revisarDireccion()
        enEspera.forEach { it.run() }
        assertFalse(ok, "traería la IP de la red anterior")
        assertTrue(fallo)
    }

    @Test fun `un alta con una revision de red pendiente se registra UNA vez y al retirarla no queda viva`() {
        val cola = ArrayDeque<Runnable>()
        val r = RedLocalMdns({ direccion }, { MdnsFalso(it).also { m -> creados += m } }, directo, Executor { cola += it }, directo)
        r.buscar("_otro._tcp", {}, {})          // ya hay jmDNS abierto
        while (cola.isNotEmpty()) cola.removeFirst().run()
        r.revisarDireccion()                     // pendiente en la cola…
        val a = r.anunciar("_avoqado-pos._tcp", "Avoqado-POS-abc123", 4321, emptyMap(), {}, {})   // …y el alta detrás
        while (cola.isNotEmpty()) cola.removeFirst().run()
        assertEquals(1, creados.last().registrados.size, "dos altas dejarían un anuncio fantasma renombrado")
        r.retirar(a)
        while (cola.isNotEmpty()) cola.removeFirst().run()
        assertEquals(listOf("Avoqado-POS-abc123"), creados.last().retirados)
    }

    @Test fun `arrancar sin red y que vuelva por un anuncio engancha tambien la busqueda pendiente`() {
        direccion = null
        val eventos = mutableListOf<String>()
        red.buscar("_avoqado-pos._tcp", { eventos += it }, {})   // sin red: pendiente
        direccion = wifi
        red.anunciar("_avoqado-pos._tcp", "Avoqado-POS-abc123", 4321, emptyMap(), {}, {})   // abre jmDNS antes que la vigía
        assertEquals(1, mdns.escuchas.size, "la caja quedaría sin buscar a nadie para siempre")
        red.revisarDireccion()   // misma tarjeta: nada que duplicar
        assertEquals(1, mdns.escuchas.size)
        assertEquals(1, mdns.registrados.size)
        mdns.aparece("Avoqado-POS-zzz111")
        assertEquals(listOf("Avoqado-POS-zzz111"), eventos)
    }

    @Test fun `sin anuncios ni busquedas se suelta jmDNS y no se recrea al cambiar de tarjeta`() {
        val a = red.anunciar("_avoqado-pos._tcp", "a", 1, emptyMap(), {}, {})
        val primero = mdns
        red.retirar(a)
        assertTrue(primero.cerrado)
        direccion = cable
        red.revisarDireccion()
        assertEquals(1, creados.size)
        assertNull(red.direccionActual)
    }

    @Test fun `quien llama nunca espera a jmDNS aunque una baja tarde`() {
        val trabajo = Executors.newSingleThreadExecutor { Thread(it, "trabajo-prueba").apply { isDaemon = true } }
        val soltar = java.util.concurrent.CountDownLatch(1)
        val lento = object : MdnsPort by MdnsFalso(wifi) {
            override fun retirar(tipo: String, nombre: String) { soltar.await(5, TimeUnit.SECONDS) }   // jmDNS: hasta 5 s
        }
        val r = RedLocalMdns({ wifi }, { lento }, directo, trabajo, directo)
        val a = r.anunciar("_avoqado-pos._tcp", "a", 1, emptyMap(), {}, {})
        r.retirar(a)
        val inicio = System.nanoTime()
        r.anunciar("_avoqado-pos._tcp", "b", 2, emptyMap(), {}, {})
        r.buscar("_avoqado-pos._tcp", {}, {})
        val ms = (System.nanoTime() - inicio) / 1_000_000
        soltar.countDown()
        trabajo.shutdown(); trabajo.awaitTermination(5, TimeUnit.SECONDS)
        assertTrue(ms < 200, "quien llama esperó $ms ms")
    }

    @Test fun `si la red se va se cierra jmDNS y no truena`() {
        red.anunciar("_avoqado-pos._tcp", "Avoqado-POS-abc123", 4321, emptyMap(), {}, {})
        val viejo = mdns
        direccion = null
        red.revisarDireccion()
        assertTrue(viejo.cerrado)
        var fallo = false
        red.resolver("_avoqado-pos._tcp", "Avoqado-POS-zzz111", { error("no") }, { fallo = true })
        assertTrue(fallo, "sin red local un resolve falla en vez de quedarse esperando")
    }

    @Test fun `si registrar truena se avisa la falla y no queda anunciado`() {
        mdns()
        creados.last().fallaAlRegistrar = true
        var fallo: Throwable? = null
        red.anunciar("_avoqado-pos._tcp", "Avoqado-POS-abc123", 4321, emptyMap(), { error("no") }, { fallo = it })
        assertTrue(fallo != null)
        direccion = cable
        red.revisarDireccion()
        assertTrue(mdns.registrados.isEmpty(), "un anuncio fallido no se re-registra solo: LanDiscovery reintenta")
    }

    @Test fun `todos los avisos llegan por el mismo hilo`() {
        val hilos = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val avisos = Executors.newSingleThreadExecutor { Thread(it, "avisos-prueba") }
        val r = RedLocalMdns({ wifi }, { MdnsFalso(it).also { m -> creados += m } }, avisos, directo, directo)
        r.anunciar("_avoqado-pos._tcp", "a", 1, emptyMap(), { hilos += Thread.currentThread().name }, {})
        r.buscar("_avoqado-pos._tcp", { hilos += Thread.currentThread().name }, {})
        Thread { creados.last().aparece("b") }.apply { start(); join() }
        avisos.shutdown(); avisos.awaitTermination(2, TimeUnit.SECONDS)
        assertEquals(setOf("avisos-prueba"), hilos)
    }

    @Test fun `cerrar suelta jmDNS`() {
        red.anunciar("_avoqado-pos._tcp", "a", 1, emptyMap(), {}, {})
        red.cerrar()
        assertTrue(mdns.cerrado)
        assertNull(red.direccionActual)
    }

    /** Fuerza que exista la instancia de jmDNS (con una búsqueda que no estorba). */
    private fun mdns() { red.buscar("_otro._tcp", {}, {}) }

    private class MdnsFalso(val direccion: InetAddress) : MdnsPort {
        val registrados = mutableListOf<String>()
        val retirados = mutableListOf<String>()
        val escuchas = mutableListOf<Pair<(String) -> Unit, (String) -> Unit>>()
        val resueltos = mutableMapOf<String, Resuelto>()
        var renombrar = false
        var fallaAlRegistrar = false
        var cerrado = false

        override fun registrar(tipo: String, nombre: String, puerto: Int, txt: Map<String, String>): String {
            if (fallaAlRegistrar) error("puerto 5353 ocupado")
            registrados += "$nombre:$puerto:$txt"
            return if (renombrar) "$nombre (2)" else nombre
        }
        override fun retirar(tipo: String, nombre: String) { retirados += nombre }
        override fun escuchar(tipo: String, alEncontrar: (String) -> Unit, alPerder: (String) -> Unit): () -> Unit {
            val e = alEncontrar to alPerder
            escuchas += e
            return { escuchas -= e }
        }
        override fun resolver(tipo: String, nombre: String, plazoMs: Long): Resuelto? = resueltos[nombre]
        override fun cerrar() { cerrado = true; escuchas.clear() }

        fun aparece(nombre: String) = escuchas.toList().forEach { it.first(nombre) }
        fun desaparece(nombre: String) = escuchas.toList().forEach { it.second(nombre) }
    }
}
