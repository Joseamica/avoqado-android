package com.avoqado.pos.printing.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * D1 «fotocopia del ticket» (28-oct): la copia de los renglones que imprimió el cobro de UNA parte de una venta dividida. La
 * reimpresión desde Ventas, el recibo digital y el correo de esa parte la pintan en vez de toda la venta. Es sólo TEXTO del ticket:
 * no dice qué está pagado (no toca inventario, reportes, comisiones ni devoluciones).
 *
 * Viaja opcional en el body de `/pay` (`lineasDelTicket`) y en su reenvío desde la cola. 🔴 Los topes son los MISMOS del SERVIDOR
 * (`src/services/shared/lineasDelTicket.ts`): si la copia no cabe, NO se manda y el cobro va igual que hoy. El servidor la guarda
 * sólo si cuadra exacto con lo que registró. Promesa y rondas de Codex: Avoqado/docs/audit/2026-10-28-parte-por-producto/.
 */
internal object CopiaDelTicket {
    const val TOPE_BYTES = 8 * 1024
    private const val TOPE_RENGLONES = 100
    private const val TOPE_MODIFICADORES = 10

    /**
     * El JSON de la copia, o null si no cabe. Los totales salen de la MISMA regla del papel ([TotalesDelTicket.desdeLoCobrado]):
     * subtotal = Σ de los renglones (lo bruto del papel) y descuento = bruto − lo cobrado sin propina. Un papel con cargo o IVA
     * aparte no se copia: la copia no los lleva y su subtotal − descuento ya no sería lo cobrado.
     */
    fun armar(
        renglones: List<ReceiptItem>,
        subtotalBruto: Int,
        cobradoSinPropina: Int,
        cargo: Int = 0,
        impuestoAgregado: Int = 0,
    ): String? {
        if (cargo > 0 || impuestoAgregado > 0 || cobradoSinPropina < 0) return null
        if (renglones.isEmpty() || renglones.size > TOPE_RENGLONES) return null
        val papel = TotalesDelTicket.desdeLoCobrado(cobradoSinPropina, propina = 0, subtotalBruto = subtotalBruto, cargo = 0, impuestoAgregado = 0)
        // Un papel que no cuadra consigo mismo (renglones que no suman el subtotal) no tiene copia: el SERVIDOR la rechazaría.
        if (renglones.sumOf { it.totalPrice.toLong() } != papel.subtotal.toLong()) return null
        val lineas = renglones.map { renglon(it) ?: return null }
        val json = buildJsonObject {
            put("lineas", JsonArray(lineas))
            put("subtotalCents", papel.subtotal)
            put("descuentoCents", papel.descuento)
        }.toString()
        return json.takeIf { it.toByteArray(Charsets.UTF_8).size <= TOPE_BYTES }
    }

    /** Un renglón del papel en la forma del contrato, o null si no cabe en los topes (entonces no se manda NADA de la copia). */
    private fun renglon(r: ReceiptItem): JsonObject? {
        if (r.name.isBlank() || r.name.length > 120 || r.quantity <= 0 || r.unitPrice < 0 || r.totalPrice < 0) return null
        val modificadores = r.modifiers.orEmpty().filter { it.isNotBlank() }
        if (modificadores.size > TOPE_MODIFICADORES || modificadores.any { it.length > 60 }) return null
        // Opcionales: vacío = no viaja; más largo que el tope = la copia no se manda.
        val peso = r.weightSummary?.takeIf { it.isNotBlank() }
        val nota = r.note?.takeIf { it.isNotBlank() }
        val area = r.areaSourceLabel?.takeIf { it.isNotBlank() }
        if ((peso?.length ?: 0) > 40 || (nota?.length ?: 0) > 140 || (area?.length ?: 0) > 60) return null
        return buildJsonObject {
            put("nombre", r.name)
            put("cantidad", r.quantity)
            put("unitarioCents", r.unitPrice)
            put("totalCents", r.totalPrice)
            if (modificadores.isNotEmpty()) put("modificadores", JsonArray(modificadores.map(::JsonPrimitive)))
            if (r.isCortesia) put("cortesia", true)
            if (r.isComboComponent) put("pieza", true)
            peso?.let { put("peso", it) }
            nota?.let { put("nota", it) }
            area?.let { put("area", it) }
        }
    }
}
