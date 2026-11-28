package com.avoqado.pos.tables.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EtiquetaDeMesaTest {
    @Test
    fun `un numero o un codigo lleva Mesa`() {
        assertEquals("Mesa 5", etiquetaDeMesa("5"))
        assertEquals("Mesa A1", etiquetaDeMesa("A1"))
        assertEquals("Mesa 12B", etiquetaDeMesa(" 12B "))
    }

    @Test
    fun `un nombre propio va tal cual (D4)`() {
        assertEquals("Bar 1", etiquetaDeMesa("Bar 1"))
        assertEquals("Terraza 3", etiquetaDeMesa("Terraza 3"))
        assertEquals("Mesada 2", etiquetaDeMesa("Mesada 2"))
    }

    @Test
    fun `nunca Mesa Mesa 1`() {
        assertEquals("Mesa 1", etiquetaDeMesa("Mesa 1"))
        assertEquals("Mesa 3", etiquetaDeMesa("mesa-3"))
        assertEquals("Mesa 2", etiquetaDeMesa("Table 2"))
        assertEquals("Mesa", etiquetaDeMesa("Mesa"))
    }
}
