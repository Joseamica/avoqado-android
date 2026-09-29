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
            "Sin conexión — 3 por sincronizar (todo se guarda aquí) · Las comandas de Barra salen en papel si la pantalla no contesta",
            textoSinRed(pendingSync = 3, avisoDeCocina = "Las comandas de Barra salen en papel si la pantalla no contesta"),
        )
    }

    /** 3.5, D11: la racha se dice con o sin red; sin red reemplaza al aviso de papel; con red la caja retenida le gana. */
    @Test
    fun `la racha reemplaza al aviso de papel sin red y sola se ve con red - la caja retenida gana`() {
        val papel = "Las comandas de Barra salen en papel si la pantalla no contesta"
        val racha = "La pantalla de Barra no se alcanza por el WiFi"
        assertEquals(
            "Sin conexión — las ventas se guardan en el dispositivo · $racha",
            textoDeLaBanda(visible = true, pendingSync = 0, avisoDeLaCaja = null, avisoDeCocina = papel, avisoDeRacha = racha),
        )
        assertEquals(racha, textoDeLaBanda(visible = false, pendingSync = 0, avisoDeLaCaja = null, avisoDeCocina = papel, avisoDeRacha = racha))
        assertEquals("caja retenida", textoDeLaBanda(visible = false, pendingSync = 0, avisoDeLaCaja = "caja retenida", avisoDeCocina = papel, avisoDeRacha = racha))
        assertEquals(
            "Sin conexión — las ventas se guardan en el dispositivo · $papel",
            textoDeLaBanda(visible = true, pendingSync = 0, avisoDeLaCaja = null, avisoDeCocina = papel, avisoDeRacha = null),
        )
    }
}
