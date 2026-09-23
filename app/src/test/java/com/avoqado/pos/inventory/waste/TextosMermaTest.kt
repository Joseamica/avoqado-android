package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.domain.antiguedadDelCatalogo
import com.avoqado.pos.inventory.waste.domain.etiquetaDeUnidad
import com.avoqado.pos.inventory.waste.domain.textoDeConfirmacion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que la pantalla le dice al cajero, armado con funciones puras. Espejo exacto de
 * `TextosMermaTests.swift` de avoqado-ios: mismas entradas, mismas salidas.
 */
class TextosMermaTest {

    /** El catálogo manda el enum del servidor; el cajero lee la abreviatura de siempre. */
    @Test
    fun `la unidad se lee como la lee el cajero`() {
        assertEquals("kg", etiquetaDeUnidad("KILOGRAM"))
        assertEquals("pza", etiquetaDeUnidad("UNIT"))
        assertEquals("ml", etiquetaDeUnidad("milliliter"))
    }

    /** Una unidad nueva del servidor no se traduce a ciegas: se muestra tal cual. */
    @Test
    fun `una unidad que no conocemos se muestra tal cual, en minusculas`() {
        assertEquals("costal", etiquetaDeUnidad("COSTAL"))
    }

    /** 🔴 Spec §5: cuánto y de qué, con la unidad. Una merma no se deshace desde el POS. */
    @Test
    fun `la confirmacion dice cuanto y de que, con la unidad`() {
        assertEquals(
            "Vas a registrar 3 kg de Aguacate como merma.",
            textoDeConfirmacion("3", "KILOGRAM", "Aguacate"),
        )
    }

    @Test
    fun `la antiguedad del catalogo se dice en minutos, horas o dias`() {
        val ahora = 1_000_000_000_000L
        assertEquals("Catálogo de hace 12 min", antiguedadDelCatalogo(ahora, ahora - 12 * 60_000L))
        assertEquals("Catálogo de hace 5 h", antiguedadDelCatalogo(ahora, ahora - 5 * 3_600_000L))
        assertEquals("Catálogo de hace 3 días", antiguedadDelCatalogo(ahora, ahora - 3 * 86_400_000L))
    }

    /** Un reloj que va atrás no produce «hace -3 h». */
    @Test
    fun `un reloj atrasado no produce una antiguedad negativa`() {
        assertEquals("Catálogo de hace 0 min", antiguedadDelCatalogo(1_000_000L, 5_000_000L))
    }
}
