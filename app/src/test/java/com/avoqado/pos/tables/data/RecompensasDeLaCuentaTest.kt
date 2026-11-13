package com.avoqado.pos.tables.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Recompensas» sólo ofrece el saldo leído para la cuenta que se ve (Codex android r4 #1): la GET calcula el máximo contra ESA
 * cuenta, así que el de la cuenta A, ofrecido en la B, prometía 500 puntos por $50 y el canje descontaba $1.
 */
class RecompensasDeLaCuentaTest {

    private val saldo = CustomerLoyalty(customerId = "cli-A", balance = 500, redemptionRate = 0.10, maxRedeemablePoints = 500, canRedeem = true)
    private val leidaEnA = LealtadLeida(orderId = "orden-A", customerId = "cli-A", version = 3, lealtad = saldo)

    @Test
    fun `misma cuenta, mismo cliente y misma versión - se ofrece`() {
        assertEquals(saldo, leidaEnA.paraLaCuenta(OrderDetail(id = "orden-A", customerId = "cli-A", version = 3))?.lealtad)
    }

    @Test
    fun `otra cuenta de la mesa - no se ofrece el saldo de la anterior`() {
        assertNull(leidaEnA.paraLaCuenta(OrderDetail(id = "orden-B", customerId = "cli-A", version = 3)))
    }

    @Test
    fun `la cuenta cambió de cliente - no se ofrece el saldo del anterior`() {
        assertNull(leidaEnA.paraLaCuenta(OrderDetail(id = "orden-A", customerId = "cli-B", version = 3)))
        assertNull(leidaEnA.paraLaCuenta(OrderDetail(id = "orden-A", customerId = null, version = 3)))
    }

    @Test
    fun `la cuenta cambió (otra versión) - no se ofrece un máximo calculado contra la de antes`() {
        assertNull(leidaEnA.paraLaCuenta(OrderDetail(id = "orden-A", customerId = "cli-A", version = 4)))
    }

    @Test
    fun `sin cuenta o sin saldo leído - nada`() {
        assertNull(leidaEnA.paraLaCuenta(null))
        assertNull((null as LealtadLeida?).paraLaCuenta(OrderDetail(id = "orden-A", customerId = "cli-A", version = 3)))
    }

    @Test
    fun `cuenta sin cliente con un saldo que dice canRedeem - pide el cliente, no prende el botón`() {
        assertEquals(
            "Esta cuenta no tiene puntos para canjear. Agrega el cliente en la pestaña «Cliente».",
            motivoSinRecompensas(tieneCliente = false, lealtad = saldo, fallo = null),
        )
        assertEquals(
            "La cuenta todavía no llega del servidor, así que no se sabe si tiene cliente. Intenta en un momento.",
            motivoSinRecompensas(tieneCliente = null, lealtad = saldo, fallo = null),
        )
    }
}
