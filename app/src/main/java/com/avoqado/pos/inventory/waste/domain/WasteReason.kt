package com.avoqado.pos.inventory.waste.domain

/**
 * Los 7 motivos que el POS puede mandar — el subconjunto `pos: true` de los 20
 * del dashboard (`avoqado-server/src/services/shared/wasteReasons.ts`).
 *
 * 🔴 El CÓDIGO es el contrato; la etiqueta es lo que ve el cajero. Antes de esto
 * el dashboard copiaba la etiqueta EN INGLÉS al campo libre `reason`, y el
 * servidor no conocía ningún motivo (spec §1, defecto 2). Nunca mandes la
 * etiqueta: manda `codigo`.
 *
 * El ORDEN es el de los chips en pantalla y tiene que ser el mismo que en iOS
 * (`WasteReason.swift`). Lo fija `WasteReasonTest` en los dos repos.
 */
enum class WasteReason(
    val codigo: String,
    val etiqueta: String,
    val exigeNota: Boolean = false,
) {
    EXPIRED("EXPIRED", "Caducó"),
    SPOILED("SPOILED", "Se echó a perder"),
    DROPPED("DROPPED", "Se cayó / derramó"),
    PREP_ERROR("PREP_ERROR", "Error de preparación"),
    DEFECTIVE("DEFECTIVE", "Dañado / roto"),
    MISSING("MISSING", "Robo o faltante"),
    OTHER("OTHER", "Otro", exigeNota = true),
    ;

    companion object {
        /** En el orden en que se pintan los chips. Mismo orden en iOS. */
        val delPos: List<WasteReason> = entries.toList()

        /**
         * `null` para cualquier código que este POS no conoce — incluido
         * `UNSPECIFIED`, que el servidor reserva al adaptador del dashboard y que
         * no debe aparecer en ningún selector.
         */
        fun porCodigo(codigo: String): WasteReason? = entries.firstOrNull { it.codigo == codigo }
    }
}
