package com.avoqado.pos.payment

import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import com.avoqado.pos.payment.domain.ProbeDecision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D58 (12-nov, regresión de tarjeta, caso 3): al cancelar en la terminal la D3 decía «Error en el pago» en rojo, aunque el texto
 * ya era «El cobro se canceló. No se cobró la tarjeta.». Una cancelación, una terminal que se liberó sin cobro o un «no se cobró»
 * que confirmó un cajero no son errores: se pintan neutros («No se cobró», gris). Un rechazo del banco sigue en rojo.
 */
class NoSeCobroNoEsErrorTest {

    private fun desenlace(status: String, evidencia: String? = null, disposicion: String? = null): CardChargeOutcome.NotCharged {
        val r = CardChargeDecision.decide(
            ChargeStatusProbe.Known(status = status, inProgress = false, outcome = "NOT_CHARGED", outcomeEvidence = evidencia, cancelDisposition = disposicion),
            isFinalAttempt = true,
        )
        return (r as ProbeDecision.Resolved).outcome as CardChargeOutcome.NotCharged
    }

    @Test
    fun `cancelar en la terminal no es un error`() {
        assertTrue(desenlace("CANCELLED", disposicion = "ACCEPTED").sinCobroNiRechazo)
    }

    @Test
    fun `la terminal que se libero sin cobro y el no se cobro de un cajero tampoco`() {
        assertTrue(desenlace("FAILED", evidencia = "NO_EVIDENCE_AFTER_WINDOW").sinCobroNiRechazo)
        assertTrue(desenlace("FAILED", evidencia = "OPERATOR_RECONCILED").sinCobroNiRechazo)
        assertTrue(CardChargeDecision.staleOutcome(CardChargeOutcome.Undetermined("x"), verdictoNoSeCobro = true, cobroYaAplicadoAEstaVenta = false).let { (it as CardChargeOutcome.NotCharged).sinCobroNiRechazo })
    }

    @Test
    fun `un rechazo del banco o un fallo de la terminal siguen siendo error`() {
        assertFalse(desenlace("FAILED", evidencia = "PROCESSOR_DECLINED").sinCobroNiRechazo)
        assertFalse(desenlace("FAILED").sinCobroNiRechazo)
    }
}
