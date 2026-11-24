package com.avoqado.pos.customerdisplay

import com.avoqado.pos.designsystem.components.Countries
import com.avoqado.pos.designsystem.components.Country
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El teclado del recibo por WhatsApp de la pantalla del cliente (hz:nube0000:nae727f6): antes «Enviar» se prendía con 8 a 15
 * dígitos aunque el país fuera México, y con 8 o 9 el recibo salía a «+5212345678», un número que no existe o de otra persona.
 */
class WhatsAppDelClienteTest {
    private val mexico = Countries.pinned.first { it.isoCode == "MX" }
    private val eeuu = Countries.pinned.first { it.isoCode == "US" }
    private val espana = Countries.pinned.first { it.isoCode == "ES" }
    private val bolivia = Country("BO", "591", "", "Bolivia")

    @Test
    fun `Mexico con 8 o 9 digitos no se manda y dice cuantos faltan`() {
        assertFalse(whatsAppDelClienteCompleto("12345678", mexico))
        assertFalse(whatsAppDelClienteCompleto("559187392", mexico))
        assertEquals("Faltan 2 dígitos: un número de México lleva 10", avisoDelWhatsAppDelCliente("55918739", mexico))
        assertEquals("Falta 1 dígito: un número de México lleva 10", avisoDelWhatsAppDelCliente("559187392", mexico))
    }

    @Test
    fun `Mexico con 10 digitos se manda con su lada`() {
        assertTrue(whatsAppDelClienteCompleto("5591873922", mexico))
        assertNull(avisoDelWhatsAppDelCliente("5591873922", mexico))
        assertEquals("+525591873922", whatsAppDelCliente("5591873922", mexico))
    }

    @Test
    fun `Mexico con 11 digitos dice que sobra`() {
        assertFalse(whatsAppDelClienteCompleto("55918739221", mexico))
        assertEquals("Sobra 1 dígito: un número de México lleva 10", avisoDelWhatsAppDelCliente("55918739221", mexico))
    }

    @Test
    fun `Mexico y Estados Unidos no empiezan con 0 ni con 1`() {
        assertFalse(whatsAppDelClienteCompleto("1559187392", mexico))
        assertEquals("Un número de México no empieza con 1", avisoDelWhatsAppDelCliente("1", mexico))
        assertEquals("Un número de Estados Unidos no empieza con 0", avisoDelWhatsAppDelCliente("0202555012", eeuu))
        assertTrue(whatsAppDelClienteCompleto("2025550123", eeuu))
    }

    @Test
    fun `otra lada usa su rango y el total no pasa de 15`() {
        // España: de 9 (lada 2 + 9 = 11) a 13 (15 - 2).
        assertFalse(whatsAppDelClienteCompleto("61234567", espana))
        assertTrue(whatsAppDelClienteCompleto("612345678", espana))
        assertEquals("Falta 1 dígito: un número de España lleva de 9 a 13", avisoDelWhatsAppDelCliente("61234567", espana))
        // Bolivia (+591): de 8 a 12.
        assertTrue(whatsAppDelClienteCompleto("71234567", bolivia))
        assertFalse(whatsAppDelClienteCompleto("7123456789012", bolivia))
    }

    @Test
    fun `vacio no se manda y no regana`() {
        assertFalse(whatsAppDelClienteCompleto("", mexico))
        assertNull(avisoDelWhatsAppDelCliente("", mexico))
        assertNull(whatsAppDelCliente("", mexico))
    }

    @Test
    fun `el teclado no deja pasar del tope del pais`() {
        assertEquals(10, digitosMaximosDelWhatsApp(mexico))
        assertEquals(13, digitosMaximosDelWhatsApp(espana))
    }

    @Test
    fun `cambiar de pais con un numero largo no lo manda`() {
        // Tecleó 12 dígitos con España y luego eligió México: «Enviar» se apaga y dice que sobran.
        assertNull(whatsAppDelCliente("612345678901", mexico))
        assertEquals("Sobran 2 dígitos: un número de México lleva 10", avisoDelWhatsAppDelCliente("612345678901", mexico))
    }
}
