package com.avoqado.pos.pos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D12 — el «Ajuste de precio» del POS sólo BAJA el precio (un ajuste hacia arriba nace del producto de precio libre).
 * Gemela de iOS: mismo texto, misma regla.
 */
class AjusteDePrecioTest {

    @Test
    fun `bajar el precio se permite`() {
        assertTrue(ajusteDePrecioPermitido(nuevoCents = 8000, originalCents = 10000))
    }

    @Test
    fun `dejarlo igual se permite`() {
        assertTrue(ajusteDePrecioPermitido(nuevoCents = 10000, originalCents = 10000))
    }

    @Test
    fun `subir el precio no se permite`() {
        assertFalse(ajusteDePrecioPermitido(nuevoCents = 12000, originalCents = 10000))
    }

    @Test
    fun `un precio en cero no se permite`() {
        assertFalse(ajusteDePrecioPermitido(nuevoCents = 0, originalCents = 10000))
    }

    @Test
    fun `un ajuste más bajo se guarda tal cual`() {
        assertEquals(8000, ajusteAGuardar(nuevoCents = 8000, originalCents = 10000))
    }

    @Test
    fun `confirmar el mismo precio no deja ajuste`() {
        // Igual al original no descuenta nada: la línea no debe quedar marcada como «ajustada» (por ejemplo, para los vales).
        assertNull(ajusteAGuardar(nuevoCents = 10000, originalCents = 10000))
    }

    @Test
    fun `el texto es idéntico en las dos apps`() {
        assertEquals("Para cobrar más, usa un producto de precio libre.", MENSAJE_SUBIR_PRECIO)
    }
}
