package com.avoqado.pos.reservations

import com.avoqado.pos.reservations.data.model.LayoutConfig
import com.avoqado.pos.reservations.data.model.LayoutSpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cómo se nombra el lugar de alguien en el kiosco.
 *
 * 🔴 Antes todo salía como "Lugar 3", incluso en un estudio de yoga con tapetes. El acomodo
 * ya guarda de qué es (`iconType`), y no usarlo hacía que el kiosco hablara en un idioma
 * que no es el del negocio: quien llega a spinning busca su BICI, no su "lugar".
 */
class SpotLabelTest {

    private fun acomodo(tipo: String?) = LayoutConfig(
        iconType = tipo,
        spots = listOf(LayoutSpot(id = "3", label = "3"), LayoutSpot(id = "7", label = "7")),
    )

    @Test
    fun `en yoga son tapetes`() {
        assertEquals("Tapete 3", acomodo("mat").spotLabelFor("3"))
    }

    @Test
    fun `en spinning son bicis`() {
        assertEquals("Bici 7", acomodo("bike").spotLabelFor("7"))
    }

    @Test
    fun `en un spa son camas`() {
        assertEquals("Cama 3", acomodo("bed").spotLabelFor("3"))
    }

    @Test
    fun `en pilates son reformers`() {
        assertEquals("Reformer 3", acomodo("reformer").spotLabelFor("3"))
    }

    @Test
    fun `un tipo que no conocemos cae en Lugar, que es cierto para cualquiera`() {
        assertEquals("Lugar 3", acomodo("generic").spotLabelFor("3"))
        assertEquals("Lugar 3", acomodo(null).spotLabelFor("3"))
    }

    @Test
    fun `P1 un lugar que ya no existe en el acomodo NO se inventa`() {
        assertNull(acomodo("mat").spotLabelFor("99"))
    }
}
