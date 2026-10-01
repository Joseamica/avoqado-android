package com.avoqado.pos.escritorio.tactil

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToquesTactilesTest {
    private val identidad = TraductorDeToques { x, y -> Offset(x.toFloat(), y.toFloat()) }
    private fun c(id: Int, x: Int, y: Int, f: FaseDeToque, t: Long = 0) = ContactoDeWindows(id, x, y, f, t)

    @Test fun `un dedo baja, se mueve y sube`() {
        val baja = identidad.traducir(c(1, 10, 10, FaseDeToque.BAJA))!!
        assertEquals(TipoDeEvento.PRESIONA, baja.tipo)
        assertEquals(listOf(Puntero(1, Offset(10f, 10f), true)), baja.punteros)
        val mueve = identidad.traducir(c(1, 10, 40, FaseDeToque.MUEVE))!!
        assertEquals(TipoDeEvento.MUEVE, mueve.tipo)
        assertEquals(Offset(10f, 40f), mueve.punteros.single().posicion)
        val sube = identidad.traducir(c(1, 10, 40, FaseDeToque.SUBE))!!
        assertEquals(TipoDeEvento.SUELTA, sube.tipo)
        assertFalse(sube.cancela)
        assertFalse(sube.punteros.single().presionado)
        assertFalse(identidad.hayDedos)
    }

    @Test fun `lo que no empezó con BAJA no se manda, ni un movimiento sin cambio`() {
        assertNull(identidad.traducir(c(9, 1, 1, FaseDeToque.MUEVE)))
        assertNull(identidad.traducir(c(9, 1, 1, FaseDeToque.SUBE)))
        assertNull(identidad.traducir(c(9, 1, 1, FaseDeToque.CANCELA)))
        identidad.traducir(c(2, 5, 5, FaseDeToque.BAJA))
        assertNull(identidad.traducir(c(2, 5, 5, FaseDeToque.MUEVE)))
    }

    @Test fun `una BAJA repetida del mismo dedo es un movimiento`() {
        identidad.traducir(c(3, 0, 0, FaseDeToque.BAJA))
        assertEquals(TipoDeEvento.MUEVE, identidad.traducir(c(3, 0, 9, FaseDeToque.BAJA))!!.tipo)
    }

    @Test fun `dos dedos viajan juntos y soltar uno deja el otro presionado`() {
        identidad.traducir(c(1, 0, 0, FaseDeToque.BAJA))
        val segundo = identidad.traducir(c(2, 50, 50, FaseDeToque.BAJA))!!
        assertEquals(TipoDeEvento.PRESIONA, segundo.tipo)
        assertEquals(setOf(1L, 2L), segundo.punteros.map { it.id }.toSet())
        assertTrue(segundo.punteros.all { it.presionado })
        val sueltaUno = identidad.traducir(c(1, 0, 0, FaseDeToque.SUBE))!!
        assertEquals(mapOf(1L to false, 2L to true), sueltaUno.punteros.associate { it.id to it.presionado })
        val mueveOtro = identidad.traducir(c(2, 50, 90, FaseDeToque.MUEVE))!!
        assertEquals(listOf(2L), mueveOtro.punteros.map { it.id })
    }

    @Test fun `cancelar suelta el dedo en su última posición y lo marca como cancelación`() {
        identidad.traducir(c(4, 7, 7, FaseDeToque.BAJA))
        identidad.traducir(c(4, 7, 30, FaseDeToque.MUEVE))
        val cancela = identidad.traducir(c(4, 999, 999, FaseDeToque.CANCELA))!!
        assertEquals(TipoDeEvento.SUELTA, cancela.tipo)
        assertTrue(cancela.cancela)
        assertEquals(Puntero(4, Offset(7f, 30f), false), cancela.punteros.single())
        assertFalse(identidad.hayDedos)
    }

    @Test fun `cancelar un dedo cancela todo el gesto y los demás dedos se ignoran`() {
        identidad.traducir(c(1, 0, 0, FaseDeToque.BAJA))
        identidad.traducir(c(2, 50, 50, FaseDeToque.BAJA))
        val cancela = identidad.traducir(c(1, 0, 0, FaseDeToque.CANCELA))!!
        assertTrue(cancela.cancela)
        assertEquals(setOf(1L, 2L), cancela.punteros.map { it.id }.toSet())
        assertTrue(cancela.punteros.none { it.presionado })
        assertNull(identidad.traducir(c(2, 50, 90, FaseDeToque.MUEVE)), "el otro dedo ya no es de ningún gesto")
        assertNull(identidad.traducir(c(2, 50, 90, FaseDeToque.SUBE)))
        assertFalse(identidad.hayDedos)
    }

    @Test fun `soltarTodos cancela y no deja dedos pegados`() {
        identidad.traducir(c(1, 0, 0, FaseDeToque.BAJA))
        identidad.traducir(c(2, 5, 5, FaseDeToque.BAJA))
        val todos = identidad.soltarTodos(10)!!
        assertEquals(TipoDeEvento.SUELTA, todos.tipo)
        assertTrue(todos.cancela)
        assertTrue(todos.punteros.none { it.presionado })
        assertFalse(identidad.hayDedos)
        assertNull(identidad.soltarTodos(11))
    }

    @Test fun `coordenadas al 100 por ciento con el lienzo corrido dentro del contenedor`() {
        assertEquals(Offset(110f, 220f), aEscena(100, 200, 1.0, Offset(10f, 20f), 1f, Offset.Zero))
    }

    @Test fun `coordenadas al 150 por ciento caen donde está el dedo`() {
        // 300 px físicos = 200 px lógicos; × densidad 1.5 = 300 px de escena.
        assertEquals(Offset(300f, 150f), aEscena(300, 150, 1.5, Offset.Zero, 1.5f, Offset.Zero))
    }

    @Test fun `coordenadas al 125 por ciento restan la esquina de la escena`() {
        // x: (100 / 1.25 + 20) × 1.25 − 10 = 115 · y: (40 / 1.25 + 0) × 1.25 − 5 = 35
        assertEquals(Offset(115f, 35f), aEscena(100, 40, 1.25, Offset(20f, 0f), 1.25f, Offset(10f, 5f)))
    }
}
