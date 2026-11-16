package com.avoqado.pos.sync.presentation

import com.avoqado.pos.core.util.formatMoneyFromCents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * D1/A4 (ensayo del 16-nov, `hz:033d44ca:D9`). Dos tablets cobraron la misma mesa sin internet ($70 por una cuenta de $45). Al subir,
 * la tablet cuyo cobro sobró lo marcó «por revisar» (`ConciliacionDelCobro`, `cobradoDeMasCents`) y su franja dijo «1 operación
 * necesita revisión»; pero la mesera, sin permiso de revisar, abría «Operaciones por revisar» y sólo leía «Un gerente debe revisar este
 * pago.», sin ninguna operación. Aquí está lo que SÍ se le dice: cuántos cobros esperan a un gerente y cuánto se cobró de más, sin
 * folios ni historial (eso sigue siendo del gerente). PURA.
 */
internal object RevisionParaQuienNoRevisa {
    private val json = Json { ignoreUnknownKeys = true }

    /** Lo cobrado de más que dejó anotado la revisión de un cobro (`cobradoDeMasCents`), o null. */
    fun cobradoDeMasCents(resultadoJson: String?): Int? = resultadoJson?.let {
        runCatching { (json.parseToJsonElement(it) as? JsonObject)?.get("cobradoDeMasCents") as? JsonPrimitive }.getOrNull()
            ?.takeIf { p -> !p.isString }?.intOrNull?.takeIf { c -> c > 0 }
    }

    /**
     * El texto para quien no puede revisar. [cobros]: los que siguen por revisar; [resultados]: el resultado de cada uno que se pudo
     * leer (a lo más 200); si son menos que [cobros], la suma dice «o más». Null = no hay cobros por revisar.
     */
    fun texto(cobros: Int, resultados: List<String?>): String? {
        if (cobros <= 0) return null
        val deMas = resultados.sumOf { cobradoDeMasCents(it)?.toLong() ?: 0L }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val unoSolo = cobros == 1
        val cabeza = if (unoSolo) "Hay 1 cobro por revisar" else "Hay $cobros cobros por revisar"
        return if (deMas > 0) {
            val oMas = if (resultados.size < cobros) " o más" else ""
            "$cabeza: se cobraron ${formatMoneyFromCents(deMas)}$oMas de más. Avísale a un gerente: " +
                "${if (unoSolo) "lo" else "los"} revisa aquí (Más › Cambiar usuario) y decide si se le devuelve al cliente."
        } else {
            "$cabeza: el servidor ${if (unoSolo) "lo registró" else "los registró"} distinto de como se cobró aquí. " +
                "Avísale a un gerente para que ${if (unoSolo) "lo" else "los"} revise aquí (Más › Cambiar usuario)."
        }
    }
}
