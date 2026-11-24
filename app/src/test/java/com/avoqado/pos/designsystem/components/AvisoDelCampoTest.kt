package com.avoqado.pos.designsystem.components

import com.avoqado.pos.core.util.digitosDelCampo
import com.avoqado.pos.core.util.filtrarTecleoDelCampoConBandera
import com.avoqado.pos.core.util.telefonoCompleto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hz:nube0000:na519219: con la bandera de México, pegar «+34 612 345 678» salía a «+5234612345678». La bandera sólo la cambia
 * la persona; el campo dice por qué no pasa. (Que cambiara sola se probó y se quitó: Codex r1 y r2 del 24-nov.)
 */
class AvisoDelCampoTest {

    private val mexico = Countries.byIso("MX")
    private val espana = Countries.byIso("ES")

    /** Lo que llega a quien usa el campo con [escrito] y la bandera [pais]. */
    private fun manda(escrito: String, pais: Country): String? {
        val digitos = digitosDelCampo(filtrarTecleoDelCampoConBandera(escrito), pais.dialCode)
        return if (telefonoCompleto(digitos, pais.dialCode)) "+${pais.dialCode}$digitos" else null
    }

    @Test fun `P1 otra lada con otra bandera no se manda y el aviso dice cual elegir`() {
        assertNull(manda("+34 612 345 678", mexico))
        assertEquals(
            AvisoDelCampo("El número trae la lada +34 (España): elige ese país en la bandera o quítala", true),
            avisoDelCampo("+34 612 345 678", mexico),
        )
        // Al elegir España, el mismo texto pasa.
        assertEquals("+34612345678", manda("+34 612 345 678", espana))
        assertEquals(
            AvisoDelCampo("El número trae la lada +52 (México): elige ese país en la bandera o quítala", true),
            avisoDelCampo("+52 55 9187 3922", espana),
        )
        assertEquals(
            AvisoDelCampo("El número trae la lada +1 (Estados Unidos o Canadá): elige ese país en la bandera o quítala", true),
            avisoDelCampo("+1 202 555 0123", mexico),
        )
        assertEquals(
            AvisoDelCampo("El número trae la lada +351 (Portugal): elige ese país en la bandera o quítala", true),
            avisoDelCampo("+351 912 345 678", mexico),
        )
    }

    @Test fun `P1 Codex r1 y r2 lo que no se puede atribuir no se manda`() {
        assertNull(manda("+34", mexico))                    // la lada sola (antes Reservas guardaba sin teléfono)
        assertNull(manda("+52", mexico))
        assertNull(manda("+52 1", mexico))
        assertNull(manda(".+7 912 345 67 89", espana))      // un «+» fuera de lugar
        assertNull(manda("+34 612+345678", mexico))         // dos «+»
        assertNull(manda("+34 612+345678", espana))
        assertNull(manda("+1 1 202 555 0123", mexico))      // r2 #5
        assertNull(manda("+1 1 202 555 0123", Countries.byIso("US")))
        assertNull(manda("+7 912 345 67 89", espana))       // lada fuera de la lista
    }

    @Test fun `P1 lo de siempre sigue pasando`() {
        assertEquals("+525591873922", manda("55 9187 3922", mexico))
        assertEquals("+525591873922", manda("+52 55 9187 3922", mexico))
        assertEquals("+525591873922", manda("+52 1 55 9187 3922", mexico))
        assertEquals("+525591873922", manda("(+52) 55 9187-3922", mexico))
        assertEquals("+12025550123", manda("+1 202 555 0123", Countries.byIso("US")))
        assertEquals("+14165550123", manda("+1 416 555 0123", Countries.byIso("CA")))
        assertEquals("+34612345678", manda("612 345 678", espana))
    }

    @Test fun `P1 el aviso mientras se escribe la lada es guia y no error`() {
        assertEquals(AvisoDelCampo("Escribe la lada y el número", false), avisoDelCampo("+", mexico))
        assertEquals(AvisoDelCampo("Sigue escribiendo la lada", false), avisoDelCampo("+3", mexico))
        assertEquals(AvisoDelCampo("Sigue escribiendo la lada", false), avisoDelCampo("+5", mexico))
        assertEquals(AvisoDelCampo("Sigue escribiendo la lada", false), avisoDelCampo("+35", mexico))
        assertEquals(AvisoDelCampo("Escribe el número después de la lada", false), avisoDelCampo("+52", mexico))
        assertEquals(AvisoDelCampo("Escribe el número después de la lada", false), avisoDelCampo("+52 1", mexico))
    }

    @Test fun `P1 los demas avisos`() {
        assertEquals(
            AvisoDelCampo("La lada de ese número no está en la lista de países", true),
            avisoDelCampo("+7 912 345 67 89", mexico),
        )
        assertEquals(
            AvisoDelCampo("El «+» sólo va al principio, antes de la lada", true),
            avisoDelCampo(".+7 912 345 67 89", espana),
        )
        assertEquals(AvisoDelCampo("Falta 1 dígito: un número de México lleva 10", false), avisoDelCampo("559187392", mexico))
        assertEquals(AvisoDelCampo("Sobra 1 dígito: un número de México lleva 10", true), avisoDelCampo("55918739221", mexico))
        assertEquals(AvisoDelCampo("Un número de México no empieza con 1", true), avisoDelCampo("1591873922", mexico))
        assertNull(avisoDelCampo("55 9187 3922", mexico))
        assertNull(avisoDelCampo("", mexico))
    }

    @Test fun `P1 ninguna lada de la lista es el principio de otra`() {
        // Si una lo fuera, «+35» diría «Sigue escribiendo» y «+3» podría nombrar el país equivocado.
        val ladas = Countries.all.map { it.dialCode }.distinct()
        for (a in ladas) for (b in ladas) {
            if (a != b) assertFalse("+$a es el principio de +$b", b.startsWith(a))
        }
        assertTrue(ladas.contains("52"))
    }
}
