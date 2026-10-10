package com.avoqado.pos.articles.data.model

/**
 * D2 del plan 7 de IVA: las apps MUESTRAN el IVA del producto y no lo editan (se cambia en el dashboard, junto al precio).
 * Espejo exacto de avoqado-ios `IvaEnLectura.swift`. Nunca adivina: sin dato o con un valor desconocido no dice «16 %».
 */
object IvaEnLectura {
    const val AYUDA = "Se cambia en el dashboard"

    /** Un producto NUEVO nace al 16 %: el alta no manda IVA y el servidor pone su default. */
    const val TITULO_ALTA = "IVA: 16 %"

    fun titulo(ivaTratamiento: String?): String = when (ivaTratamiento) {
        "IVA_16" -> "IVA: 16 %"
        "IVA_8" -> "IVA: 8 %"
        "IVA_0" -> "IVA: 0 %"
        "EXENTO" -> "IVA: Exento"
        "NO_OBJETO" -> "IVA: No objeto de impuesto"
        "BLOQUEADO_03", "BLOQUEADO_04" -> "IVA: Por revisar"
        else -> "IVA: consúltalo en el dashboard"
    }
}
