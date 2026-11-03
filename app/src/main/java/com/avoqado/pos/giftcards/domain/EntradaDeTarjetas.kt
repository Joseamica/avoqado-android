package com.avoqado.pos.giftcards.domain

import com.avoqado.pos.giftcards.data.EstadoDeAjustes
import com.avoqado.pos.giftcards.data.FalloDeTarjeta

data class AccionDeHoja(val visible: Boolean, val habilitada: Boolean, val motivo: String? = null, val etiquetaDePlan: String? = null)
data class EstadoDeHoja(
    val mosaicoVisible: Boolean, val vender: AccionDeHoja, val consultar: AccionDeHoja,
    val prender: AccionDeHoja = AccionDeHoja(visible = false, habilitada = false),
)

/** Por qué no se prendieron. Un 403 sin código es de permiso y trae un texto en inglés: nunca se enseña. */
fun motivoAlPrender(fallo: FalloDeTarjeta): String = when {
    fallo.http == 403 && fallo.code == null -> TextosDeTarjetas.SIN_PERMISO_PRENDER
    fallo.code != null && !fallo.message.isNullOrBlank() -> fallo.message
    else -> TextosDeTarjetas.PRENDER_RECHAZADO
}

fun estadoDeLaHoja(
    ajustes: EstadoDeAjustes,
    enLinea: Boolean,
    puedeLeer: Boolean,
    puedeVender: Boolean,
    ventaVacia: Boolean,
    etiquetaPro: String?,
): EstadoDeHoja {
    val oculta = AccionDeHoja(visible = false, habilitada = false)
    if (ajustes == EstadoDeAjustes.NoDisponible || ajustes == EstadoDeAjustes.Desconocido) return EstadoDeHoja(false, oculta, oculta)
    val consultar = when {
        !enLinea -> AccionDeHoja(true, false, TextosDeTarjetas.SIN_RED)
        ajustes == EstadoDeAjustes.SinPermiso || !puedeLeer -> AccionDeHoja(true, false, TextosDeTarjetas.SIN_PERMISO_CONSULTAR)
        else -> AccionDeHoja(true, true)
    }
    val disponibles = (ajustes as? EstadoDeAjustes.Disponible)?.ajustes
    val paraPrender = disponibles?.takeIf { !it.enabled }?.prender
    val vender = when {
        ajustes != EstadoDeAjustes.SinPermiso && disponibles?.vende != true -> oculta
        !enLinea -> AccionDeHoja(true, false, TextosDeTarjetas.SIN_RED)
        ajustes == EstadoDeAjustes.SinPermiso || !puedeVender -> AccionDeHoja(true, false, TextosDeTarjetas.SIN_PERMISO_VENDER)
        paraPrender != null && !paraPrender.allowed ->
            AccionDeHoja(true, false, paraPrender.message.orEmpty(), etiquetaPro.takeIf { paraPrender.howToEnable == "PLAN" })
        paraPrender != null ->
            AccionDeHoja(true, false, if (paraPrender.puedeEsteUsuario) TextosDeTarjetas.APAGADAS_PRENDELAS_AQUI else TextosDeTarjetas.APAGADAS_PIDELE_A_UN_ADMIN)
        disponibles?.sale?.sellable == false -> AccionDeHoja(true, false, disponibles.sale.message.orEmpty(), etiquetaPro.takeIf { disponibles.sale.howToEnable == "PLAN" })
        !ventaVacia -> AccionDeHoja(true, false, TextosDeTarjetas.VENTA_CON_ARTICULOS)
        else -> AccionDeHoja(true, true)
    }
    val prender = when {
        disponibles?.vende != true || paraPrender?.allowed != true || !paraPrender.puedeEsteUsuario -> oculta
        !enLinea -> AccionDeHoja(true, false, TextosDeTarjetas.SIN_RED)
        else -> AccionDeHoja(true, true)
    }
    return EstadoDeHoja(true, vender, consultar, prender)
}
