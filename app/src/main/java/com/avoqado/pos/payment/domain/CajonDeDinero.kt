package com.avoqado.pos.payment.domain

import com.avoqado.pos.payment.data.model.PaymentMethod

/**
 * ¿Se abre el cajón de dinero al terminar este cobro?
 *
 * Sólo cuando entró EFECTIVO de verdad. Los pagos declarados a mano (terminal ajena,
 * transferencia) y los tipos del catálogo del negocio viajan por la rama de efectivo del
 * ViewModel, pero no meten billetes al cajón: abrirlo ahí es dejarlo abierto sin motivo.
 *
 * Del catálogo sólo abre el tipo de SISTEMA con base CASH. Un tipo personalizado que sí
 * entra al cajón (vale de despensa, `countsAsPhysicalCash` en el servidor) hoy NO lo abre:
 * la app no recibe ese dato. Es el lado seguro — el cajero lo abre con su botón.
 */
object CajonDeDinero {
    fun debeAbrirse(
        method: PaymentMethod?,
        manualMethod: ManualPaymentMethod?,
        tender: TenderTypeOption?,
    ): Boolean {
        if (method != PaymentMethod.CASH) return false
        if (manualMethod != null) return false
        if (tender == null) return true
        return tender.isSystem && tender.baseMethod == PaymentMethod.CASH.name
    }
}
