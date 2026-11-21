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

    @Test fun `un avance guardado sin red no repinta pendiente lo que el servidor ya anulo`() {
        val anulada = PreparationLine(id = "l", productName = "ANULADO · Agua", quantity = 1,
            preparation = PreparationCounts(CANCELLED = 1), preparationRevision = 1, removedAt = "2026-11-20T18:00:00.000Z")
        val reabrir = PreparationEffect("i", expectedRevision = 1, action = PreparationAction.REOPEN, quantity = 1, reason = "x")
        assertEquals(anulada, PreparationLocalLine(anulada, listOf(reabrir)).projected())
        val viva = anulada.copy(productName = "Agua", removedAt = null)
        assertEquals(PreparationCounts(PENDING = 1), PreparationLocalLine(viva, listOf(reabrir)).projected().preparation)
    }

    @Test fun `sin etiqueta conocida se dice ANULADO y el nombre queda entero`() =
        assertEquals(RenglonQuitado("ANULADO", "Agua"), item("Agua", "2026-11-20T18:00:00.000Z").quitado())
}
