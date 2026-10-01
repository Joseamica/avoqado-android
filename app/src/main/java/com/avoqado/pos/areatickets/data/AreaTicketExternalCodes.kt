// Caja externa (spec docs/superpowers/specs/2026-09-30-caja-externa-codigos-en-el-vale-design.md):
// el vale lo cobra OTRO POS, cuya pistola lee un código por pieza.
package com.avoqado.pos.areatickets.data

const val SETTLEMENT_ROUTE_EXTERNAL = "EXTERNAL"

/** La ruta va CONGELADA en el vale: si el área cambia de ruta, un vale ya emitido se reimprime como se emitió. */
val AreaTicket.isExternalRoute: Boolean get() = settlementRoute == SETTLEMENT_ROUTE_EXTERNAL

/**
 * D5 c/d (Codex final #1): la pistola no cobra bien un renglón por peso (un código fijo es 1 pieza) ni uno con
 * descuento (cobra el precio de lista). El servidor ya no los emite en caja externa; sólo llegan en un vale
 * externo emitido antes de esa guarda. Espejo de `externalRouteBlockers` del servidor.
 */
val AreaTicketLine.needsManualCapture: Boolean
    get() = weightKg != null || (discountAmount.toBigDecimalOrNull()?.signum() ?: 0) > 0

/**
 * Los códigos que la pistola de la otra caja tiene que leer para este renglón, UNO POR PIEZA:
 * primero el del producto (× piezas) y luego el de cada extra con código (× piezas × su cantidad),
 * porque «2 Latte + Shot» son dos lattes, cada uno con su shot. Un extra sin código no sale: si
 * cobraba algo, el servidor ya rechazó el vale al emitirlo (EXTERNAL_CODE_MAPPING_MISSING).
 * Un renglón por peso o con descuento no lleva ninguno: se cobra a mano ([needsManualCapture]).
 */
fun AreaTicketLine.externalCodes(): List<String> {
    if (needsManualCapture) return emptyList()
    val pieces = quantity.toBigDecimalOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
    val codes = mutableListOf<String>()
    skuSnapshot?.trim()?.takeIf { it.isNotEmpty() }?.let { sku -> repeat(pieces) { codes += sku } }
    for (modifier in modifiersSnapshot) {
        val sku = modifier.sku?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        repeat(pieces * modifier.quantity.coerceAtLeast(1)) { codes += sku }
    }
    return codes
}
