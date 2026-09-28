package com.avoqado.pos.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Etapa 3 del KDS (3.4): la banda de «Sin conexión» dice qué estaciones salen en papel. Espejo de iOS. */
class ConnectivityBannerTextoTest {

    @Test
    fun `sin aviso de cocina la banda dice lo de siempre`() {
        assertEquals("Sin conexión — las ventas se guardan en el dispositivo", textoSinRed(pendingSync = 0, avisoDeCocina = null))
        assertEquals("Sin conexión — 3 por sincronizar (todo se guarda aquí)", textoSinRed(pendingSync = 3, avisoDeCocina = null))
    }

    @Test
    fun `P1 con estaciones solo pantalla la banda dice que salen en papel`() {
        assertEquals(
            "Sin conexión — 3 por sincronizar (todo se guarda aquí) · Las comandas de Barra salen en papel",
            textoSinRed(pendingSync = 3, avisoDeCocina = "Las comandas de Barra salen en papel"),
        )
    }
}
