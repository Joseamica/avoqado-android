package com.avoqado.pos.payment

import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.domain.CajonDeDinero
import com.avoqado.pos.payment.domain.ManualPaymentMethod
import com.avoqado.pos.payment.domain.TenderTypeOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuándo se abre el cajón de dinero al terminar un cobro.
 *
 * Nació en una Sunmi D3 con un cajón NC010 (2026-09-17): un cobro en efectivo tecleado
 * (sin productos) NO abría el cajón, y un pago declarado a mano (transferencia) SÍ lo abría,
 * porque los dos viajan por la rama de efectivo del ViewModel.
 */
class CajonDeDineroTest {

    private fun tender(name: String, isSystem: Boolean, baseMethod: String) = TenderTypeOption(
        id = "t-$name", revision = 1, name = name, isSystem = isSystem,
        baseMethod = baseMethod, captureTip = true, posSection = "PRIMARY", displayOrder = 0,
    )

    @Test
    fun `efectivo de verdad abre el cajon`() {
        assertTrue(CajonDeDinero.debeAbrirse(PaymentMethod.CASH, manualMethod = null, tender = null))
    }

    @Test
    fun `tarjeta no abre el cajon`() {
        assertFalse(CajonDeDinero.debeAbrirse(PaymentMethod.CARD, manualMethod = null, tender = null))
    }

    @Test
    fun `sin metodo no abre el cajon`() {
        assertFalse(CajonDeDinero.debeAbrirse(null, manualMethod = null, tender = null))
    }

    @Test
    fun `un pago declarado a mano no abre el cajon aunque viaje por la rama de efectivo`() {
        ManualPaymentMethod.entries.forEach { declarado ->
            assertFalse(declarado.name, CajonDeDinero.debeAbrirse(PaymentMethod.CASH, declarado, tender = null))
        }
    }

    @Test
    fun `un tipo personalizado del catalogo no abre el cajon`() {
        val uber = tender("Uber Eats", isSystem = false, baseMethod = "OTHER")
        assertFalse(CajonDeDinero.debeAbrirse(PaymentMethod.CASH, manualMethod = null, tender = uber))
    }

    @Test
    fun `el tipo de sistema Efectivo si abre el cajon`() {
        val efectivo = tender("Efectivo", isSystem = true, baseMethod = "CASH")
        assertTrue(CajonDeDinero.debeAbrirse(PaymentMethod.CASH, manualMethod = null, tender = efectivo))
    }

    @Test
    fun `el tipo de sistema Transferencia no abre el cajon`() {
        val transferencia = tender("Transferencia", isSystem = true, baseMethod = "BANK_TRANSFER")
        assertFalse(CajonDeDinero.debeAbrirse(PaymentMethod.CASH, manualMethod = null, tender = transferencia))
    }
}
