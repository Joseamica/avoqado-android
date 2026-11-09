package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.giftcards.domain.avisoDeCuentaSinRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AvisoDeCuentaSinRedTest {
    @Test fun `una cuenta con tarjetas de regalo las nombra`() {
        assertEquals(TextosDeTarjetas.SIN_RED, avisoDeCuentaSinRed(traeTarjetas = true, sePuedeCobrar = true))
        assertEquals(TextosDeTarjetas.SIN_RED, avisoDeCuentaSinRed(traeTarjetas = true, sePuedeCobrar = false))
    }

    @Test fun `una cuenta sin tarjetas que se puede cobrar no las nombra`() {
        val aviso = avisoDeCuentaSinRed(traeTarjetas = false, sePuedeCobrar = true)!!
        assertEquals(TextosDeTarjetas.COBRAR_CUENTA_SIN_RED, aviso)
        assertFalse(aviso.contains("tarjetas de regalo"))
    }

    @Test fun `una cuenta sin tarjetas y sin nada por cobrar no dice nada`() {
        assertNull(avisoDeCuentaSinRed(traeTarjetas = false, sePuedeCobrar = false))
    }
}
