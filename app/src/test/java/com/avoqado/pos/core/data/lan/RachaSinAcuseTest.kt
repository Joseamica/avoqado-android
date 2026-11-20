package com.avoqado.pos.core.data.lan

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tres entregas seguidas sin acuse a la misma estación ⇒ «no se alcanza»; el primer acuse la limpia (3.5, D11). */
class RachaSinAcuseTest {

    @Test
    fun `P1 a la tercera sin acuse la estacion queda sin alcance, y el primer acuse la limpia`() {
        val racha = RachaSinAcuse()
        racha.registrar(setOf("st_barra"), emptySet())
        racha.registrar(setOf("st_barra"), emptySet())
        assertEquals(emptySet<String>(), racha.sinAlcance.value)
        racha.registrar(setOf("st_barra"), emptySet())
        assertEquals(setOf("st_barra"), racha.sinAlcance.value)
        racha.registrar(setOf("st_barra"), setOf("st_barra"))
        assertEquals(emptySet<String>(), racha.sinAlcance.value)
    }

    /** D6 de Flautas (opción B, Codex r5 #2 y r6 #1): cuándo acusó la ÚLTIMA entrega de cada estación; un acuse viejo no cuenta. */
    @Test
    fun `acusaronDesde sigue la ultima entrega de cada estacion y su hora`() {
        val racha = RachaSinAcuse()
        racha.registrar(setOf("st_barra", "st_postres"), setOf("st_barra"), ahora = 1_000)
        assertEquals(setOf("st_barra"), racha.acusaronDesde(0))
        racha.registrar(setOf("st_postres"), setOf("st_postres"), ahora = 5_000)
        assertEquals("una entrega a otra estación no le quita la suya a Barra", setOf("st_barra", "st_postres"), racha.acusaronDesde(0))
        assertEquals("el de Barra ya es viejo", setOf("st_postres"), racha.acusaronDesde(2_000))
        racha.registrar(setOf("st_barra"), emptySet(), ahora = 6_000)
        assertEquals("una entrega sin acuse la borra", setOf("st_postres"), racha.acusaronDesde(0))
        racha.limpiar()
        assertEquals(emptySet<String>(), racha.acusaronDesde(0))
    }

    @Test
    fun `cada estacion lleva su propia cuenta y limpiar borra todo`() {
        val racha = RachaSinAcuse()
        repeat(3) { racha.registrar(setOf("st_barra", "st_postres"), setOf("st_postres")) }
        assertEquals(setOf("st_barra"), racha.sinAlcance.value)
        racha.limpiar()
        assertEquals(emptySet<String>(), racha.sinAlcance.value)
    }
}
