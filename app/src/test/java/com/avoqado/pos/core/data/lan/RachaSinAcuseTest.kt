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

    @Test
    fun `cada estacion lleva su propia cuenta y limpiar borra todo`() {
        val racha = RachaSinAcuse()
        repeat(3) { racha.registrar(setOf("st_barra", "st_postres"), setOf("st_postres")) }
        assertEquals(setOf("st_barra"), racha.sinAlcance.value)
        racha.limpiar()
        assertEquals(emptySet<String>(), racha.sinAlcance.value)
    }
}
