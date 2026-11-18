package com.avoqado.pos.payment.presentation

import com.avoqado.pos.payment.presentation.PaymentFlowViewModel.TerminalAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tarjeta de la nube na8a23ce (encargo 0199df, visto en la SAMSUNG el 15-nov): venta de mostrador → sin red → Cobrar. D68 #12 dejó de
 * pintar «No hay terminales conectadas» sin red, así que la pantalla le pasa al subtítulo `terminalsUnavailable = false`; y como no es
 * una mesa, `soloEfectivoSinRed = false`. El subtítulo caía al «Envía el cobro a una terminal para procesar el pago», que sin red no se
 * puede. Ahora la pantalla también le pasa `sinRed` ([PaymentFlowViewModel.sinRedEnElCobro]).
 *
 * En `develop` (commit de sólo pruebas) esta prueba llamaba sin `sinRed`, que es lo que la pantalla le pasaba: salía roja.
 */
class SubtituloDelCobroSinRedTest {
    // No promete efectivo (Codex r1 #3, r2 #6): un cupón o un descuento con aprobación no se cobran sin red; el efectivo dice su motivo.
    @Test fun `P2 sin red en mostrador el subtitulo no manda a una terminal`() {
        for (sonda in TerminalAvailability.values()) {
            // Lo que `PaymentFlowScreen` le pasa sin red, sea cual sea la sonda de terminales.
            val texto = subtituloDelCobro(
                terminalsUnavailable = terminalesNoConectadasCon(sonda, enLinea = false),
                metodosHabilitados = true,
                soloEfectivoSinRed = false,
                sinRed = true,
            )
            assertFalse("sonda $sonda: «$texto»", texto.startsWith("Envía el cobro a una terminal"))
            assertEquals("Sin conexión: el cobro con terminal necesita internet", texto)
        }
    }

    @Test fun `la mesa cobrada sin internet conserva su texto y con red todo como antes`() {
        assertEquals("Cobra en efectivo", subtituloDelCobro(false, metodosHabilitados = true, soloEfectivoSinRed = true, sinRed = true))
        assertEquals("Cobra en efectivo o conecta una terminal", subtituloDelCobro(true, metodosHabilitados = true, soloEfectivoSinRed = false, sinRed = false))
        assertEquals("Envía el cobro a una terminal para procesar el pago", subtituloDelCobro(false, metodosHabilitados = true, soloEfectivoSinRed = false, sinRed = false))
    }

    @Test fun `con los metodos apagados el subtitulo no promete efectivo`() {
        // Codex r3 #3: la cuenta de tarjetas de regalo sin red apaga los métodos; el subtítulo tampoco manda a la terminal.
        assertEquals(SUBTITULO_SIN_RED, subtituloDelCobro(false, metodosHabilitados = false, soloEfectivoSinRed = false, sinRed = true))
        assertFalse(SUBTITULO_SIN_RED.contains("efectivo"))
    }
}
