package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regla que estos tests protegen: la pantalla del cliente sólo habla por
 * Avoqado cuando NO hay sesión (la caja está en el login y por eso no hay
 * negocio del cual mostrar marca). En cuanto hay venue, la marca es la del
 * negocio — el cliente está en la taquería, no en Avoqado.
 *
 * Es una línea de código, pero invertirla pondría el logo de Avoqado en el
 * mostrador de cada cliente que nos paga, y nadie lo notaría desde la caja:
 * el error sólo se ve en la OTRA pantalla.
 */
class CustomerDisplayIdleBrandTest {

    @Test
    fun `sin sesion la marca es Avoqado`() {
        assertTrue(idleShowsAvoqadoBrand(null, null))
    }

    @Test
    fun `campos en blanco cuentan como sin sesion`() {
        // SecureStorage devuelve "" y no null cuando la clave existe vacía.
        assertTrue(idleShowsAvoqadoBrand("", "   "))
    }

    @Test
    fun `con nombre de negocio manda el negocio`() {
        assertFalse(idleShowsAvoqadoBrand("Tacos El Güero", null))
    }

    @Test
    fun `con logo del negocio manda el negocio aunque no haya nombre`() {
        assertFalse(idleShowsAvoqadoBrand(null, "https://cdn.avoqado.io/logo.png"))
    }
}
