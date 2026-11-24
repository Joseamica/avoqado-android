package com.avoqado.pos.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hz:nube0000:na519219 (20-nov-2026): el campo con bandera del POS (recibo por WhatsApp al cobrar y desde Transacciones,
 * alta rápida de cliente en Reservas, invitado en Lista de espera) dejaba mandar un número incompleto o de otra lada:
 * con México, «55918739221» salía a «+5255918739221» y «+34 612 345 678» pegado a «+5234612345678». Nadie se enteraba.
 */
class TelefonoCompletoTest {

    @Test fun `P1 Mexico y Norteamerica llevan exactamente 10 digitos`() {
        assertTrue(telefonoCompleto("5591873922", "52"))
        assertFalse(telefonoCompleto("559187392", "52"))      // 9
        assertFalse(telefonoCompleto("55918739221", "52"))    // 11
        assertTrue(telefonoCompleto("2025550123", "1"))
        assertFalse(telefonoCompleto("202555012", "1"))
        assertFalse(telefonoCompleto("", "52"))
    }

    @Test fun `P1 lo que el campo con bandera entrega del caso de la tarjeta ya no pasa`() {
        assertFalse(telefonoCompleto(digitosDelCampo("55918739221", "52"), "52"))
        assertFalse(telefonoCompleto(digitosDelCampo("559187392", "52"), "52"))
        // Pegar otra lada con México elegido: antes daba «34612345678» y salía a «+5234612345678».
        assertFalse(telefonoCompleto(digitosDelCampo("+34 612 345 678", "52"), "52"))
        assertTrue(telefonoCompleto(digitosDelCampo("+52 1 55 9187 3922", "52"), "52"))
        assertTrue(telefonoCompleto(digitosDelCampo("+34 612 345 678", "34"), "34"))
    }

    @Test fun `P1 un celular de Mexico o de Norteamerica no empieza con 0 ni 1`() {
        assertFalse(telefonoCompleto("0591873922", "52"))
        assertFalse(telefonoCompleto("1591873922", "52"))
        assertFalse(telefonoCompleto("1025550123", "1"))
        assertEquals("Un número de México no empieza con 1", avisoDelTelefono("1591873922", "52", "México"))
    }

    @Test fun `P1 otras ladas usan el rango del kiosco`() {
        // España (+34): de 9 a 13 (lada + número suman al menos 11 y a lo más 15, como E.164).
        assertEquals(9..13, digitosNacionalesValidos("34"))
        assertTrue(telefonoCompleto("612345678", "34"))
        assertFalse(telefonoCompleto("61234567", "34"))
        // Costa Rica (+506): de 8 a 12.
        assertEquals(8..12, digitosNacionalesValidos("506"))
        assertEquals(10..10, digitosNacionalesValidos("52"))
        assertEquals(10..10, digitosNacionalesValidos("1"))
    }

    @Test fun `P1 otra lada escrita con mas queda marcada y no se vuelve de este pais`() {
        assertEquals("+34612345678", digitosDelCampo("+34 612 345 678", "52"))
        assertEquals("+79123456789", digitosDelCampo("+7 912 345 67 89", "34"))   // en ES el rango la dejaba pasar
        assertFalse(telefonoCompleto("+79123456789", "34"))
        assertEquals("Revisa la lada del número", avisoDelTelefono("+79123456789", "34", "España"))
    }

    @Test fun `P2 con la lada del pais o sin lada entrega lo mismo que antes`() {
        assertEquals("5591873922", digitosDelCampo("+52 55 9187 3922", "52"))
        assertEquals("5591873922", digitosDelCampo("55 9187 3922", "52"))
        assertEquals("", digitosDelCampo("", "52"))
    }

    @Test fun `P1 el aviso dice cuantos faltan o sobran`() {
        assertNull(avisoDelTelefono("", "52", "México"))
        assertNull(avisoDelTelefono("5591873922", "52", "México"))
        assertEquals("Falta 1 dígito: un número de México lleva 10", avisoDelTelefono("559187392", "52", "México"))
        assertEquals("Faltan 9 dígitos: un número de México lleva 10", avisoDelTelefono("5", "52", "México"))
        assertEquals("Sobra 1 dígito: un número de México lleva 10", avisoDelTelefono("55918739221", "52", "México"))
        assertEquals("Sobran 2 dígitos: un número de México lleva 10", avisoDelTelefono("559187392211", "52", "México"))
        assertEquals("Faltan 2 dígitos: un número de España lleva de 9 a 13", avisoDelTelefono("6123456", "34", "España"))
    }

    @Test fun `P1 Codex r1 lo que no se puede atribuir a este pais no pasa`() {
        // La lada sola: antes llegaba vacío y Reservas guardaba sin teléfono con «+52» a la vista.
        assertEquals("+", digitosDelCampo("+", "52"))
        assertEquals("+", digitosDelCampo("(+", "52"))
        assertEquals("+52", digitosDelCampo("+52", "52"))
        assertEquals("+521", digitosDelCampo("+52 1", "52"))
        assertFalse(telefonoCompleto(digitosDelCampo("+52", "52"), "52"))
        // Un «+» que no va al principio: antes «.+7 912 345 67 89» con España pasaba como «+3479123456789».
        assertEquals("+79123456789", digitosDelCampo(".+7 912 345 67 89", "34"))
        assertFalse(telefonoCompleto(digitosDelCampo(".+7 912 345 67 89", "34"), "34"))
        assertFalse(masBienPuesto(".+7 912"))
        assertTrue(masBienPuesto("(+52) 55"))
    }
}
