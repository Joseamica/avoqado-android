package com.avoqado.pos.cashdrawer.data

import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import java.util.Locale

/**
 * 🔴 EL CONTEO QUE NO ERA UN CONTEO (Testarudo, 6-sep-2026).
 *
 * El cajero registró dos retiros seguidos —propinas $2,814 y «venta final» $6,042— y quince
 * segundos después, cuando la hoja le pidió contar el cajón, escribió $8,856: la SUMA de lo que
 * acababa de sacar, no lo que quedó dentro. Su nota lo dice: «suma propinas y venta». El servidor
 * calculó bien ($1,852 esperados: el fondo menos el hielo) y el ticket imprimió un sobrante de
 * $7,004 que no existe. El 5-sep, mismo cajero, misma confusión en otra forma.
 *
 * La hoja no puede enseñarle el esperado (conteo ciego: un faltante pesa como evidencia laboral y
 * el cajero no debe poder «contar hacia el sistema»). Pero SÍ puede reconocer que el número
 * tecleado es uno que él mismo acaba de producir, sin revelar nada nuevo:
 *
 *  - la suma de sus ÚLTIMOS retiros (los del cierre: $2,814 + $6,042). No «todos los retiros»: el
 *    hielo de las 14:15 no entraba en su suma, y con «todos» este caso real no se habría cazado;
 *  - el efectivo cobrado del día, que ya vio impreso en el corte parcial.
 *
 * Es una PREGUNTA antes de confirmar, nunca un bloqueo: un conteo legítimo puede coincidir por
 * azar y el cajero decide. Los textos viven aquí, no en la pantalla, para que se prueben y para
 * que iOS los espeje letra por letra (`ConteoSospechoso.swift`).
 */
enum class MotivoDeSospecha {
    /** El conteo es exactamente la suma de los últimos retiros registrados. */
    MISMO_QUE_RETIROS,

    /** El conteo es exactamente el efectivo cobrado del día. */
    MISMO_QUE_EFECTIVO_COBRADO,
}

/** Un retiro del día, tal como lo ve el cajero: su nota y su monto. */
data class RetiroDelDia(val nota: String?, val amountCents: Int)

data class SospechaDeConteo(
    val motivo: MotivoDeSospecha,
    val montoCents: Int,
    /** Sólo para [MotivoDeSospecha.MISMO_QUE_RETIROS]: los retiros cuya suma coincide. */
    val retirosQueCoinciden: List<RetiroDelDia> = emptyList(),
)

object ConteoSospechoso {

    const val TITULO = "¿Contaste lo que quedó dentro del cajón?"
    const val VOLVER_A_CONTAR = "Volver a contar"
    const val CERRAR_ASI = "Cerrar con este conteo"

    /** Lo que la hoja de cierre le dice al cajero ANTES de que teclee. */
    const val INSTRUCCION =
        "Cuenta los billetes y monedas que hay dentro del cajón en este momento, incluido el " +
            "fondo con el que abriste. La diferencia se muestra al confirmar."
    const val ENCABEZADO_DE_RETIROS = "Lo que ya sacaste hoy no se cuenta:"
    const val TOTAL_RETIRADO = "Total retirado"

    /** Los retiros de la sesión, del más viejo al más reciente. */
    fun retirosDelDia(events: List<CashDrawerEventEntity>): List<RetiroDelDia> =
        events
            .filter { it.type == CashDrawerEventType.PAY_OUT.name }
            .sortedBy { it.createdAt }
            .map { RetiroDelDia(it.note, it.amountCents) }

    /** El efectivo que entró por ventas (los `CASH_SALE` ya traen la propina en efectivo). */
    fun efectivoCobradoCents(events: List<CashDrawerEventEntity>): Int =
        events.filter { it.type == CashDrawerEventType.CASH_SALE.name }.sumOf { it.amountCents }

    /**
     * `null` cuando el número tecleado no coincide con nada conocido: ahí no hay nada que
     * preguntar. Se revisan primero los retiros, porque ese mensaje es el más accionable («ese
     * dinero ya no está en el cajón»); el 6-sep coincidían los dos.
     */
    fun evaluar(actualCents: Int, retiros: List<RetiroDelDia>, efectivoCobradoCents: Int): SospechaDeConteo? {
        var suma = 0
        for (k in retiros.indices.reversed()) {
            suma += retiros[k].amountCents
            if (suma > 0 && suma == actualCents) {
                return SospechaDeConteo(MotivoDeSospecha.MISMO_QUE_RETIROS, actualCents, retiros.subList(k, retiros.size))
            }
        }
        if (efectivoCobradoCents > 0 && actualCents == efectivoCobradoCents) {
            return SospechaDeConteo(MotivoDeSospecha.MISMO_QUE_EFECTIVO_COBRADO, actualCents)
        }
        return null
    }

    /** El cuerpo del diálogo. Nunca menciona el esperado: el conteo sigue siendo ciego. */
    fun mensaje(sospecha: SospechaDeConteo): String = when (sospecha.motivo) {
        MotivoDeSospecha.MISMO_QUE_RETIROS ->
            "Escribiste ${pesos(sospecha.montoCents)}, que es exactamente lo que acabas de retirar " +
                "(${nombres(sospecha.retirosQueCoinciden)}). Ese dinero ya no está en el cajón: cuenta " +
                "sólo los billetes y monedas que quedaron dentro, incluido el fondo."
        MotivoDeSospecha.MISMO_QUE_EFECTIVO_COBRADO ->
            "Escribiste ${pesos(sospecha.montoCents)}, que es exactamente el efectivo cobrado hoy. " +
                "El conteo es lo que hay dentro del cajón ahora: el fondo más lo cobrado, menos lo que sacaste."
    }

    /** La etiqueta de un retiro en la hoja: su nota, o «Retiro» si no la tiene. */
    fun etiqueta(retiro: RetiroDelDia): String = retiro.nota?.trim()?.takeIf { it.isNotEmpty() } ?: "Retiro"

    internal fun nombres(retiros: List<RetiroDelDia>): String {
        val etiquetas = retiros.map { it.nota?.trim()?.takeIf { n -> n.isNotEmpty() } ?: "retiro" }
        return when (etiquetas.size) {
            0 -> ""
            1 -> etiquetas[0]
            else -> etiquetas.dropLast(1).joinToString(", ") + " y " + etiquetas.last()
        }
    }

    fun pesos(cents: Int): String = "$" + String.format(Locale.US, "%,.2f", cents / 100.0)
}
