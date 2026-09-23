package com.avoqado.pos.transactions.data.model

import com.avoqado.pos.core.util.aCentavos

/**
 * ════════════════════════════════════════════════════════════════════════════════════
 * LAS DOS REGLAS DE DINERO DEL FORMULARIO DE REEMBOLSO
 * ════════════════════════════════════════════════════════════════════════════════════
 *
 * Viven FUERA del Composable a propósito. El módulo no tiene Robolectric ni
 * `compose.ui.test`, así que nada puede renderizar la hoja y comprobar un número en
 * pantalla; enterradas en el `onClick` estas dos reglas no las podía ejercitar ninguna
 * prueba. Aquí sí, y son las únicas dos líneas de esa pantalla que deciden cuánto
 * dinero se devuelve.
 */

/**
 * Pesos tecleados por el cajero → centavos enteros, que es lo único que acepta el servidor.
 *
 * 🔴 REDONDEA, no trunca. `(pesos * 100).toInt()` parece equivalente y no lo es: los
 * dobles no representan exacto la mayoría de los importes con centavos, así que
 * `4.35 * 100` vale 434.99999999999994 y truncar devuelve **434** — un centavo de menos,
 * en contra del cliente. Medido sobre los 20 000 importes de $0.01 a $200.00: **1 145
 * se devolverían cortos**. El dashboard web (`Math.round`) e iOS (`Int(round(...))`) ya
 * redondean; truncar era la divergencia de Android.
 */
fun centavosDelImporte(pesos: Double): Int = pesos.aCentavos()

/**
 * Qué mandar en `tipRefundCents` al reembolsar por importe.
 *
 * - `null`  ⇒ el campo NO viaja con valor: el servidor aplica su reparto proporcional
 *             entre venta y propina del cobro original. Es el default.
 * - `0`     ⇒ devuelve sólo la venta y **la propina del mesero queda intacta**.
 *
 * 🔴 El cero es un valor con significado, no «vacío». Colapsarlo a `null` restauraría
 * en silencio el reparto proporcional y le quitaría al mesero una propina que el cajero
 * decidió respetar.
 */
fun tipRefundCentsParaEnvio(paymentTipAmount: Double, includeTip: Boolean): Int? =
    if (paymentTipAmount > 0 && !includeTip) 0 else null

/**
 * Hasta cuánto puede devolver el cajero por importe, según si la propina viaja.
 *
 * - Propina incluida ⇒ todo lo disponible (venta + propina restantes).
 * - Propina desmarcada ⇒ SÓLO la venta restante: con `tipRefundCents = 0` el servidor lee
 *   el importe entero como venta, y un importe que incluya la propina lo rechaza
 *   («Sale portion of refund exceeds original sale amount»). Testarudo, 17-sep-2026: $220
 *   sobre una venta de $200, cinco 400 seguidos y el reembolso salió 4.7 h después por
 *   otro camino.
 *
 * `remainingRefundableSale` lo manda el servidor (aditivo, desde el 21-sep-2026) ya
 * descontadas las devoluciones previas. Un servidor anterior no lo manda: se cae a
 * «disponible − propina», que sin devoluciones previas es exacto y con ellas ofrece de
 * menos, nunca de más — el servidor sigue siendo quien valida.
 */
fun topeReembolsable(
    remainingRefundable: Double,
    remainingRefundableSale: Double?,
    paymentTipAmount: Double,
    includeTip: Boolean,
): Double =
    if (includeTip || paymentTipAmount <= 0) {
        remainingRefundable
    } else {
        (remainingRefundableSale ?: (remainingRefundable - paymentTipAmount)).coerceAtLeast(0.0)
    }

/**
 * Qué queda en el campo de importe cuando el tope BAJA (se desmarcó la propina): lo que
 * ya no cabe se recorta al tope; lo que cabe no se toca — quien escribió $50 sin propina
 * quiere devolver $50, no $30. Un texto que no es un número se deja tal cual.
 */
fun importeAjustadoAlTope(amountStr: String, tope: Double): String {
    val escrito = amountStr.replace(',', '.').toDoubleOrNull() ?: return amountStr
    return if (escrito > tope + 0.001) "%.2f".format(java.util.Locale.US, tope) else amountStr
}
