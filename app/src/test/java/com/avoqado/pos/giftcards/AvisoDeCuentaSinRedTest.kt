package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.giftcards.domain.avisoDeCuentaSinRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AvisoDeCuentaSinRedTest {
    @Test fun `una cuenta con tarjetas de regalo las nombra`() {
        assertEquals(TextosDeTarjetas.SIN_RED, avisoDeCuentaSinRed(esCuentaDeTarjetas = true, cobrosConSaldo = 0, sePuedeCobrar = false))
        assertEquals(TextosDeTarjetas.SIN_RED, avisoDeCuentaSinRed(esCuentaDeTarjetas = false, cobrosConSaldo = 1, sePuedeCobrar = true))
    }

    @Test fun `una cuenta sin tarjetas que se puede cobrar no las nombra`() {
        // También la de un intento con saldo que el SERVIDOR rechazó: no se descontó nada.
        for (cobros in listOf(0, null)) {
            val aviso = avisoDeCuentaSinRed(esCuentaDeTarjetas = false, cobrosConSaldo = cobros, sePuedeCobrar = true)!!
            assertEquals(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED, aviso)
            assertFalse(aviso.contains("tarjetas de regalo"))
        }
    }

    @Test fun `una cuenta sin tarjetas y sin nada por cobrar no dice nada`() {
        assertNull(avisoDeCuentaSinRed(esCuentaDeTarjetas = false, cobrosConSaldo = 0, sePuedeCobrar = false))
    }
}
