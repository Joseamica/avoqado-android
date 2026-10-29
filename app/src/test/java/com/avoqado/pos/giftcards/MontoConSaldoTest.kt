package com.avoqado.pos.giftcards
import com.avoqado.pos.giftcards.domain.*
import org.junit.Assert.*
import org.junit.Test

class MontoConSaldoTest {
    @Test fun montoEsLoMenorDespuesDePropina() {
        for ((entrada, salida) in listOf(
            Triple(30000,50000,0) to Pair(30000,0), Triple(80000,50000,0) to Pair(50000,0),
            Triple(30000,32000,3000) to Pair(29000,3000), Triple(30000,2000,3000) to Pair(2000,0),
            Triple(30000,12000,2000) to Pair(10000,2000))) {
            val r = montoOfrecido(entrada.first, entrada.second, entrada.third)
            assertEquals(salida.first, r.montoCents); assertEquals(salida.second,r.propinaCents)
            assertEquals(if (entrada.third > entrada.second) "El saldo no alcanza para la propina; cobra la propina con otro medio." else null, r.aviso)
        }
    }
}
