package com.avoqado.pos.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que el cajero teclea en la cantidad (founder, 17-nov: «poder teclear un número grande, para no sumar de 1 en 1»). */
class CantidadTecleableTest {
    @Test fun `un numero tecleado es la cantidad`() {
        assertEquals(24, cantidadTecleada("24"))
        assertEquals(5, cantidadTecleada("05"))
        assertEquals(999, cantidadTecleada("999"))
    }

    @Test fun `vacio o cero no es cantidad, se queda la de antes`() {
        assertNull(cantidadTecleada(""))
        assertNull(cantidadTecleada("0"))
        assertNull(cantidadTecleada("000"))
    }

    @Test fun `solo entran digitos`() {
        assertEquals("12", soloDigitosDeCantidad("1.2"))
        assertEquals("12", soloDigitosDeCantidad("-12"))
    }

    @Test fun `un cuarto digito o una letra no es cantidad (la pistola o un dedo de mas)`() {
        assertFalse(noEsCantidad("999"))
        assertTrue(noEsCantidad("2475"))
        assertTrue(noEsCantidad("ABC123"))
        assertFalse(noEsCantidad("1.2"))   // los signos del teclado numérico se tiran, no bloquean
        assertNull(cantidadTecleada("1000"))
    }

    @Test fun `dos teclas a menos de 60 ms son la pistola, a 80 ms o mas una persona`() {
        assertFalse(esRitmoDePistola(null, 1_000))
        assertTrue(esRitmoDePistola(1_000, 1_010))
        assertTrue(esRitmoDePistola(1_000, 1_059))
        assertFalse(esRitmoDePistola(1_000, 1_060))
        assertFalse(esRitmoDePistola(1_000, 1_080))
        assertFalse(esRitmoDePistola(1_000, 900))   // reloj hacia atrás: no se adivina
    }
}
