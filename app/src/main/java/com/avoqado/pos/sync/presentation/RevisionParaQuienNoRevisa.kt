package com.avoqado.pos.sync.presentation

import com.avoqado.pos.core.data.local.database.PorRevisarQ15
import com.avoqado.pos.core.util.formatMoneyFromCents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * D1/A4 (ensayo del 16-nov, `hz:033d44ca:D9`). Dos tablets cobraron la misma mesa sin internet ($70 por una cuenta de $45). Al subir,
 * la tablet cuyo cobro sobró lo marcó «por revisar» (`ConciliacionDelCobro`, `cobradoDeMasCents`) y su franja dijo «1 operación
 * necesita revisión»; pero la mesera, sin permiso de revisar, abría «Operaciones por revisar» y sólo leía «Un gerente debe revisar este
 * pago.», sin ninguna operación. Aquí está lo que SÍ se le dice: cuántas operaciones esperan a un gerente y, si alguna lo dejó anotado,
 * cuánto se cobró de más. Sin folios ni historial (eso sigue siendo del gerente), y sin decir de qué tipo es cada una: una ronda
 * retenida también cae aquí (Codex r1 #1), así que no se le llama «cobro». PURA.
 */
internal object RevisionParaQuienNoRevisa {
    /** Lo que lee [Q15_POR_REVISAR_SQL]: con una fila más, el texto dice «más de 200». */
    const val TOPE = 200
    private val json = Json { ignoreUnknownKeys = true }

    /** Lo cobrado de más que dejó anotado la revisión de un cobro (`cobradoDeMasCents`), o null. */
    fun cobradoDeMasCents(resultadoJson: String?): Int? = resultadoJson?.takeIf { it.isNotBlank() }?.let {
        runCatching { (json.parseToJsonElement(it) as? JsonObject)?.get("cobradoDeMasCents") as? JsonPrimitive }.getOrNull()
            ?.takeIf { p -> !p.isString }?.intOrNull?.takeIf { c -> c > 0 }
    }

    /** El texto para quien no puede revisar. Null = no hay nada por revisar. */
    fun texto(filas: List<PorRevisarQ15>): String? {
        if (filas.isEmpty()) return null
        val masDelTope = filas.size > TOPE
        val leidas = filas.take(TOPE)
        val una = leidas.size == 1 && !masDelTope
        val cabeza = when {
            masDelTope -> "Hay más de $TOPE operaciones por revisar"
            una -> "Hay 1 operación por revisar"
            else -> "Hay ${leidas.size} operaciones por revisar"
        }
        val la = if (una) "la" else "las"
        val deMas = leidas.sumOf { cobradoDeMasCents(it.resultado)?.toLong() ?: 0L }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return if (deMas > 0) {
            "$cabeza: se cobraron ${formatMoneyFromCents(deMas)}${if (masDelTope) " o más" else ""} de más. Avísale a un gerente: " +
                "$la revisa aquí (Más › Cambiar usuario) y decide si se le devuelve al cliente."
        } else {
            "$cabeza. Sólo un gerente puede ver${la} y resolver${la}: avísale para que $la revise aquí (Más › Cambiar usuario)."
        }
    }
}
