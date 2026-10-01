package com.avoqado.pos.escritorio.teclado

import kotlin.test.Test
import kotlin.test.assertEquals

class CaracteresDelTecladoTest {
    @Test fun `el teclado cubre los 95 ASCII imprimibles`() {
        val teclas = caracteresDelTeclado()
        val faltan = (32..126).map { it.toChar() }.filter { c -> (if (c.isUpperCase()) c.lowercaseChar() else c) !in teclas }
        assertEquals(emptyList(), faltan)
    }
}
