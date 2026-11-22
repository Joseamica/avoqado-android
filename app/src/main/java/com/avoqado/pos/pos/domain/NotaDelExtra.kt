package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.SelectedModifier

/*
 * D40 (Flautas Coyoacán, como Fudo): una nota por cada PARTE del paquete — «sin cebolla» sólo al Consomé, «salsa combinada» a las
 * 2 Flautas Cochinita. La nota es de la OPCIÓN elegida (todas sus veces), no de cada vez: el renglón de Fudo dice «2 Flautas
 * Cochinita · salsa combinada». La nota del artículo completo (`CartItem.itemNote`) no cambia.
 *
 * En el cable viaja aparte (`modifierNotes: { modifierId: nota }`, ver [notasPorExtra]); en la cocina viaja DENTRO de la etiqueta de
 * la parte, en una segunda línea ([etiquetaEnCocina]: «Consomé⏎Nota: sin cebolla»), igual que el SERVIDOR (`toKdsModifierLabels`):
 * así llega por todos los caminos de cocina (papel, pantalla, reparto por WiFi) sin cambiar su contrato, y una pantalla de cocina
 * o una caja viejas la pintan ya debajo de su parte. La nota no se confunde con un extra que se llame «Consomé (Nota: …)» (Codex r1
 * #5); sí, en teoría, con uno cuyo nombre traiga un salto de línea y «Nota: » (riesgo cosmético aceptado, ver [etiquetaEnCocina]).
 */

/** Tope de la nota de una parte, el mismo que la nota del artículo y que el SERVIDOR. */
const val MAX_NOTA_DEL_EXTRA = 200

private const val ABRE_NOTA = "\nNota: "

/**
 * Lo que la nota NO lleva: caracteres de control (un NUL tumbaba el guardado en Postgres, Codex r1 #7) y cualquier espacio o salto
 * (también los de Unicode: U+0085, U+2028…). Lista EXPLÍCITA y la misma letra por letra que el SERVIDOR (`kdsModifierLabels.ts`,
 * Codex r2 #7): `\s` de Java y de JavaScript no cubren lo mismo, y la comanda del aparato y la del SERVIDOR deben salir iguales.
 */
private val ESPACIOS_Y_CONTROLES = Regex("[\\u0000-\\u001F\\u007F-\\u009F\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

/** Una línea, sin espacios de sobra ni caracteres de control y con tope; vacía = sin nota. El SERVIDOR la limpia igual. */
fun limpiarNotaDelExtra(texto: String?): String? =
    texto?.replace(ESPACIOS_Y_CONTROLES, " ")?.trim(' ')?.take(MAX_NOTA_DEL_EXTRA)?.trim(' ')?.takeIf { it.isNotEmpty() }

/**
 * «Consomé⏎Nota: sin cebolla»; sin nota, el nombre TAL CUAL, byte a byte como antes (Codex r2 #2: no se aplana). Riesgo aceptado
 * por escrito (decisión A, 21-nov): un extra cuyo NOMBRE trae «⏎Nota: …» se pintaría como si tuviera nota; sólo cambia cómo se ve.
 * Sólo ASCII en lo que agrega: la PC850 no trae «·».
 */
fun etiquetaEnCocina(nombre: String, nota: String?): String =
    limpiarNotaDelExtra(nota)?.let { "$nombre$ABRE_NOTA$it" } ?: nombre

val SelectedModifier.etiquetaEnCocina: String get() = etiquetaEnCocina(modifierName, note)

/** Las etiquetas de cocina de un renglón, una por vez elegida (las repetidas las junta [juntarExtrasRepetidos] al pintar). */
fun List<SelectedModifier>.etiquetasEnCocina(): List<String> = map { it.etiquetaEnCocina }

/**
 * La parte y su nota, para pintar la nota DEBAJO de su parte (pantalla de cocina, comanda). Una etiqueta sin nota sale entera con
 * nota null, así que lo de siempre se pinta igual.
 */
fun separarNotaDelExtra(etiqueta: String): Pair<String, String?> {
    val i = etiqueta.indexOf(ABRE_NOTA)
    if (i <= 0) return etiqueta to null
    val nota = etiqueta.substring(i + ABRE_NOTA.length).trim()
    return if (nota.isEmpty()) etiqueta to null else etiqueta.substring(0, i) to nota
}

/** Para la CAJA (carrito, renglones enviados): la parte y su nota en un solo renglón, «Consomé (Nota: sin cebolla)». */
fun enUnRenglon(etiqueta: String): String = separarNotaDelExtra(etiqueta).let { (parte, nota) -> nota?.let { "$parte (Nota: $it)" } ?: parte }

/** Sin las notas: lo que se manda cuando no caben (reparto por WiFi de una comanda enorme, Codex r1 #6 y r2 #1). */
fun sinNota(etiqueta: String): String = separarNotaDelExtra(etiqueta).first

/** La nota que lleva esta opción en este grupo (todas sus veces llevan la misma). */
fun notaDelExtra(actuales: List<SelectedModifier>, groupId: String, modifierId: String): String? =
    actuales.firstOrNull { it.groupId == groupId && it.modifierId == modifierId }?.note

/** Pone (o quita, con null o vacío) la nota de una opción en TODAS sus veces. Las demás opciones no cambian. */
fun ponerNotaAlExtra(actuales: List<SelectedModifier>, groupId: String, modifierId: String, nota: String?): List<SelectedModifier> {
    val limpia = limpiarNotaDelExtra(nota)
    return actuales.map { if (it.groupId == groupId && it.modifierId == modifierId) it.copy(note = limpia) else it }
}

/** Lo que viaja al SERVIDOR: `{ modifierId: nota }` sólo de las opciones con nota; vacío = la llave no viaja (lo de siempre). */
fun List<SelectedModifier>.notasPorExtra(): Map<String, String> =
    mapNotNull { m -> limpiarNotaDelExtra(m.note)?.let { m.modifierId to it } }.toMap()
