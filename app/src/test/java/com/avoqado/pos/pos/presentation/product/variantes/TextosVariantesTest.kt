package com.avoqado.pos.pos.presentation.product.variantes

import org.junit.Assert.assertEquals
import org.junit.Test

class TextosVariantesTest {

    @Test
    fun `variantes pone el numero en la plantilla`() {
        assertEquals("3 variantes", TextosVariantes.variantes(3))
    }

    @Test
    fun `desde pone el precio en la plantilla`() {
        assertEquals("desde \$199.00", TextosVariantes.desde("\$199.00"))
    }

    @Test
    fun `disponibles distingue uno de varios`() {
        assertEquals("1 disponible", TextosVariantes.disponibles(1))
        assertEquals("4 disponibles", TextosVariantes.disponibles(4))
    }

    @Test
    fun `accesibleFamilia junta nombre, cantidad y precio`() {
        assertEquals("Playera, 3 variantes, desde \$199.00", TextosVariantes.accesibleFamilia("Playera", 3, "desde \$199.00"))
    }

    @Test
    fun `agregarCon pone el precio en el boton`() {
        assertEquals("Agregar al carrito · \$199.00", TextosVariantes.agregarCon("\$199.00"))
    }
}
