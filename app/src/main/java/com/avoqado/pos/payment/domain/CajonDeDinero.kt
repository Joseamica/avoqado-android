package com.avoqado.pos.payment.domain

import com.avoqado.pos.payment.data.model.PaymentMethod

/**
 * ¿Se abre el cajón de dinero al terminar este cobro?
 *
 * Sólo cuando entró dinero físico al cajón:
 * - Los pagos declarados a mano (terminal ajena, transferencia) viajan por la rama de efectivo
 *   del ViewModel, pero no meten billetes: no abren.
 * - Un tipo del catálogo abre si el server dice que entra al cajón (`opensCashDrawer`, que sale
 *   de `countsAsPhysicalCash`: un vale sí, Uber Eats no). Con un server viejo que no manda el
 *   dato, sólo abre el tipo de SISTEMA con base CASH — el lado seguro.
 * - Un cobro en $0 (cortesía total) no movió dinero: no abre.
 *
 * Espejo exacto en avoqado-ios: `Payment/CajonDeDinero.swift`.
 */
object CajonDeDinero {
    fun debeAbrirse(
        method: PaymentMethod?,
        manualMethod: ManualPaymentMethod?,
        tender: TenderTypeOption?,
        montoCents: Int,
    ): Boolean {
        if (method != PaymentMethod.CASH) return false
        if (manualMethod != null) return false
        if (montoCents <= 0) return false
        if (tender == null) return true
        return tender.opensCashDrawer ?: (tender.isSystem && tender.baseMethod == PaymentMethod.CASH.name)
    }
}
