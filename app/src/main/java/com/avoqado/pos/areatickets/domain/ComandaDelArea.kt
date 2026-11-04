package com.avoqado.pos.areatickets.domain

import com.avoqado.pos.areatickets.data.AreaTicketSettingsData

/**
 * D2 de vales (La Galeterie, 3-nov-2026): «Manda comanda a cocina» del área que ESTE aparato emite, en «Impresoras y
 * cajón». Es del área (vale para todos sus aparatos) y se guarda en el servidor por `PUT …/fulfillment-areas/:id/comanda`,
 * aparte del papel del aparato (Codex r1: un guardado no pisa al otro). Espejo futuro: iOS (`docs/proyectos/ios-por-hacer.md`).
 */
data class ComandaDelArea(val areaId: String, val areaNombre: String, val manda: Boolean)

/** null = no se enseña: el aparato no emite vales de un área, los vales están apagados o el SERVIDOR no manda el dato. */
fun comandaDelArea(ajustes: AreaTicketSettingsData): ComandaDelArea? {
    val area = ajustes.terminal.fulfillmentArea ?: return null
    val manda = area.sendsComanda ?: return null
    if (!ajustes.areaTickets.enabled || !ajustes.terminal.canIssueAreaTickets) return null
    return ComandaDelArea(areaId = area.id, areaNombre = area.name, manda = manda)
}

/** Por qué no se puede cambiar ahora (null = sí se puede). Gana el permiso, luego la red. */
fun motivoParaNoCambiarComanda(puedeConfigurar: Boolean, hayRed: Boolean): String? = when {
    !puedeConfigurar -> "Pídele a tu administrador que lo cambie"
    !hayRed -> "Necesita internet para cambiarlo"
    else -> null
}
