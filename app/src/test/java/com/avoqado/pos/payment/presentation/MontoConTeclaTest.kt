package com.avoqado.pos.payment.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Monto recibido» (Efectivo › Personalizado) con teclado FÍSICO. Founder, 9-oct, POS de Windows:
 * ya cobrando, el monto sólo entraba con el mouse sobre el teclado de la pantalla.
 *
 * Mismo criterio que el teclado de la pantalla: cada dígito entra como centavos ($ 1 → 5 → 0 → 0 → 0
 * = $150.00), retroceso quita el último.
 */
class MontoConTeclaTest {

    @Test
    fun `P1 los digitos entran como en el teclado de la pantalla`() {
        val cents = "15000".fold(0) { acc, c -> montoConTecla(acc, caracter = c, borrar = false)!! }
        assertEquals(15_000, cents)
    }

    @Test
    fun `P1 retroceso quita el ultimo digito y en cero no truena`() {
        assertEquals(1_500, montoConTecla(15_000, caracter = null, borrar = true))
        assertEquals(0, montoConTecla(0, caracter = null, borrar = true))
    }

    @Test
    fun `P2 el tope de la pantalla se respeta`() {
        assertEquals(10_000_000, montoConTecla(10_000_000, caracter = '9', borrar = false))
    }

    @Test
    fun `P2 letras y punto no son de esta pantalla`() {
        assertNull(montoConTecla(500, caracter = 'a', borrar = false))
        assertNull(montoConTecla(500, caracter = '.', borrar = false))
        assertNull(montoConTecla(500, caracter = null, borrar = false))
    }
}
