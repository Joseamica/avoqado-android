package com.avoqado.pos.payment

import com.avoqado.pos.payment.domain.parteProporcional
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D10 (27-oct, founder «A»): al dividir «por producto» cada parte paga la parte PROPORCIONAL del saldo de la venta, según lo
 * que valen sus renglones. La última, o la que dejaría ≤ 1 ¢ o vale $0, es TODO lo que falta (el SERVIDOR perdona 1 ¢ y no
 * deja cobrarlo después). Espejo de iOS `ParteProporcionalTests.swift`.
 */
class ParteProporcionalTest {

    @Test
    fun `cafe y pan de 100 con 10 por ciento - cada uno paga 90`() {
        val parte = parteProporcional(pesosElegidos = 10_000, pesosRestantes = 20_000, saldoCents = 18_000)
        assertEquals(9_000, parte.cents)
        assertFalse(parte.todoLoQueFalta)
    }

    @Test
    fun `lo que queda es todo lo que falta`() {
        val parte = parteProporcional(pesosElegidos = 10_000, pesosRestantes = 10_000, saldoCents = 9_000)
        assertEquals(9_000, parte.cents)
        assertTrue(parte.todoLoQueFalta)
    }

    @Test
    fun `tres productos de centavos raros suman exactamente el total`() {
        // $1.01, $2.03 y $3.07 con $1.01 de descuento de cuenta: total $5.10.
        val pesos = listOf(101L, 203L, 307L)
        var saldo = 510
        var restantes = pesos.sum()
        var cobrado = 0
        for (peso in pesos) {
            val parte = parteProporcional(peso, restantes, saldo)
            cobrado += parte.cents
            saldo -= parte.cents
            restantes -= peso
            if (parte.todoLoQueFalta) break
        }
        assertEquals(510, cobrado)
        assertEquals(0, saldo)
    }

    @Test
    fun `una parte que saldria en cero se junta con todo lo que falta`() {
        // $10, $20 y $30 con $59.99 de descuento: total $0.01. Elegir el de $10 daría $0.
        val parte = parteProporcional(pesosElegidos = 1_000, pesosRestantes = 6_000, saldoCents = 1)
        assertEquals(1, parte.cents)
        assertTrue(parte.todoLoQueFalta)
    }

    @Test
    fun `una parte que dejaria un centavo se junta con todo lo que falta`() {
        // Dos de $1 con $1.98 de descuento: total $0.02; cada uno valdría $0.01 y el SERVIDOR cerraría con 1 ¢ vivo.
        val parte = parteProporcional(pesosElegidos = 100, pesosRestantes = 200, saldoCents = 2)
        assertEquals(2, parte.cents)
        assertTrue(parte.todoLoQueFalta)
    }

    @Test
    fun `sin saldo no se cobra nada`() {
        val parte = parteProporcional(pesosElegidos = 100, pesosRestantes = 200, saldoCents = 0)
        assertEquals(0, parte.cents)
        assertTrue(parte.todoLoQueFalta)
    }
}
