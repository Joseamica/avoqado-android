package com.avoqado.pos.printing.data.model

/** Los renglones de dinero del papel, en centavos. Siempre: subtotal − descuento + cargo + impuesto + propina = total. */
internal data class TotalesDelPapel(val subtotal: Int, val descuento: Int, val cargo: Int, val impuesto: Int, val total: Int)

/**
 * Regla de cuadre del ticket del COBRO (IVA por producto, plan 7; Codex r1 #3). Lo cobrado manda: el subtotal es la suma bruta
 * de los renglones que imprime el ticket y el descuento es lo que falta para llegar a lo que el SERVIDOR cobró (promociones y
 * premios que confirmó él). Si lo cobrado supera el bruto, el subtotal pasa a ser lo cobrado y el descuento 0. Espejo exacto de
 * `TotalesDelTicket.desdeLoCobrado` en iOS (Tarea 14). En el 7a el cobro pasa siempre `cargo = 0` (una mesa con cargo queda en
 * renglón único, como hoy); el 7b pasará el cargo de una cuenta comprobada.
 */
internal object TotalesDelTicket {
    fun desdeLoCobrado(cobrado: Int, propina: Int, subtotalBruto: Int, cargo: Int, impuestoAgregado: Int): TotalesDelPapel {
        val c = cargo.coerceAtLeast(0)
        val impuesto = impuestoAgregado.coerceAtLeast(0)
        val total = cobrado + propina
        if (cobrado < c + impuesto) return TotalesDelPapel(subtotal = cobrado, descuento = 0, cargo = 0, impuesto = 0, total = total)
        val descuento = subtotalBruto + c + impuesto - cobrado
        return if (descuento >= 0) TotalesDelPapel(subtotalBruto, descuento, c, impuesto, total)
        else TotalesDelPapel(cobrado - c - impuesto, 0, c, impuesto, total)
    }
}
