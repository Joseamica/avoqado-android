package com.avoqado.pos.areatickets.domain

import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities

/**
 * D3 (2-nov-2026): qué hace este aparato en los vales por área. Es lo que ya guarda la terminal en el
 * servidor; la app sólo lo lee y lo cambia por `PUT …/terminals/:id/area-role`. Espejo: avoqado-ios `PapelDelAparato`.
 *
 * La hoja sólo ofrece [Cobra] y [EmiteVales] sin cobro: un aparato que emite siempre emite (su botón no cobra vales
 * escaneados, Codex r1). [EmiteVales.tambienCobra], [Entrega] y [SinElegir] existen para MOSTRAR lo que el panel puso.
 */
sealed interface PapelDelAparato {
    data object Cobra : PapelDelAparato
    data class EmiteVales(val areaId: String, val areaNombre: String, val tambienCobra: Boolean = false) : PapelDelAparato
    data object Entrega : PapelDelAparato
    data object SinElegir : PapelDelAparato
}

fun papelActual(terminal: AreaTicketTerminalCapabilities): PapelDelAparato {
    val area = terminal.fulfillmentArea
    return when {
        terminal.canIssueAreaTickets && area != null ->
            PapelDelAparato.EmiteVales(area.id, area.name, tambienCobra = terminal.canCheckoutAreaTickets)
        terminal.canCheckoutAreaTickets -> PapelDelAparato.Cobra
        terminal.canDeliverAreaTickets -> PapelDelAparato.Entrega
        else -> PapelDelAparato.SinElegir
    }
}

/** El valor del recuadro «Vales» del encabezado de «Más». */
fun etiquetaDelRecuadro(papel: PapelDelAparato): String = when (papel) {
    PapelDelAparato.Cobra -> "Cobra (caja)"
    is PapelDelAparato.EmiteVales -> if (papel.tambienCobra) "Emite · ${papel.areaNombre} y cobra" else "Emite · ${papel.areaNombre}"
    PapelDelAparato.Entrega -> "Entrega"
    PapelDelAparato.SinElegir -> "Sin elegir"
}

/** El mismo papel aunque el panel haya renombrado el área: se compara por su id, no por su nombre (Codex r7). Igual en iOS. */
fun mismoPapel(a: PapelDelAparato, b: PapelDelAparato): Boolean = when {
    a is PapelDelAparato.EmiteVales && b is PapelDelAparato.EmiteVales -> a.areaId == b.areaId && a.tambienCobra == b.tambienCobra
    else -> a == b
}

/** «Guardar» encontró que el papel cambió en el panel desde que se abrió la hoja (Codex r6). Igual en iOS. */
fun textoCambioEnElPanel(papel: PapelDelAparato): String =
    "En el panel lo cambiaron a «${etiquetaDelRecuadro(papel)}». Revisa y vuelve a guardar."

/** Por qué no se puede guardar ahora (null = sí se puede). Gana el permiso, luego la red, luego la venta a medias. */
fun motivoParaNoGuardar(puedeConfigurar: Boolean, hayRed: Boolean, ventaAMedias: Boolean): String? = when {
    !puedeConfigurar -> "Pídele a tu administrador que lo configure"
    !hayRed -> "Necesita internet para cambiarlo"
    ventaAMedias -> "Termina la venta antes de cambiar"
    else -> null
}

/**
 * 🔴 Cambiar el papel con algo a medio vender lo dejaría en una pantalla que ya no lo atiende. En la app, las cinco
 * señales son `ActiveCartState.hasItems`, `AreaTicketSession.checkout.value != null`, `areaTicketCheckoutId != null`,
 * `pendingAreaTicketPrintCode != null` y `valeSinConfirmar(venueId) != null`.
 */
fun ventaAMedias(
    carritoConProductos: Boolean,
    cobroDeValesAbierto: Boolean,
    cobroDeValesGuardado: Boolean,
    valePorImprimir: Boolean,
    valeSinConfirmar: Boolean,
): Boolean = carritoConProductos || cobroDeValesAbierto || cobroDeValesGuardado || valePorImprimir || valeSinConfirmar
