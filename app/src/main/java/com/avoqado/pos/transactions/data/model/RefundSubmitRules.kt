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
