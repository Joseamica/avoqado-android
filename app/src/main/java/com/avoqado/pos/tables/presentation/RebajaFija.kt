package com.avoqado.pos.tables.presentation

import com.avoqado.pos.tables.data.AppliedOrderDiscount

/** D15: cómo se enseña el «Quitar» de una rebaja de la cuenta en el diálogo «Descuentos» de Mesas. */
internal data class QuitarDeLaRebaja(
    val quitarActivo: Boolean,
    /** Texto corto debajo del nombre. */
    val nota: String? = null,
    /** No nula = tocar la fila la explica. */
    val razon: String? = null,
)

internal const val NOTA_REBAJA_FIJA = "Fija: viene del cobro de vales"
internal const val RAZON_REBAJA_FIJA =
    "Esta rebaja quedó fija al cobrar los vales y no se quita desde la caja. Si hay que cambiarla, avisa a un encargado."

/**
 * D15 (pizarrón hz:e82938e1:D15, 18-nov): la rebaja que quedó fija al cobrar vales (`congeladoPorVales`, del SERVIDOR) enseña su
 * «Quitar» en GRIS con su nota, y tocar la fila dice sólo por qué. No manda a cancelar, cobrar ni reembolsar: con un abono o sin red esa
 * salida puede estar bloqueada (Codex r3 #1). Las demás rebajas siguen como hoy y el SERVIDOR decide al tocar «Quitar».
 */
internal fun quitarDeLaRebaja(d: AppliedOrderDiscount): QuitarDeLaRebaja =
    if (d.congeladoPorVales) {
        QuitarDeLaRebaja(quitarActivo = false, nota = NOTA_REBAJA_FIJA, razon = RAZON_REBAJA_FIJA)
    } else {
        QuitarDeLaRebaja(quitarActivo = true)
    }
