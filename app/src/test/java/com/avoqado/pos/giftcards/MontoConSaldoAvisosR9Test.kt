package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.domain.*
import org.junit.Assert.*
import org.junit.Test

class MontoConSaldoAvisosR9Test {
    @Test fun `saldo igual a propina avisa que se cobra aparte`() {
        val oferta = montoOfrecido(25000, 1000, 1000)
        assertEquals(1000, oferta.montoCents)
        assertEquals(0, oferta.propinaCents)
        assertEquals(TextosDeTarjetas.SALDO_NO_ALCANZA_PROPINA, oferta.aviso)
    }

    @Test fun `faltante cero conserva oferta cero y avisa propina excluida`() {
        val oferta = montoOfrecido(0, 5000, 1000)
        assertEquals(0, oferta.montoCents)
        assertEquals(0, oferta.propinaCents)
        assertEquals(TextosDeTarjetas.SALDO_NO_ALCANZA_PROPINA, oferta.aviso)
    }

    @Test fun `aviso corresponde a propina ofrecida en todas las ramas`() {
        for (faltante in listOf(-1, 0, 25000)) {
            for (saldo in listOf(-1, 0, 500, 1000, 1100, 30000)) {
                for (propina in listOf(0, 1000)) {
                    val oferta = montoOfrecido(faltante, saldo, propina)
                    val esperado = when {
                        saldo <= 0 -> TextosDeTarjetas.TARJETA_SIN_SALDO
                        propina > 0 && oferta.propinaCents == 0 -> TextosDeTarjetas.SALDO_NO_ALCANZA_PROPINA
                        else -> null
                    }
                    assertEquals("faltante=$faltante saldo=$saldo propina=$propina oferta=$oferta", esperado, oferta.aviso)
                }
            }
        }
    }
}
