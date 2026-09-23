package com.avoqado.pos.inventory.waste.domain

/** Qué hacer con una fila de la cola después de que el servidor contestó. */
sealed interface WasteOutcome {
    /** Registrada. La fila se cierra. */
    data object Sincronizada : WasteOutcome

    /** Se vuelve a mandar con el MISMO folio, tras esperar. */
    data object Reintentable : WasteOutcome

    /** Ni reintenta ni se borra sola: la ve una persona. */
    data class NecesitaRevision(val motivo: String) : WasteOutcome

    /** Alguien anuló el folio. Terminal. */
    data object Anulada : WasteOutcome

    /** El plan del negocio no cubre inventario. Sube solo cuando se levante. */
    data object BloqueoDePlan : WasteOutcome
}

/**
 * (HTTP, code, featureCode) → qué hacer con la fila. Función PURA: es la ÚNICA
 * fuente de esa decisión en la app, y su gemela de iOS tiene los mismos casos.
 *
 * 🔴 Se clasifica por CÓDIGO, no por la clase de la excepción. Es la lección que
 * ya costó dinero en el carril de cobro: «no llegó» y «llegó y el servidor dijo
 * que no» se parecen desde el cliente y significan lo contrario.
 *
 * `http = 0` es «ni siquiera hubo respuesta»: red caída, timeout, DNS.
 */
fun clasificarRespuestaDeMerma(http: Int, code: String?, featureCode: String?): WasteOutcome = when {
    http in 200..299 -> WasteOutcome.Sincronizada

    // Terminal, y se mira ANTES que nada: el servidor lo devuelve antes de revisar
    // el plan y el permiso, así que puede venir acompañado de `featureCode`. Un
    // folio anulado no lo resucita ningún plan.
    code == "WASTE_VOIDED" -> WasteOutcome.Anulada

    code == "WASTE_RETRYABLE_CONFLICT" -> WasteOutcome.Reintentable

    // Sin respuesta, servidor caído, saturado, se agotó la espera (408), o sesión por refrescar.
    http == 0 || http >= 500 || http == 429 || http == 408 || http == 401 -> WasteOutcome.Reintentable

    // El plan se distingue por `featureCode`: los middlewares compartidos de la
    // plataforma no mandan `code`.
    http == 403 && featureCode != null -> WasteOutcome.BloqueoDePlan

    // Todo lo demás, incluido un 4xx que este APK no conoce porque el servidor es
    // más nuevo. Reintentar para siempre algo que el servidor rechaza a propósito
    // sería un bucle invisible; el motivo viaja para poder explicárselo a alguien.
    else -> WasteOutcome.NecesitaRevision(code ?: "HTTP $http")
}
