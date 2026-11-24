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
}
