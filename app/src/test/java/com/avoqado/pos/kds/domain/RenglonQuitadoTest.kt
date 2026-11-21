package com.avoqado.pos.kds.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D15: un renglón quitado (anulado en la caja o retirado por la plataforma) se pinta tachado, con su etiqueta sin tachar. */
class RenglonQuitadoTest {
    private fun item(nombre: String, removedAt: String?) = KDSOrderItem(id = "i", productName = nombre, quantity = 1, removedAt = removedAt)

    @Test fun `vivo no es quitado`() = assertNull(item("Agua de pepino", null).quitado())

    @Test fun `anulado en la caja separa la etiqueta del nombre`() =
        assertEquals(RenglonQuitado("ANULADO", "Agua de pepino"), item("ANULADO · Agua de pepino", "2026-11-20T18:00:00.000Z").quitado())

    @Test fun `retirado por la plataforma usa su etiqueta`() =
        assertEquals(RenglonQuitado("RETIRADO", "Tacos"), item("RETIRADO · Tacos", "2026-11-20T18:00:00.000Z").quitado())

    @Test fun `sin etiqueta conocida se dice ANULADO y el nombre queda entero`() =
        assertEquals(RenglonQuitado("ANULADO", "Agua"), item("Agua", "2026-11-20T18:00:00.000Z").quitado())
}
