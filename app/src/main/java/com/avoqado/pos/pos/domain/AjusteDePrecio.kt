package com.avoqado.pos.pos.domain

/**
 * D12 — el «Ajuste de precio» de un producto sólo BAJA el precio: viaja al servidor como descuento
 * (`adjustedUnitPrice`) y subirlo a mano no cobraría nada de más (el servidor lo ignora). Para cobrar más está el
 * producto de precio libre. Mismo texto y misma regla que iOS.
 */
const val MENSAJE_SUBIR_PRECIO = "Para cobrar más, usa un producto de precio libre."

/** `true` sólo si el precio nuevo es mayor a cero y no pasa del original (sin extras; por kg en venta por peso). */
fun ajusteDePrecioPermitido(nuevoCents: Int, originalCents: Int): Boolean = nuevoCents in 1..originalCents

/**
 * Lo que se guarda en la línea al confirmar: `null` si el precio escrito es igual al original (no descuenta nada, y una
 * línea «ajustada» sin descuento bloquearía cosas como los vales), el valor si es más bajo. Se llama sólo cuando
 * [ajusteDePrecioPermitido] es `true`.
 */
fun ajusteAGuardar(nuevoCents: Int, originalCents: Int): Int? = nuevoCents.takeIf { it < originalCents }

/**
 * Encargo 1afe04 (founder «A», 15-nov, como Toast): qué hace la fila «Ajuste de precio» del detalle del producto. Bajar un precio a
 * mano pide «Aplicar descuentos en TPV» (de fábrica gerente, administrador y dueño) o el PIN de un encargado al cobrar.
 */
enum class AccesoAlAjuste {
    /** Tiene el permiso: lo aplica directo. */
    LIBRE,

    /** Sin el permiso y con el PIN de encargado prendido: se puede poner; al cobrar se pide el PIN. */
    CON_PIN,

    /** Sin el permiso y con el PIN apagado: no se puede poner (la fila se ve y dice por qué). */
    LO_APLICA_UN_GERENTE,

    /** Mesas: la cuenta abierta no guarda ajustes de precio (se tiraban en silencio): la fila no sale. */
    NO_APLICA,
}

fun accesoAlAjuste(puedeAprobar: Boolean, pinPrendido: Boolean): AccesoAlAjuste = when {
    puedeAprobar -> AccesoAlAjuste.LIBRE
    pinPrendido -> AccesoAlAjuste.CON_PIN
    else -> AccesoAlAjuste.LO_APLICA_UN_GERENTE
}

/** Lo que dice la fila cuando todavía no hay ajuste (con ajuste, dice el precio). `null` = nada. */
fun avisoDeLaFilaDeAjuste(acceso: AccesoAlAjuste): String? = when (acceso) {
    AccesoAlAjuste.LIBRE, AccesoAlAjuste.NO_APLICA -> null
    AccesoAlAjuste.CON_PIN -> "Lo autoriza un encargado con su PIN al cobrar"
    AccesoAlAjuste.LO_APLICA_UN_GERENTE -> "Lo aplica un gerente con su sesión"
}
