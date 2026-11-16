package com.avoqado.pos.payment.domain

/**
 * D57 (opción A, encargo 747343): antes de registrar a mano un cobro con TARJETA en «Ya pagó de otra forma», la tablet
 * pregunta si se cobró en una terminal de Avoqado.
 *
 * Por qué: medido el 12-nov (regresión de tarjeta, caso 9) — mesa en la tablet sin internet, el mesero cobra en la PAX con
 * «Pago rápido» (que crea su propia venta FAST) y luego registra la mesa con «Ya pagó de otra forma › Tarjeta de crédito».
 * Un solo cargo al banco y DOS ventas: el reporte, las comisiones y el corte de tarjeta suben el doble. El arreglo de fondo
 * (la terminal liga su cobro a la mesa) es la D9, estacionada; esto es la mitigación más chica: avisar en el momento.
 *
 * Sólo sale con tarjeta: los tipos del negocio con método base de tarjeta (`CREDIT_CARD`/`DEBIT_CARD`, los del sistema) y
 * «Tarjeta (otra terminal)». Un tipo propio del negocio («Terminal BBVA») es siempre `OTHER` y ya dice que no es de Avoqado:
 * no lo frena. Efectivo, transferencia y «Otro medio» tampoco.
 */
object AvisoDeTerminalDeAvoqado {
    const val TITULO = "¿Lo cobraste en una terminal de Avoqado?"
    const val TEXTO = "Si lo cobraste con «Pago rápido» en una terminal de Avoqado, ese cobro ya quedó registrado: " +
        "si también lo registras aquí, la venta se cuenta dos veces. No lo registres y avísale a tu gerente."
    const val CONSEJO = "Para cobrar una mesa con la terminal, entra a «Mesas» en la terminal: así queda un solo cobro."
    const val NO_REGISTRAR = "No registrar"
    fun registrar(montoConFormato: String) = "Fue otra terminal: registrar $montoConFormato"

    private val METODOS_DE_TARJETA = setOf("CREDIT_CARD", "DEBIT_CARD")

    /** ¿Esta elección de «¿Cómo pagó el cliente?» pide el aviso antes de registrarse? */
    fun aplica(eleccion: ManualPaymentChoice): Boolean = when (eleccion) {
        is ManualPaymentChoice.Fixed -> eleccion.method == ManualPaymentMethod.CARD_EXTERNAL
        is ManualPaymentChoice.Tender -> eleccion.option.baseMethod in METODOS_DE_TARJETA
    }
}
