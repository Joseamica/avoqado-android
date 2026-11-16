package com.avoqado.pos.payment

import com.avoqado.pos.payment.domain.AvisoDeTerminalDeAvoqado
import com.avoqado.pos.payment.domain.ManualPaymentChoice
import com.avoqado.pos.payment.domain.ManualPaymentMethod
import com.avoqado.pos.payment.domain.TenderTypeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D57 (opción A): cuándo «Ya pagó de otra forma» pregunta si fue una terminal de Avoqado antes de registrar. */
class AvisoDeTerminalDeAvoqadoTest {
    private fun tipo(nombre: String, base: String, sistema: Boolean = true) = ManualPaymentChoice.Tender(
        TenderTypeOption(id = nombre, revision = 1, name = nombre, isSystem = sistema, baseMethod = base, captureTip = true,
            posSection = "PRIMARY", displayOrder = 0),
    )

    @Test fun `P1 la tarjeta de credito y de debito del negocio piden el aviso (el caso 9 del 12-nov)`() {
        assertTrue(AvisoDeTerminalDeAvoqado.aplica(tipo("Tarjeta de crédito", "CREDIT_CARD")))
        assertTrue(AvisoDeTerminalDeAvoqado.aplica(tipo("Tarjeta de débito", "DEBIT_CARD")))
    }

    @Test fun `P1 Tarjeta (otra terminal) pide el aviso`() {
        assertTrue(AvisoDeTerminalDeAvoqado.aplica(ManualPaymentChoice.Fixed(ManualPaymentMethod.CARD_EXTERNAL)))
    }

    @Test fun `lo que no es tarjeta se registra como siempre, sin aviso`() {
        assertFalse(AvisoDeTerminalDeAvoqado.aplica(tipo("Efectivo", "CASH")))
        assertFalse(AvisoDeTerminalDeAvoqado.aplica(tipo("Transferencia", "BANK_TRANSFER")))
        assertFalse(AvisoDeTerminalDeAvoqado.aplica(ManualPaymentChoice.Fixed(ManualPaymentMethod.TRANSFER)))
        assertFalse(AvisoDeTerminalDeAvoqado.aplica(ManualPaymentChoice.Fixed(ManualPaymentMethod.OTHER)))
    }

    @Test fun `un tipo propio del negocio (Terminal BBVA, siempre OTHER) ya dice que no es de Avoqado - sin aviso`() {
        assertFalse(AvisoDeTerminalDeAvoqado.aplica(tipo("Terminal BBVA", "OTHER", sistema = false)))
    }

    @Test fun `el boton de registrar dice el monto`() {
        assertEquals("Fue otra terminal: registrar \$25.00", AvisoDeTerminalDeAvoqado.registrar("\$25.00"))
    }
}
