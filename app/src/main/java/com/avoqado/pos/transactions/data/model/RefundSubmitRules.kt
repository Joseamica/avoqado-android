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

/**
 * Reembolso POR ARTÍCULOS: ¿la casilla «Incluir propina» arranca marcada? Sí cuando los artículos
 * elegidos cubren toda la venta que queda del cobro (devolver todo = todo el dinero, como Square).
 * Compara CENTAVOS ENTEROS con 2 de tolerancia por el reparto de unidades (en dobles, 144.98 vs 145
 * da 0.02000000000001 y fallaba). Servidor viejo sin la venta restante ⇒ no.
 */
fun propinaMarcadaPorDefecto(importeArticulos: Double, ventaRestante: Double?): Boolean =
    ventaRestante != null && importeArticulos > 0 &&
        kotlin.math.abs(centavosDelImporte(importeArticulos) - centavosDelImporte(ventaRestante)) <= 2

/**
 * Qué mandar en `tipRefundCents` al reembolsar POR ARTÍCULOS. `null` ⇒ el campo no viaja y el
 * servidor devuelve sólo los artículos, como siempre.
 *
 * La propina se topa con lo que queda del TOTAL una vez descontados los artículos:
 * `min(propina restante, total restante − artículos)`, en centavos enteros (espejo de `propinaQueCabe`
 * del dashboard). Con un acumulado histórico sin filas, venta + propina restantes pueden sumar más de lo
 * que el servidor deja salir: cobro $145 + $14.50 con $9.50 devueltos sin filas ⇒ total restante $150, y
 * con los $145 de artículos caben $5.00, no $14.50 (mandar $159.50 daba 400). `totalRestante` nulo ⇒ sin
 * tope de total. Resultado ≤ 0 ⇒ `null`.
 */
fun tipRefundCentsPorArticulos(
    marcada: Boolean,
    propinaRestante: Double?,
    totalRestante: Double?,
    importeArticulos: Double,
): Int? {
    if (!marcada || propinaRestante == null || propinaRestante <= 0) return null
    val propina = centavosDelImporte(propinaRestante)
    val cabe = if (totalRestante == null) {
        propina
    } else {
        minOf(propina, centavosDelImporte(totalRestante) - centavosDelImporte(importeArticulos))
    }
    return cabe.takeIf { it > 0 }
}

/**
 * ¿La devolución de este cobro se abre en la TERMINAL? Si no, se reembolsa como el efectivo: aquí se registra y
 * el dinero se le entrega al cliente por fuera.
 *
 * 🔴 Regla del founder (30-sep-2026): sólo la tarjeta presente en NUESTRA terminal. Transferencia, tipos de pago que
 * crea el negocio y «Tarjeta (terminal externa)» van como el efectivo. Antes la hoja preguntaba «¿no es CASH?» y una
 * transferencia de Testarudo pedía «Abrir en la terminal», que el servidor rechaza.
 *
 * Manda el servidor (`refundOnTerminal`, aditivo desde el 30-sep): el método solo no basta, hay CREDIT_CARD que la
 * terminal nunca cobró. Un servidor anterior no lo manda: se cae a su regla de entonces (sólo crédito o débito).
 */
fun seDevuelveEnTerminal(refundOnTerminal: Boolean?, method: String?): Boolean =
    refundOnTerminal ?: (method == "CREDIT_CARD" || method == "DEBIT_CARD")

/** Una pastilla de «Devolver con». `refundMethod = null` es «por el mismo medio» (no viaja al servidor). */
data class OpcionDeDevolucion(val refundMethod: String?, val label: String)

private fun esEfectivo(method: String?) = method == "CASH"
private fun esTransferencia(method: String?) = method == "BANK_TRANSFER" || method == "TRANSFER"

/**
 * Las pastillas de «Devolver con» (spec 2026-09-30): el método original primero (marcado), más «Efectivo de la
 * caja» y «Transferencia» sin repetir. Sólo se pintan si el servidor dijo `canChooseRefundMethod`.
 */
fun opcionesParaDevolver(method: String?, tenderLabel: String?): List<OpcionDeDevolucion> {
    val original = when {
        esEfectivo(method) -> "Efectivo de la caja"
        esTransferencia(method) -> "Transferencia"
        else -> tenderLabel?.takeIf { it.isNotBlank() } ?: PaymentMethodDisplay.label(method)
    }
    val agregaEfectivo = !esEfectivo(method)
    val agregaTransferencia = !esTransferencia(method)
    // Un método del negocio que se llama igual que una pastilla fija se distingue como «(original)».
    val choca = original.trim().let {
        (agregaEfectivo && it.equals("Efectivo de la caja", ignoreCase = true)) ||
            (agregaTransferencia && it.equals("Transferencia", ignoreCase = true))
    }
    return buildList {
        add(OpcionDeDevolucion(null, if (choca) "${original.trim()} (original)" else original))
        if (agregaEfectivo) add(OpcionDeDevolucion("CASH", "Efectivo de la caja"))
        if (agregaTransferencia) add(OpcionDeDevolucion("BANK_TRANSFER", "Transferencia"))
    }
}

/**
 * ¿Avisar «esto sólo lo registra»? Un cobro que no se abre en la terminal, sin selector y que no es efectivo
 * (tarjeta registrada a mano, transferencia vieja): el cajero tiene que saber que el dinero lo devuelve él.
 */
fun avisoDeSoloRegistro(enTerminal: Boolean, hayOpciones: Boolean, method: String?): Boolean =
    !enTerminal && !hayOpciones && method != "CASH"

/**
 * Qué avisar bajo «Devolver con» al escoger efectivo sin `payments:refund-to-cash` (1-oct-2026); `null` = nada.
 * No bloquea: el servidor da el 403. Con el código del encargado prendido (`managerPinOverrideEnabled`) ese 403 abre
 * el teclado; apagado (el default) es un «no» seco, así que no se promete un teclado que no va a salir.
 */
fun textoDeAutorizacionParaEfectivo(refundMethod: String?, puede: Boolean, codigoDeEncargadoActivo: Boolean): String? = when {
    refundMethod != "CASH" || puede -> null
    codigoDeEncargadoActivo -> "Necesitarás que un encargado lo autorice con su código."
    else -> "Sólo un encargado puede devolver en efectivo: pídele que lo haga desde su usuario."
}

/** El 403 al reembolsar: si se pidió efectivo, lo que falta es ESE permiso, no el de reembolsar. */
fun textoDeSinPermisoParaReembolsar(refundMethod: String?): String =
    if (refundMethod == "CASH") {
        "No tienes permiso para devolver en efectivo. Devuélvelo por el mismo medio o pídele a un encargado que lo haga."
    } else {
        "No tienes permiso para emitir reembolsos"
    }

/** La línea bajo las pastillas: qué pasa con el dinero según lo escogido. */
fun leyendaDeDevolucion(method: String?, refundMethod: String?): String = when {
    refundMethod == "CASH" || (refundMethod == null && esEfectivo(method)) -> "Sale de la caja: el corte lo descuenta solo."
    refundMethod == "BANK_TRANSFER" || (refundMethod == null && esTransferencia(method)) -> "Tú le haces la transferencia; aquí sólo queda registrado."
    else -> "Se devuelve por el mismo medio con que se pagó."
}
