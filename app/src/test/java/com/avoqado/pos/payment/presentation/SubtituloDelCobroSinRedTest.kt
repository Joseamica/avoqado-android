package com.avoqado.pos.payment.presentation

import com.avoqado.pos.payment.presentation.PaymentFlowViewModel.TerminalAvailability
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tarjeta de la nube na8a23ce (encargo 0199df, visto en la SAMSUNG el 15-nov): venta de mostrador → sin red → Cobrar. D68 #12 dejó de
 * pintar «No hay terminales conectadas» sin red, así que la pantalla le pasa al subtítulo `terminalsUnavailable = false`; y como no es
 * una mesa, `soloEfectivoSinRed = false`. El subtítulo caía al «Envía el cobro a una terminal para procesar el pago», que sin red no se
 * puede.
 */
class SubtituloDelCobroSinRedTest {
    @Test fun `P2 sin red en mostrador el subtitulo no manda a una terminal`() {
        for (sonda in TerminalAvailability.values()) {
            // Lo que `PaymentFlowScreen` le pasa hoy sin red, sea cual sea la sonda de terminales.
            val texto = subtituloDelCobro(
                terminalsUnavailable = terminalesNoConectadasCon(sonda, enLinea = false),
                metodosHabilitados = true,
                soloEfectivoSinRed = false,
            )
            assertFalse("sonda $sonda: «$texto»", texto.contains("terminal", ignoreCase = true))
        }
    }
}
