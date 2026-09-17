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
 * porque los dos viajan por la rama de efectivo del ViewModel. Auditoría de Codex (mismo día):
 * un tipo personalizado que SÍ entra al cajón (vale) también debe abrirlo, y un cobro en $0
 * (cortesía total) no.
 */
class CajonDeDineroTest {

    private fun tender(name: String, isSystem: Boolean, baseMethod: String, opensCashDrawer: Boolean? = null) =
        TenderTypeOption(
            id = "t-$name", revision = 1, name = name, isSystem = isSystem,
            baseMethod = baseMethod, captureTip = true, posSection = "PRIMARY", displayOrder = 0,
            opensCashDrawer = opensCashDrawer,
        )

    private fun abre(
        method: PaymentMethod? = PaymentMethod.CASH,
        manual: ManualPaymentMethod? = null,
        tender: TenderTypeOption? = null,
        montoCents: Int = 1000,
    ) = CajonDeDinero.debeAbrirse(method, manual, tender, montoCents)

    @Test
    fun `efectivo de verdad abre el cajon`() = assertTrue(abre())

    @Test
    fun `tarjeta no abre el cajon`() = assertFalse(abre(method = PaymentMethod.CARD))

    @Test
    fun `sin metodo no abre el cajon`() = assertFalse(abre(method = null))

    @Test
    fun `un cobro en cero no abre el cajon`() {
        assertFalse(abre(montoCents = 0))
        assertFalse(abre(montoCents = -5))
    }

    @Test
    fun `un pago declarado a mano no abre el cajon aunque viaje por la rama de efectivo`() {
        ManualPaymentMethod.entries.forEach { declarado ->
            assertFalse(declarado.name, abre(manual = declarado))
        }
    }

    @Test
    fun `un tipo personalizado que no entra al cajon no lo abre`() {
        assertFalse(abre(tender = tender("Uber Eats", isSystem = false, baseMethod = "OTHER", opensCashDrawer = false)))
    }

    @Test
    fun `un tipo personalizado que si entra al cajon lo abre`() {
        assertTrue(abre(tender = tender("Vale", isSystem = false, baseMethod = "OTHER", opensCashDrawer = true)))
    }

    @Test
    fun `lo que diga el server manda sobre el tipo de sistema`() {
        assertFalse(abre(tender = tender("Efectivo", isSystem = true, baseMethod = "CASH", opensCashDrawer = false)))
    }

    @Test
    fun `server viejo sin el dato - el tipo de sistema Efectivo abre y los demas no`() {
        assertTrue(abre(tender = tender("Efectivo", isSystem = true, baseMethod = "CASH")))
        assertFalse(abre(tender = tender("Transferencia", isSystem = true, baseMethod = "BANK_TRANSFER")))
        assertFalse(abre(tender = tender("Vale", isSystem = false, baseMethod = "OTHER")))
    }
}
