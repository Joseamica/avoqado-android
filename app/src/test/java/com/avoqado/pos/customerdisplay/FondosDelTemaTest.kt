package com.avoqado.pos.customerdisplay

import androidx.compose.ui.graphics.Color
import com.avoqado.pos.designsystem.theme.SurfaceDark
import com.avoqado.pos.designsystem.theme.SurfaceLight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contrato con `avoqado-server/tests/fixtures/customer-display/theme-backgrounds.json`
 * (`{ "LIGHT": "#FFFFFF", "DARK": "#1C1C1E" }`): el editor del dashboard pinta su vista previa con esos fondos, así que
 * la pantalla del cliente tiene que pintar los MISMOS. Si alguien cambia un fondo del tema, cambia también el JSON.
 */
class FondosDelTemaTest {
    @Test fun `fondo claro es FFFFFF`() = assertEquals(Color(0xFFFFFFFF), SurfaceLight)
    @Test fun `fondo oscuro es 1C1C1E`() = assertEquals(Color(0xFF1C1C1E), SurfaceDark)
}
