package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.network.FalloDeLectura
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Recompensas» en gris dice la causa VERDADERA. Antes había un solo texto («Agrega el cliente…») que mentía sin red y en una
 * cuenta con abono, donde la clienta sí está (medido en la SAMSUNG contra el banco de la nube, 12-nov).
 */
class MotivoSinRecompensasTest {

    private fun lealtad(
        canRedeem: Boolean = false,
        active: Boolean = true,
        balance: Int = 500,
        min: Int = 10,
        max: Int = 0,
        rate: Double = 0.10,
    ) = CustomerLoyalty(
        customerName = "Laurel Berge",
        active = active,
        balance = balance,
        redemptionRate = rate,
        minPointsRedeem = min,
        // Como lo manda el SERVIDOR: `money(balance × redemptionRate)`.
        balanceValue = Math.round(balance * rate * 100) / 100.0,
        maxRedeemablePoints = max,
        canRedeem = canRedeem,
    )

    @Test
    fun `sin red con un saldo leído antes (canRedeem true) - el botón se apaga y dice sin red (Codex android r1 3)`() {
        assertEquals(
            "Sin conexión: canjear puntos necesita internet. Se puede en cuanto vuelva la red.",
            motivoSinRecompensas(tieneCliente = true, lealtad = lealtad(canRedeem = true, max = 100), fallo = null, enLinea = false),
        )
    }

    @Test
    fun `sin cliente se pide agregarlo (el texto de siempre)`() {
        assertEquals(
            "Esta cuenta no tiene puntos para canjear. Agrega el cliente en la pestaña «Cliente».",
            motivoSinRecompensas(tieneCliente = false, lealtad = null, fallo = null),
        )
    }

    @Test
    fun `sin red no dice que falta el cliente`() {
        assertEquals(
            "Sin conexión: canjear puntos necesita internet. Se puede en cuanto vuelva la red.",
            motivoSinRecompensas(tieneCliente = true, lealtad = null, fallo = FalloDeLectura.SIN_RED),
        )
    }

    @Test
    fun `sin red y sin la cuenta completa (no se sabe si hay cliente) - dice sin red, no «agrega el cliente»`() {
        assertEquals(
            "Sin conexión: canjear puntos necesita internet. Se puede en cuanto vuelva la red.",
            motivoSinRecompensas(tieneCliente = false, lealtad = null, fallo = null, enLinea = false),
        )
    }

    @Test
    fun `sin red con un saldo viejo que no alcanza - dice sin red`() {
        assertEquals(
            "Sin conexión: canjear puntos necesita internet. Se puede en cuanto vuelva la red.",
            motivoSinRecompensas(true, lealtad(max = 0), null, enLinea = false),
        )
    }

    @Test
    fun `el servidor contestó mal`() {
        assertEquals(
            "No se pudo leer el saldo de puntos del cliente. Vuelve a abrir la cuenta para intentarlo.",
            motivoSinRecompensas(tieneCliente = true, lealtad = null, fallo = FalloDeLectura.SERVIDOR),
        )
    }

    @Test
    fun `mientras se lee el saldo`() {
        assertEquals("Leyendo los puntos del cliente…", motivoSinRecompensas(true, null, null))
    }

    @Test
    fun `se puede canjear - sin motivo`() {
        assertNull(motivoSinRecompensas(true, lealtad(canRedeem = true, max = 100), null))
    }

    @Test
    fun `programa apagado`() {
        assertEquals(
            "El programa de lealtad está apagado en esta sucursal. Se prende desde el panel web.",
            motivoSinRecompensas(true, lealtad(active = false), null),
        )
    }

    @Test
    fun `pocos puntos`() {
        assertEquals(
            "Laurel Berge tiene 5 puntos; se necesitan al menos 10 para canjear.",
            motivoSinRecompensas(true, lealtad(balance = 5), null),
        )
    }

    @Test
    fun `queda algo por descontar pero no cabe ni un punto - dice eso, no «tiene un pago» (Codex android r2 1)`() {
        // La GET real del SERVIDOR: cuenta de $100 con $99.99 de descuento, sin pagos; saldo 2,000, mínimo 1,000, $0.10 por punto.
        val l = CustomerLoyalty(
            customerName = "Laurel Berge", active = true, balance = 2000, redemptionRate = 0.10, minPointsRedeem = 1000,
            balanceValue = 200.0, maxRedeemablePoints = 0, maxRedeemableValue = 0.01, canRedeem = false,
        )
        assertEquals(
            "Lo que queda por descontar en esta cuenta ($0.01) no alcanza para canjear puntos.",
            motivoSinRecompensas(tieneCliente = true, lealtad = l, fallo = null),
        )
    }

    // Pasada completa de la familia «causa falsa» (Codex android r3 #1): cada forma en que el SERVIDOR (`getCustomerLoyalty`) manda
    // canRedeem=false con el programa prendido y el saldo en el mínimo del negocio.

    @Test
    fun `mínimo 0 y saldo 0 - dice que no tiene puntos, no «tiene un pago» (Codex android r3 1)`() {
        val l = CustomerLoyalty(
            customerName = "Laurel Berge", active = true, balance = 0, redemptionRate = 0.10, minPointsRedeem = 0,
            balanceValue = 0.0, maxRedeemablePoints = 0, maxRedeemableValue = 0.0,
        )
        assertEquals("Laurel Berge no tiene puntos para canjear.", motivoSinRecompensas(true, l, null))
    }

    @Test
    fun `puntos que valen $0 al redondear - lo dice, no «tiene un pago» (Codex android r3 1)`() {
        // Mínimo 10, saldo 40 y $0.0001 por punto: 40 × 0.0001 = $0.004 → $0.00.
        val l = CustomerLoyalty(
            customerName = "Laurel Berge", active = true, balance = 40, redemptionRate = 0.0001, minPointsRedeem = 10,
            balanceValue = 0.0, maxRedeemablePoints = 0, maxRedeemableValue = 0.0,
        )
        assertEquals(
            "Los 40 puntos de Laurel Berge valen $0.00: no alcanzan para descontar nada.",
            motivoSinRecompensas(true, l, null),
        )
        assertEquals(
            "Los 40 puntos del cliente valen $0.00: no alcanzan para descontar nada.",
            motivoSinRecompensas(true, l.copy(customerName = null), null),
        )
    }

    @Test
    fun `una combinación que el SERVIDOR de hoy no manda - un texto que no inventa la causa`() {
        val l = lealtad(balance = 500, min = 10, max = 5).copy(maxRedeemableValue = 0.5)
        assertEquals("Laurel Berge tiene 500 puntos, pero en esta cuenta no se pueden canjear.", motivoSinRecompensas(true, l, null))
    }

    @Test
    fun `la cuenta no ha llegado del servidor (no se sabe si tiene cliente) - no dice «agrega el cliente» (Codex android r3 2)`() {
        assertEquals(
            "La cuenta todavía no llega del servidor, así que no se sabe si tiene cliente. Intenta en un momento.",
            motivoSinRecompensas(tieneCliente = null, lealtad = null, fallo = null),
        )
    }

    @Test
    fun `cuenta con abono, pagada o sin nada que descontar - no dice que falta el cliente`() {
        assertEquals(
            "En esta cuenta ya no se pueden canjear puntos: tiene un pago registrado o no queda nada que descontar.",
            motivoSinRecompensas(true, lealtad(balance = 59400, max = 0), null),
        )
    }
}
