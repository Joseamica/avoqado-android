package com.avoqado.pos.printing.presentation

import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import java.net.URLEncoder

/**
 * La pantalla «Impresoras y cajón» separa lo que es de ESTA caja (la de recibos, con su cajón) de lo que es del
 * local (las de cocina y bebidas del panel). Nació en La Galeterie (8-oct): la segunda caja enseñaba en verde las de
 * cocina, el cajero la creyó lista, y no tenía impresora de recibos ni abría el cajón. Espejo de iOS.
 */

/** Las de recibos de esta caja: por ellas sale el ticket y se abre el cajón. */
internal fun impresorasDeRecibos(guardadas: List<SavedPrinter>): List<SavedPrinter> =
    guardadas.filter { PrinterRole.RECEIPT in it.roleEnums }

/** Las demás guardadas en esta caja (comandas o etiquetas por USB, Bluetooth o red). */
internal fun otrasDeEstaCaja(guardadas: List<SavedPrinter>): List<SavedPrinter> =
    guardadas.filter { PrinterRole.RECEIPT !in it.roleEnums }

/**
 * Cómo se llama este aparato en el panel («Avoqado Windows 10 (2)»), o null si no se sabe: primera vez sin red, un
 * servidor que no lo manda, o una terminal sin nombre. Con null la pantalla dice «esta computadora» / «este aparato»:
 * nunca se inventa un nombre ni se toma el de otra terminal.
 */
internal fun nombreDelAparato(nombre: String?): String? = nombre?.trim()?.takeIf { it.isNotEmpty() }

/** «esta computadora» en Windows; «este aparato» en Android (tableta o celular). */
internal fun esteAparato(esEscritorio: Boolean): String = if (esEscritorio) "esta computadora" else "este aparato"

internal const val DASHBOARD_URL = "https://dashboard.avoqado.io"

/**
 * La pantalla del panel donde se editan las impresoras del local, o null si no hay sucursal. Quien NO puede editarlas
 * (cajero) no recibe liga: ve «pídele a tu gerente», en vez de una página que le diga que no tiene permiso.
 */
internal fun ligaDeImpresorasDelPanel(venueSlug: String?, puedeEditar: Boolean): String? {
    if (!puedeEditar) return null
    val slug = venueSlug?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "$DASHBOARD_URL/venues/${URLEncoder.encode(slug, "UTF-8")}/print-stations"
}
