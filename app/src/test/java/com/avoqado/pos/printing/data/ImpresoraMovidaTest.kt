package com.avoqado.pos.printing.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La parte PURA de «la impresora que se encuentra sola»: qué direcciones barrer, qué cuenta
 * como ticketera y cuándo se puede adoptar una dirección nueva sin preguntarle a nadie.
 */
class ImpresoraMovidaTest {

    // MARK: - Qué direcciones se barren

    @Test
    fun `una red 24 barre las 253 vecinas sin la propia ni la de red ni la de difusion`() {
        val hosts = ImpresoraMovida.hostsDeLaSubred("192.168.1.64", 24)
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.64" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
        assertTrue("192.168.1.1" in hosts)
        assertTrue("192.168.1.254" in hosts)
    }

    @Test
    fun `una red grande sólo barre el bloque 24 propio para no tardar minutos`() {
        val hosts = ImpresoraMovida.hostsDeLaSubred("10.0.7.20", 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.0.7.") })
    }

    @Test
    fun `una red chica barre sólo su rango`() {
        // 192.168.1.64/28 = .64-.79 → hosts .65-.78 sin la propia (.70)
        val hosts = ImpresoraMovida.hostsDeLaSubred("192.168.1.70", 28)
        assertEquals((65..78).filter { it != 70 }.map { "192.168.1.$it" }, hosts)
    }

    @Test
    fun `una direccion invalida o un punto a punto no barren nada`() {
        assertEquals(emptyList<String>(), ImpresoraMovida.hostsDeLaSubred("no-es-ip", 24))
        assertEquals(emptyList<String>(), ImpresoraMovida.hostsDeLaSubred("192.168.1.300", 24))
        assertEquals(emptyList<String>(), ImpresoraMovida.hostsDeLaSubred("192.168.1.10", 31))
        assertEquals(emptyList<String>(), ImpresoraMovida.hostsDeLaSubred("192.168.1.10", 0))
    }

    // MARK: - Qué cuenta como ticketera (respuesta a DLE EOT 1)

    @Test
    fun `el estado de una ticketera tiene los bits fijos 1 y 4 prendidos y 0 y 7 apagados`() {
        assertTrue(ImpresoraMovida.esEstadoDeTicketera(0x12)) // en línea, cajón cerrado
        assertTrue(ImpresoraMovida.esEstadoDeTicketera(0x16)) // cajón abierto
        assertTrue(ImpresoraMovida.esEstadoDeTicketera(0x1A)) // fuera de línea
        assertFalse(ImpresoraMovida.esEstadoDeTicketera(-1)) // no contestó
        assertFalse(ImpresoraMovida.esEstadoDeTicketera(0x00))
        assertFalse(ImpresoraMovida.esEstadoDeTicketera(0x48)) // una 'H' de un banner HTTP/PJL
        assertFalse(ImpresoraMovida.esEstadoDeTicketera(0x93))
    }

    // MARK: - Cuándo se adopta una dirección nueva

    @Test
    fun `una sola ticketera libre se adopta`() {
        val eleccion = ImpresoraMovida.elegir(listOf("192.168.1.71"), ocupadas = emptySet())
        assertEquals(Eleccion.Una("192.168.1.71"), eleccion)
    }

    @Test
    fun `las direcciones de las OTRAS impresoras conocidas no cuentan`() {
        // Barra sigue en .70 y contesta: la que se movió (Cocina) tiene que ser la .71.
        val eleccion = ImpresoraMovida.elegir(
            listOf("192.168.1.70", "192.168.1.71"),
            ocupadas = setOf("192.168.1.70"),
        )
        assertEquals(Eleccion.Una("192.168.1.71"), eleccion)
    }

    @Test
    fun `P1 dos ticketeras libres NO se adivinan`() {
        // Mandar la comanda de Cocina a la impresora de Barra es peor que avisar.
        val eleccion = ImpresoraMovida.elegir(listOf("192.168.1.71", "192.168.1.70"), ocupadas = emptySet())
        assertEquals(Eleccion.Varias(listOf("192.168.1.70", "192.168.1.71")), eleccion)
    }

    @Test
    fun `sin ticketeras libres no hay eleccion`() {
        assertEquals(Eleccion.Ninguna, ImpresoraMovida.elegir(emptyList(), ocupadas = emptySet()))
        assertEquals(Eleccion.Ninguna, ImpresoraMovida.elegir(listOf("192.168.1.70"), ocupadas = setOf("192.168.1.70")))
    }
}

class DireccionVigenteTest {

