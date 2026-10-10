package com.avoqado.pos.tables.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.avoqado.pos.designsystem.theme.SurfaceDark
import com.avoqado.pos.designsystem.theme.SurfaceVariantDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 (prueba real del 9-oct): en oscuro la mesa ocupada medía 1.08:1 contra el área y casi desaparecía. Contraste WCAG:
 * ≥ 3:1 para distinguir una figura, ≥ 4.5:1 para leer su letra.
 */
class ColoresDeMesaTest {

    /** El fondo del área en oscuro, el mismo que pinta `FloorPlanCanvas`: `surfaceVariant` al 35 % sobre el fondo. */
    private val areaOscura = SurfaceVariantDark.copy(alpha = 0.35f).compositeOver(SurfaceDark)

    private fun contraste(a: Color, b: Color): Double {
        val (claro, oscuro) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (claro + 0.05) / (oscuro + 0.05)
    }

    @Test
    fun `en oscuro la ocupada se ve contra el area`() {
        val ocupada = tableColors(occupied = true, dark = true)
        assertTrue("relleno vs área: ${contraste(ocupada.fill, areaOscura)}", contraste(ocupada.fill, areaOscura) >= 3.0)
        assertTrue("su letra: ${contraste(ocupada.content, ocupada.fill)}", contraste(ocupada.content, ocupada.fill) >= 4.5)
    }

    @Test
    fun `en oscuro la ocupada y la libre no se confunden`() {
        val ocupada = tableColors(occupied = true, dark = true)
        val libre = tableColors(occupied = false, dark = true)
        assertTrue("ocupada vs libre: ${contraste(ocupada.fill, libre.fill)}", contraste(ocupada.fill, libre.fill) >= 3.0)
        assertTrue("letra de la libre: ${contraste(libre.content, libre.fill)}", contraste(libre.content, libre.fill) >= 4.5)
    }

    @Test
    fun `en claro la paleta es la de siempre`() {
        assertEquals(TableColors(OccupiedColor, Color.White, OccupiedColor), tableColors(occupied = true, dark = false))
        assertEquals(TableColors(FreeFill, Color(0xFF1A1A1A), FreeBorder), tableColors(occupied = false, dark = false))
    }
}
