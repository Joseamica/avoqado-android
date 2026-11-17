package com.avoqado.pos.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lo que el cajero teclea en la cantidad (founder, 17-nov: «poder teclear un número grande, para no sumar de 1 en 1»). */
class CantidadTecleableTest {
    @Test fun `un numero tecleado es la cantidad`() {
        assertEquals(24, cantidadTecleada("24"))
        assertEquals(5, cantidadTecleada("05"))
    }

    @Test fun `vacio o cero no es cantidad, se queda la de antes`() {
        assertNull(cantidadTecleada(""))
        assertNull(cantidadTecleada("0"))
        assertNull(cantidadTecleada("000"))
    }

    @Test fun `solo entran digitos y hasta tres`() {
        assertEquals("12", soloDigitosDeCantidad("1.2"))
        assertEquals("12", soloDigitosDeCantidad("-12"))
        assertEquals("999", soloDigitosDeCantidad("99999"))
        assertEquals("100", soloDigitosDeCantidad("1000"))   // un dedo de más no hace 1,000
    }
}
