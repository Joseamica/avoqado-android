package com.avoqado.pos.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * hz:nube0000:na519219: con la bandera de México, pegar «+34 612 345 678» no cambiaba la bandera y el número salía a
 * «+5234612345678». Ahora la lada escrita con «+» elige el país sola.
 */
class PaisPorLadaEscritaTest {

    private val mexico = Countries.byIso("MX")

    @Test fun `P1 pegar otra lada cambia la bandera`() {
        assertEquals("ES", Countries.porLadaEscrita("+34 612 345 678", mexico)?.isoCode)
        assertEquals("CR", Countries.porLadaEscrita("+506 8888 7777", mexico)?.isoCode)
        assertEquals("PT", Countries.porLadaEscrita("+351 912 345 678", mexico)?.isoCode)
        assertEquals("US", Countries.porLadaEscrita("+1 202 555 0123", mexico)?.isoCode)
    }

    @Test fun `P1 la lada del pais elegido no cambia nada`() {
        assertNull(Countries.porLadaEscrita("+52 55 9187 3922", mexico))
        // Canadá y EE. UU. comparten el +1: con Canadá elegido se queda Canadá.
        assertNull(Countries.porLadaEscrita("+1 416 555 0123", Countries.byIso("CA")))
    }

    @Test fun `P2 sin mas o con una lada que no esta en la lista no cambia`() {
        assertNull(Countries.porLadaEscrita("34 612 345 678", mexico))
        assertNull(Countries.porLadaEscrita("+7 912 345 67 89", mexico))
        assertNull(Countries.porLadaEscrita("+", mexico))
        // A media lada: «+5» todavía no es ningún país; «+59» tampoco (Bolivia es +591).
        assertNull(Countries.porLadaEscrita("+5", mexico))
        assertNull(Countries.porLadaEscrita("+59", mexico))
    }

    @Test fun `P1 ninguna lada de la lista es el principio de otra`() {
        // Si una lo fuera, al teclear la larga la bandera saltaría antes a la corta.
        val ladas = Countries.all.map { it.dialCode }.distinct()
        for (a in ladas) for (b in ladas) {
            if (a != b) assertFalse("+$a es el principio de +$b", b.startsWith(a))
        }
    }

    // --- El campo entero: teclazos, pegados, borrar y el aviso (Codex r1 del 24-nov) ---

    private fun escribir(vararg textos: String, desde: Country = mexico): EstadoDelCampo {
        var estado = EstadoDelCampo("", desde, null)
        for (t in textos) estado = alEscribirEnElCampo(t, estado)
        return estado
    }

    private fun lento(texto: String, desde: Country = mexico): EstadoDelCampo {
        var estado = EstadoDelCampo("", desde, null)
        for (i in 1..texto.length) estado = alEscribirEnElCampo(estado.escrito + texto[i - 1], estado)
        return estado
    }

    @Test fun `P1 pegar otra lada deja la bandera con la lada y el texto con el numero`() {
        val e = escribir("+34 612 345 678")
        assertEquals("ES", e.pais.isoCode)
        assertEquals("612345678", e.escrito)
        assertEquals("612345678", com.avoqado.pos.core.util.digitosDelCampo(e.escrito, e.pais.dialCode))
    }

    @Test fun `P1 teclear la lada de uno en uno llega a lo mismo`() {
        val e = lento("+34 612 345 678")
        assertEquals("ES", e.pais.isoCode)
        assertEquals("612345678", com.avoqado.pos.core.util.digitosDelCampo(e.escrito, e.pais.dialCode))
        val us = lento("+1 202 555 0123")
        assertEquals("US", us.pais.isoCode)
        assertEquals("2025550123", com.avoqado.pos.core.util.digitosDelCampo(us.escrito, us.pais.dialCode))
    }

    @Test fun `P1 Codex r1 borrar todo regresa a la bandera de antes`() {
        // Pegar +34, borrar todo y teclear un celular de México: antes de este arreglo se quedaba en España (+345591873922).
        val e = escribir("+34 612 345 678", "", "5591873922")
        assertEquals("MX", e.pais.isoCode)
        assertEquals("5591873922", com.avoqado.pos.core.util.digitosDelCampo(e.escrito, e.pais.dialCode))
    }

    @Test fun `P1 el espacio despues de la lada no regresa la bandera`() {
        assertEquals("ES", escribir("+34", " ", " 6").pais.isoCode)
    }

    @Test fun `P1 elegir el pais a mano no se deshace al borrar`() {
        val espana = Countries.byIso("ES")
        val e = escribir("612345678", "", desde = espana)
        assertEquals("ES", e.pais.isoCode)
    }

    @Test fun `P1 la lada del pais elegido se queda escrita como antes`() {
        val e = escribir("+52 55 9187 3922")
        assertEquals("MX", e.pais.isoCode)
        assertEquals("+52 55 9187 3922", e.escrito)
    }

    @Test fun `P1 Codex r1 el aviso dice que pasa con la lada`() {
        assertEquals(AvisoDelCampo("Sigue escribiendo la lada", false), avisoDelCampo("+3", mexico))
        assertEquals(AvisoDelCampo("Escribe la lada y el número", false), avisoDelCampo("+", mexico))
        assertEquals(AvisoDelCampo("Escribe el número después de la lada", false), avisoDelCampo("+52 1", mexico))
        assertEquals(
            AvisoDelCampo("La lada de ese número no está en la lista de países", true),
            avisoDelCampo("+7 912 345 67 89", mexico),
        )
        assertEquals(
            AvisoDelCampo("El «+» sólo va al principio, antes de la lada", true),
            avisoDelCampo(".+7 912 345 67 89", Countries.byIso("ES")),
        )
        // Lo escrito con +52 y luego España elegida a mano.
        assertEquals(
            AvisoDelCampo("El número trae la lada +52 (México): elige ese país en la bandera o quítala", true),
            avisoDelCampo("+52 55 9187 3922", Countries.byIso("ES")),
        )
        assertEquals(AvisoDelCampo("Falta 1 dígito: un número de México lleva 10", false), avisoDelCampo("559187392", mexico))
        assertEquals(AvisoDelCampo("Sobra 1 dígito: un número de México lleva 10", true), avisoDelCampo("55918739221", mexico))
        assertNull(avisoDelCampo("55 9187 3922", mexico))
        assertNull(avisoDelCampo("", mexico))
    }
}
