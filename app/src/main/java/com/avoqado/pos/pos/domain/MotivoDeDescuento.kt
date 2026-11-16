package com.avoqado.pos.pos.domain

/**
 * Motivo obligatorio en los descuentos del POS (encargo f998c8, founder «A» 15-nov, como Toast «Discount reason required»).
 *
 * El negocio lo prende en el DASHBOARD con su lista de motivos; la app la guarda con los ajustes del aparato (sirve sin red). Prendido,
 * quien pone A MANO un descuento de catálogo (de producto, de la cuenta en Mostrador o de una mesa) o un «Ajuste de precio» elige un
 * motivo de la lista y puede escribir un comentario. No aplica a lo que nadie elige a mano: el cupón, el descuento del cliente que se
 * pone solo, los referidos y los premios, ni a la cortesía (que ya trae su propio motivo).
 *
 * El SERVIDOR nunca rechaza una venta sin motivo (apps viejas y la cola): quien lo exige es esta app.
 */
data class MotivosDeDescuento(val requerido: Boolean, val lista: List<String>) {
    /** Se pide sólo si está prendido y hay de dónde elegir (el DASHBOARD no deja prenderlo con la lista vacía). */
    val sePide: Boolean get() = requerido && lista.isNotEmpty()

    companion object {
        val APAGADO = MotivosDeDescuento(false, emptyList())
        const val MAX_COMENTARIO = 200
    }
}

/** El motivo que eligió la caja y su comentario (opcional, recortado). */
data class MotivoElegido(val motivo: String, val comentario: String? = null) {
    companion object {
        /** Lo que viaja: el comentario vacío no viaja, y el texto se recorta al tope del SERVIDOR. */
        fun de(motivo: String, comentario: String?): MotivoElegido =
            MotivoElegido(
                motivo = motivo.trim(),
                comentario = comentario?.trim()?.take(MotivosDeDescuento.MAX_COMENTARIO)?.trim()?.takeIf { it.isNotEmpty() },
            )
    }
}
