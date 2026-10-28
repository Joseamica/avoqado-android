package com.avoqado.pos.giftcards.domain

import com.avoqado.pos.giftcards.data.EstadoDeAjustes

data class AccionDeHoja(val visible: Boolean, val habilitada: Boolean, val motivo: String? = null, val etiquetaDePlan: String? = null)
data class EstadoDeHoja(val mosaicoVisible: Boolean, val vender: AccionDeHoja, val consultar: AccionDeHoja)

fun estadoDeLaHoja(
    ajustes: EstadoDeAjustes,
    enLinea: Boolean,
    puedeLeer: Boolean,
    puedeVender: Boolean,
    ventaVacia: Boolean,
    etiquetaPro: String?,
): EstadoDeHoja {
    val oculta = AccionDeHoja(visible = false, habilitada = false)
    if (ajustes == EstadoDeAjustes.NoDisponible) return EstadoDeHoja(false, oculta, oculta)
    val consultar = when {
        !enLinea -> AccionDeHoja(true, false, TextosDeTarjetas.SIN_RED)
        ajustes == EstadoDeAjustes.SinPermiso || !puedeLeer -> AccionDeHoja(true, false, TextosDeTarjetas.SIN_PERMISO_CONSULTAR)
        else -> AccionDeHoja(true, true)
    }
    val disponibles = (ajustes as? EstadoDeAjustes.Disponible)?.ajustes
    val vender = when {
        ajustes != EstadoDeAjustes.SinPermiso && disponibles?.vende != true -> oculta
        !enLinea -> AccionDeHoja(true, false, TextosDeTarjetas.SIN_RED)
        ajustes == EstadoDeAjustes.SinPermiso || !puedeVender -> AccionDeHoja(true, false, TextosDeTarjetas.SIN_PERMISO_VENDER)
        disponibles?.sale?.sellable == false -> AccionDeHoja(true, false, disponibles.sale.message.orEmpty(), etiquetaPro.takeIf { disponibles.sale.howToEnable == "PLAN" })
        !ventaVacia -> AccionDeHoja(true, false, TextosDeTarjetas.VENTA_CON_ARTICULOS)
        else -> AccionDeHoja(true, true)
    }
    return EstadoDeHoja(true, vender, consultar)
}
