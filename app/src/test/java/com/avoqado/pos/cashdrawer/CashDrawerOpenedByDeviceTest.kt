package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.presentation.formatOpenedBy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 6 (plan turno-de-caja fase 2-3): la pantalla de Caja dice quién abrió el turno y desde qué
 * aparato. `openedByName` y `deviceName` ya vivían en `CashDrawerSessionEntity` — la sesión local
 * los llena de fábrica al abrir (staffName real + `Build.MANUFACTURER Build.MODEL`), y el server
 * los manda de vuelta al confirmar. Esta prueba fija SOLO cómo se combinan para mostrarse: nunca
 * "· null" cuando el aparato falta, nunca doble espacio cuando alguno viene con espacios de sobra.
 */
class CashDrawerOpenedByDeviceTest {

    @Test
    fun `nombre y aparato se unen con punto medio`() {
        assertEquals("Ana Pérez · SM-X133", formatOpenedBy("Ana Pérez", "SM-X133"))
    }

    @Test
    fun `sin aparato solo se muestra el nombre`() {
        assertEquals("Ana Pérez", formatOpenedBy("Ana Pérez", null))
    }

    @Test
    fun `aparato en blanco se trata igual que ausente`() {
        assertEquals("Ana Pérez", formatOpenedBy("Ana Pérez", "   "))
    }

    @Test
    fun `aparato vacio se trata igual que ausente`() {
        assertEquals("Ana Pérez", formatOpenedBy("Ana Pérez", ""))
    }

    @Test
    fun `recorta espacios sobrantes de los dos lados`() {
        assertEquals("Ana Pérez · SM-X133", formatOpenedBy("  Ana Pérez  ", "  SM-X133  "))
    }

    @Test
    fun `los dos en blanco da cadena vacia, nunca un punto medio suelto`() {
        assertEquals("", formatOpenedBy("", null))
    }

    /** Nombre vacio con aparato presente ⇒ solo el aparato (la direccion CONTRARIA del degradado). */
    @Test
    fun `nombre vacio y aparato presente muestra solo el aparato`() {
        assertEquals("SM-X133", formatOpenedBy("", "SM-X133"))
    }
}
