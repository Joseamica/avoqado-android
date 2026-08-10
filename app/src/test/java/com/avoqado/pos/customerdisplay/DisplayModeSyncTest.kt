package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que protege: si el server pudiera pisar un cambio local no confirmado, un
 * equipo sin internet que acaba de invertir sus pantallas las vería regresar
 * solas en el siguiente refresh — a media venta.
 */
class DisplayModeSyncTest {

    @Test
    fun `sin cambio local se adopta lo del server`() {
        assertEquals(
            DisplayModeAction.Adopt(true),
            reconcileDisplayMode(local = false, dirty = false, server = true),
        )
    }

    @Test
    fun `con cambio local pendiente se EMPUJA y no se adopta`() {
        assertEquals(
            DisplayModeAction.Push(true),
            reconcileDisplayMode(local = true, dirty = true, server = false),
        )
    }

    @Test
    fun `server ausente - servidor viejo sin el campo - no se toca nada`() {
        assertEquals(
            DisplayModeAction.Keep,
            reconcileDisplayMode(local = true, dirty = false, server = null),
        )
    }

    @Test
    fun `valores iguales - nada que hacer`() {
        assertEquals(
            DisplayModeAction.Keep,
            reconcileDisplayMode(local = true, dirty = false, server = true),
        )
    }

    @Test
    fun `cambio local que ya coincide con el server - solo marcar sincronizado`() {
        assertEquals(
            DisplayModeAction.Push(true),
            reconcileDisplayMode(local = true, dirty = true, server = true),
        )
    }
}
