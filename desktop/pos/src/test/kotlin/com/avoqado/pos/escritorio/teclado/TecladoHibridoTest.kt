package com.avoqado.pos.escritorio.teclado

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TecladoHibridoTest {
    private fun visibleCon(ultimo: Puntero?) = TecladoHibrido().apply { alCambiarEntrada(true, ultimo) }.visible

    @Test fun `entrada activa tras un dedo se ve`() = assertTrue(visibleCon(Puntero.DEDO))
    @Test fun `entrada activa tras el mouse no se ve`() = assertFalse(visibleCon(Puntero.MOUSE))
    @Test fun `entrada activa sin puntero previo no se ve`() = assertFalse(visibleCon(null))

    @Test fun `entrada inactiva lo oculta`() {
        val t = TecladoHibrido().apply { alCambiarEntrada(true, Puntero.DEDO) }
        t.alCambiarEntrada(false, Puntero.DEDO)
        assertFalse(t.visible)
    }

    @Test fun `tecla fisica lo oculta`() {
        val t = TecladoHibrido().apply { alCambiarEntrada(true, Puntero.DEDO) }
        t.alTeclaFisica()
        assertFalse(t.visible)
    }

    @Test fun `presionar con el mouse lo oculta`() {
        val t = TecladoHibrido().apply { alCambiarEntrada(true, Puntero.DEDO) }
        t.alPresionarConMouse()
        assertFalse(t.visible)
    }

    @Test fun `ocultar a mano y un toque nuevo sobre el campo lo reabre`() {
        val t = TecladoHibrido().apply { alCambiarEntrada(true, Puntero.DEDO) }
        t.alOcultarAMano()
        assertFalse(t.visible)
        t.trasSoltarDedo(true)
        assertTrue(t.visible)
    }

    @Test fun `soltar un dedo sin campo en escritura no lo muestra`() {
        val t = TecladoHibrido()
        t.trasSoltarDedo(false)
        assertFalse(t.visible)
    }
}
