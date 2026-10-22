package com.avoqado.pos.payment

import com.avoqado.pos.payment.presentation.tipRows
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hasta seis porcentajes de propina caben en las dos pantallas (el servidor y el editor del panel topan en 6): hasta
 * cuatro en una fila, cinco o seis en dos filas casi parejas, y lo que pase de seis no se pinta.
 */
class TipRowsTest {

    @Test
    fun `tres porcentajes son una sola fila`() {
        assertEquals(listOf(listOf("a", "b", "c")), tipRows(listOf("a", "b", "c")))
    }

    @Test
    fun `cuatro porcentajes siguen en una sola fila`() {
        assertEquals(listOf(listOf("a", "b", "c", "d")), tipRows(listOf("a", "b", "c", "d")))
    }

    @Test
    fun `cinco porcentajes van tres arriba y dos abajo`() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e")), tipRows(listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun `seis porcentajes van tres y tres`() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e", "f")), tipRows(listOf("a", "b", "c", "d", "e", "f")))
    }

    @Test
    fun `ocho porcentajes se topan en seis`() {
        assertEquals(
            listOf(listOf(1, 2, 3), listOf(4, 5, 6)),
            tipRows((1..8).toList()),
        )
    }

    @Test
    fun `sin porcentajes no hay filas`() {
        assertEquals(emptyList<List<Int>>(), tipRows(emptyList<Int>()))
    }
}
