package com.avoqado.pos.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Iniciales del chip de identidad en "Más". La sesión que originó la pantalla
 * (una de CASHIER olvidada) puede venir de huella, y ESA no guarda el nombre —
 * así que los casos sin nombre no son teóricos: son el camino que más se usa.
 */
class IdentityInitialsTest {

    @Test
    fun `nombre y apellido dan dos iniciales`() {
        assertEquals("JA", initialsFor("Jose Amieva", "jose@avoqado.io"))
    }

    @Test
    fun `un nombre de tres palabras se queda en dos iniciales`() {
        assertEquals("JA", initialsFor("Jose Antonio Amieva", null))
    }

    @Test
    fun `un solo nombre da una inicial`() {
        assertEquals("J", initialsFor("Jose", null))
    }

    @Test
    fun `los espacios de mas no producen iniciales vacias`() {
        assertEquals("JA", initialsFor("  Jose   Amieva ", null))
    }

    @Test
    fun `sin nombre cae al correo`() {
        // Login por huella: restoreBiometricSession guarda el correo pero NO el
        // nombre. Sin este fallback el chip saldría vacío justo ahí.
        assertEquals("D", initialsFor(null, "devjamica@gmail.com"))
    }

    @Test
    fun `un nombre en blanco cuenta como ausente y cae al correo`() {
        assertEquals("D", initialsFor("   ", "devjamica@gmail.com"))
    }

    @Test
    fun `un correo que empieza con simbolo toma su primer caracter util`() {
        assertEquals("A", initialsFor(null, "_avoqado@gmail.com"))
    }

    @Test
    fun `sin nombre ni correo no hay iniciales y se pinta el icono generico`() {
        assertNull(initialsFor(null, null))
        assertNull(initialsFor("", ""))
    }
}
