package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Quién manda sobre "cuál pantalla mira el cliente": **el APARATO**.
 *
 * 🔴 No es una preferencia del negocio: describe cómo está ATORNILLADA ESTA D3 a ESTE
 * mostrador. Dos aparatos del mismo negocio pueden estar montados al revés entre sí, y el
 * server guarda UN valor por negocio — así que dejarlo mandar hacía que invertir en un
 * mostrador volteara las pantallas del de al lado.
 *
 * Medido en la D3 el 2026-08-25: el valor local se puso en `true` y el siguiente arranque
 * lo devolvió a `false` porque el server decía `false`. El interruptor peleaba con el
 * servidor y ganaba quien llegara primero.
 */
class DisplayModeSyncTest {

    @Test
    fun `P1 el server NO puede voltear las pantallas de este aparato`() {
        // Antes esto devolvía Adopt(true) y el mostrador se volteaba solo.
        assertEquals(
            DisplayModeAction.Push(false),
            reconcileDisplayMode(local = false, dirty = false, server = true),
        )
    }

    @Test
    fun `P1 al diferir, el registro del negocio se pone al dia con el aparato`() {
        assertEquals(
            DisplayModeAction.Push(true),
            reconcileDisplayMode(local = true, dirty = false, server = false),
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