    private val mudanzas = mapOf("cocina" to Mudanza(original = "192.168.1.64", nueva = "192.168.1.67"))

    @Test
    fun `una impresora que se mudo se busca en la direccion nueva`() {
        assertEquals("192.168.1.67", ImpresoraMovida.direccionVigente(mudanzas, "cocina", "192.168.1.64"))
    }

    @Test
    fun `si alguien corrigio la direccion a mano gana lo nuevo`() {
        assertEquals("192.168.1.90", ImpresoraMovida.direccionVigente(mudanzas, "cocina", "192.168.1.90"))
    }

    @Test
    fun `una impresora sin mudanza se queda como esta`() {
        assertEquals("192.168.1.70", ImpresoraMovida.direccionVigente(mudanzas, "barra", "192.168.1.70"))
    }

    @Test
    fun `los avisos dicen la direccion y no traen texto del sistema en ingles`() {
        val avisos = listOf(
            ImpresoraMovida.avisoDireccionPropia("192.168.1.64"),
            ImpresoraMovida.avisoVarias("192.168.1.64", 2),
            ImpresoraMovida.avisoNinguna("192.168.1.64"),
        )
        avisos.forEach {
            assertTrue(it.contains("192.168.1.64"))
            assertFalse(it.contains("failed", ignoreCase = true))
            assertFalse(it.contains("ECONNREFUSED"))
        }
    }
}

class IdentidadDeImpresoraTest {

    // El JSONP real de la ticketera de Testarudo, medido por el fork el 2-oct-2026.
    private val w5500Testarudo = """setCallback({"m0":"02","m1":"10","m2":"7B","m3":"1A","m4":"76","m5":"FC","i0":"192","i1":"168","i2":"1","i3":"67","s0":"255","s1":"255","s2":"255","s3":"0","g0":"192","g1":"168","g2":"1","g3":"254","dh":"1","dht":"30","ds":"5","cut":"1","bz":"1","cutb":"0"});"""

    @Test
    fun `lee la MAC de la ticketera real de Testarudo`() {
        assertEquals("mac:02107B1A76FC", IdentidadDeImpresora.macDeW5500(w5500Testarudo))
    }

    @Test
    fun `un JSONP incompleto o ajeno no da identidad`() {
        assertEquals(null, IdentidadDeImpresora.macDeW5500("""setCallback({"m0":"02","m1":"10"})"""))
        assertEquals(null, IdentidadDeImpresora.macDeW5500("<html>Printer Setting</html>"))
    }

    @Test
    fun `lee una MAC escrita en una pagina con dos puntos o guiones`() {
        assertEquals("mac:02107B1A76FC", IdentidadDeImpresora.macDeHtml("<td>MAC</td><td>02:10:7b:1a:76:fc</td>"))
        assertEquals("mac:02107B1A76FC", IdentidadDeImpresora.macDeHtml("MAC Address: 02-10-7B-1A-76-FC"))
    }

    @Test
    fun `una MAC vacia o mezclada no cuenta`() {
        assertEquals(null, IdentidadDeImpresora.macDeHtml("00:00:00:00:00:00"))
        assertEquals(null, IdentidadDeImpresora.macDeHtml("FF:FF:FF:FF:FF:FF"))
        assertEquals(null, IdentidadDeImpresora.macDeHtml("02:10-7B:1A-76:FC"))
        assertEquals(null, IdentidadDeImpresora.macDeHtml("sin mac aqui"))
    }

    @Test
    fun `identidad por nombre anunciado`() {
        val id = IdentidadDeImpresora.deNombre("EPSON TM-m30III")
        assertEquals("mdns:EPSON TM-m30III", id)
        assertEquals("EPSON TM-m30III", IdentidadDeImpresora.nombreAnunciado(id))
        assertFalse(IdentidadDeImpresora.esMac(id))
        assertTrue(IdentidadDeImpresora.esMac("mac:02107B1A76FC"))
        assertEquals(null, IdentidadDeImpresora.nombreAnunciado("mac:02107B1A76FC"))
    }
}
