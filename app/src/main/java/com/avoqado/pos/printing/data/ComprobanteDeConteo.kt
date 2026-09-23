package com.avoqado.pos.printing.data

import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.formatInvQty
import com.avoqado.pos.inventory.data.model.unitSuffixOf

/**
 * Comprobante impreso de un conteo COMPLETADO, para que lo firmen quien contó y quien revisa.
 * Espejo de `ComprobanteDeConteo` en iOS.
 *
 * Sólo lleva renglones de lo que SÍ se contó: un 0 sin contar no es faltante (el defecto de
 * «−6.9 millones» de Mindform nació de restar lo que nadie contó). Lo no contado va como número.
 */
data class ComprobanteDeConteo(
    val negocio: String?,
    val tipo: String,
    val folio: String,
    val fecha: String,
    val conto: String?,
    val nota: String?,
    val renglones: List<Renglon>,
    val total: Int,
    val sinContar: Int,
) {
    data class Renglon(val nombre: String, val esperado: String, val contado: String, val diferencia: String) {
        val cuadra: Boolean get() = diferencia == "0"
    }

    companion object {
        fun desde(conteo: StockCount, negocio: String?, fecha: String): ComprobanteDeConteo {
            val contadas = conteo.items.filter { it.yaSeConto }
            val renglones = contadas.map { item ->
                ComprobanteDeConteo.Renglon(
                    nombre = item.productName,
                    esperado = item.expectedDisplay,
                    contado = item.countedDisplay,
                    diferencia = conSigno(item.counted - item.expected, item.unit),
                )
            }.sortedBy { it.cuadra } // estable: las diferencias primero, en su orden original
            return ComprobanteDeConteo(
                negocio = negocio,
                tipo = conteo.type.label,
                folio = conteo.id.takeLast(8).uppercase(),
                fecha = fecha,
                conto = conteo.createdBy,
                nota = conteo.note?.takeIf { it.isNotBlank() },
                renglones = renglones,
                total = conteo.items.size,
                sinContar = conteo.items.size - contadas.size,
            )
        }

        private fun conSigno(valor: Double, unit: String?): String {
            val magnitud = formatInvQty(kotlin.math.abs(valor), unit)
            if (magnitud == "0") return "0"
            return (if (valor > 0) "+" else "-") + magnitud + unitSuffixOf(unit)
        }
    }
}
