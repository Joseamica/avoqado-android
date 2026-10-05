package com.avoqado.pos.timeclock.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El PIN con teclado FÍSICO (PC con Windows, tableta con teclado). Full-testing de Windows, 3-oct:
 * en «Cambiar usuario» los dígitos del teclado no entraban; sólo funcionaba con clic o dedo.
 */
class PinConTeclaTest {

    @Test
    fun `un digito se agrega`() {
        assertEquals("17", pinConTecla("1", caracter = '7', borrar = false, maxLength = 10))
    }

    @Test
    fun `retroceso borra el ultimo y en vacio no truena`() {
        assertEquals("17", pinConTecla("173", caracter = null, borrar = true, maxLength = 10))
        assertEquals("", pinConTecla("", caracter = null, borrar = true, maxLength = 10))
    }

    @Test
    fun `no pasa del largo maximo pero la tecla se consume`() {
        assertEquals("1735", pinConTecla("1735", caracter = '9', borrar = false, maxLength = 4))
    }

    @Test
    fun `lo que no es digito no le toca al PIN`() {
        assertNull(pinConTecla("1", caracter = 'a', borrar = false, maxLength = 10))
        assertNull(pinConTecla("1", caracter = null, borrar = false, maxLength = 10))
    }
}
