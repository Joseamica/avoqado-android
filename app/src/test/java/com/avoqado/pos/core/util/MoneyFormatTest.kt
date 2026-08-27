package com.avoqado.pos.core.util

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * El dinero se muestra con el formato de la MONEDA del negocio, nunca con el idioma en que
 * el usuario dejó configurada la tablet.
 *
 * Encontrado en una D3 Sunmi real (2026-08-27): estaba en `es-ES` y los precios salían
 * "$20,00" con coma decimal, incluida la pantalla de confirmar el cobro. `String.format`
 * sin `Locale` toma el del sistema, así que el total cambiaba de forma solo.
 */
class MoneyFormatTest {
    private lateinit var original: Locale

    @Before fun guardarLocale() { original = Locale.getDefault() }
    @After fun restaurarLocale() { Locale.setDefault(original) }

    @Test
    fun `formato mexicano con aparato en español de España`() {
        Locale.setDefault(Locale("es", "ES"))
        assertEquals("$1,234.50", formatMoney(1234.5))
        assertEquals("$20.00", formatMoney(20.0))
    }

    @Test
    fun `formato mexicano con aparato en portugués de Brasil`() {
        Locale.setDefault(Locale("pt", "BR"))
        assertEquals("$1,234.50", formatMoney(1234.5))
    }

    @Test
    fun `formato mexicano con aparato en inglés`() {
        Locale.setDefault(Locale.US)
        assertEquals("$1,234.50", formatMoney(1234.5))
    }

    @Test
    fun `desde centavos, que es como viaja el dinero en el POS`() {
        Locale.setDefault(Locale("es", "ES"))
        assertEquals("$1,234.50", formatMoneyFromCents(123450))
        assertEquals("$0.00", formatMoneyFromCents(0))
    }

    @Test
    fun `los negativos conservan el signo delante`() {
        Locale.setDefault(Locale("es", "ES"))
        assertEquals("-$50.00", formatMoney(-50.0))
        assertEquals("-$50.00", formatMoneyFromCents(-5000))
    }

    @Test
    fun `el separador de miles es coma y el decimal es punto`() {
        Locale.setDefault(Locale("es", "ES"))
        assertEquals("$1,000.00", formatMoney(1000.0))
        assertEquals("$12,345.67", formatMoney(12345.67))
    }
}
