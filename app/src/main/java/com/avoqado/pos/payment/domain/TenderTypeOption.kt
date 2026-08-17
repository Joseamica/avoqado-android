package com.avoqado.pos.payment.domain

/**
 * Tipo de pago del catálogo del negocio ("Uber Eats", "Terminal BBVA", "Vale de
 * despensa"), bajado del server con `GET /mobile/venues/{venueId}/tender-types`.
 *
 * 🔴 Sólo viaja la REFERENCIA `{id, revision}`. La comisión, el "¿entra al cajón?"
 * y la forma SAT NUNCA viven aquí: las resuelve el server desde su historial al
 * registrar el cobro. Un POS con bug no puede inventarse una comisión.
 *
 * `revision` se manda tal cual al cobrar. Si el dueño edita el tipo mientras esta
 * tablet está sin red, el server honra la revisión que el cajero tenía enfrente
 * (la venta ya ocurrió); con red, exige la vigente y el POS refresca.
 */
data class TenderTypeOption(
    val id: String,
    val revision: Int,
    val name: String,
    val isSystem: Boolean,
    val baseMethod: String,
    /** Si es false, el POS NO debe pedir propina con este tipo (el server rechaza tip>0). */
    val captureTip: Boolean,
    /** PRIMARY = primer nivel al cobrar; MORE = detrás de "Más". Espejo de Square. */
    val posSection: String,
    val displayOrder: Int,
)

/**
 * Lo que el cajero eligió en "¿cómo pagó el cliente?".
 *
 * Coexisten a propósito: las opciones fijas de siempre siguen sirviendo a los
 * negocios que no dieron de alta ningún tipo, y las del catálogo las añade el
 * dueño desde el dashboard.
 */
sealed interface ManualPaymentChoice {
    /** Las 3 de siempre (Tarjeta otra terminal / Transferencia / Otro medio). */
    data class Fixed(val method: ManualPaymentMethod) : ManualPaymentChoice

    /** Un tipo del catálogo del negocio. */
    data class Tender(val option: TenderTypeOption) : ManualPaymentChoice
}
