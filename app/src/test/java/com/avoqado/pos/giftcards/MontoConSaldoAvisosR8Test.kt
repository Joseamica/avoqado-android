package com.avoqado.pos.giftcards

import com.avoqado.pos.giftcards.domain.*
import org.junit.Assert.*
import org.junit.Test

class MontoConSaldoAvisosR8Test {
    @Test fun `saldo cero sin propina dice sin saldo`() {
        val r=montoOfrecido(25000,0,0)
        assertEquals("Esta tarjeta de regalo no tiene saldo.",r.aviso)
        assertFalse(r.aviso.orEmpty().contains("propina"))
    }
    @Test fun `saldo no positivo con propina tambien dice sin saldo`() {
        for (saldo in listOf(0,-1)) {
            val r=montoOfrecido(25000,saldo,1000)
            assertEquals("Esta tarjeta de regalo no tiene saldo.",r.aviso)
        }
    }
    @Test fun `saldo positivo menor que la propina usa aviso de propina`() {
        val r=montoOfrecido(25000,500,1000)
        assertEquals(TextosDeTarjetas.SALDO_NO_ALCANZA_PROPINA,r.aviso)
    }
    @Test fun `aviso de propina corresponde a lo que ofrece la tarjeta`() {
        for ((entrada,aviso) in listOf(
            Triple(25000,1000,1000) to TextosDeTarjetas.SALDO_NO_ALCANZA_PROPINA,
            Triple(0,5000,0) to null,
            Triple(0,5000,1000) to TextosDeTarjetas.SALDO_NO_ALCANZA_PROPINA,
            Triple(25000,30000,1000) to null)) {
            assertEquals(aviso,montoOfrecido(entrada.first,entrada.second,entrada.third).aviso)
        }
    }
    @Test fun `importes de casos nuevos y bordes se conservan`() {
        for ((entrada,salida) in listOf(
            Triple(25000,0,0) to Pair(0,0), Triple(25000,0,1000) to Pair(0,0),
            Triple(25000,500,1000) to Pair(500,0), Triple(25000,1000,1000) to Pair(1000,0),
            Triple(25000,1100,1000) to Pair(100,1000), Triple(25000,30000,1000) to Pair(25000,1000),
            Triple(0,5000,1000) to Pair(0,0), Triple(-1,1000,0) to Pair(0,0))) {
            val r=montoOfrecido(entrada.first,entrada.second,entrada.third)
            assertEquals(salida.first,r.montoCents)
            assertEquals(salida.second,r.propinaCents)
        }
    }
}
