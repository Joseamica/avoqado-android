package com.avoqado.pos.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "1 artículos" salía en SIETE pantallas —pedidos, conteos, órdenes de compra,
 * transferencias, categorías, paquetes— porque cada una lo escribía a mano.
 *
 * Nadie lo reporta como bug, pero hace que la app se lea como sin terminar
 * justo donde alguien decide si aprueba una compra.
 */
class PluralesTest {

    @Test
    fun `uno va en singular`() {
        assertEquals("1 artículo", Plurales.articulos(1))
        assertEquals("1 persona", Plurales.personas(1))
        assertEquals("1 cuenta", Plurales.cuentas(1))
    }

    @Test
    fun `varios van en plural`() {
        assertEquals("3 artículos", Plurales.articulos(3))
        assertEquals("2 personas", Plurales.personas(2))
        assertEquals("2 cuentas", Plurales.cuentas(2))
    }

    @Test
    fun `cero va en plural, como en español`() {
        // "0 artículo" suena a traducción automática.
        assertEquals("0 artículos", Plurales.articulos(0))
        assertEquals("0 personas", Plurales.personas(0))
    }

    @Test
    fun `contar acepta un plural irregular`() {
        // El caso que el helper no puede adivinar: no todo plural es añadir "s".
        assertEquals("1 mes", Plurales.contar(1, "mes", "meses"))
        assertEquals("3 meses", Plurales.contar(3, "mes", "meses"))
        assertEquals("2 mesas", Plurales.contar(2, "mesa"))
    }
}
