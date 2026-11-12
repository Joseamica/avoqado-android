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
    ) = CustomerLoyalty(
        customerName = "Laurel Berge",
        active = active,
        balance = balance,
        minPointsRedeem = min,
        maxRedeemablePoints = max,
        canRedeem = canRedeem,
    )

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
    fun `cuenta con abono, pagada o sin nada que descontar - no dice que falta el cliente`() {
        assertEquals(
            "En esta cuenta ya no se pueden canjear puntos: tiene un pago registrado o no queda nada que descontar.",
            motivoSinRecompensas(true, lealtad(balance = 59400, max = 0), null),
        )
    }
}
