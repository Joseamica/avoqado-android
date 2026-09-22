package com.avoqado.pos.inventory.waste.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** El formato exacto que exige el servidor para `quantity` cuando viaja como texto. */
private val DECIMAL_SIMPLE = Regex("""^\d+(\.\d+)?$""")

/** Tope del servidor para `note`, en CARACTERES. */
const val TOPE_DE_NOTA = 280

private val FORMATO_CON_ZONA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

/**
 * Lo que teclea una persona → lo que el servidor acepta, o `null` si no hay forma.
 *
 * 🔴 Se llama ANTES de persistir el folio. Una cantidad que el servidor va a
 * rechazar con un 422 permanente no debe llegar nunca a la cola: allí ya no tiene
 * salida, y el cajero se queda con una fila trabada que él no puede resolver.
 *
 * El teclado en español escribe COMA, así que la coma se acepta y se traduce. Un
 * separador de millares NO se adivina: "1,234.5" es ambiguo y se rechaza aquí.
 */
fun normalizarCantidad(texto: String): String? {
    val limpio = texto.trim().replace(',', '.')
    if (!DECIMAL_SIMPLE.matches(limpio)) return null
    // Un decimal simple es > 0 si y sólo si trae algún dígito distinto de cero.
    // Mismo razonamiento que el esquema del servidor: no hace falta convertirlo.
    if (limpio.none { it in '1'..'9' }) return null
    return limpio
}

/**
 * Recorta la nota al tope del servidor contando CARACTERES, no bytes.
 *
 * 🔴 Un emoji es 1 carácter y 4 bytes. Contar bytes rechazaría notas legítimas de
 * 70 emojis; no contar dejaría pasar un 422 permanente sobre una fila ya escrita.
 */
fun recortarNota(texto: String): String = texto.trim().take(TOPE_DE_NOTA)

/**
 * `clientOccurredAt`: el reloj del aparato, informativo, nunca decide nada.
 *
 * 🔴 Pero su FORMA sí importa: sin offset de zona el cuerpo entero se cae con 422.
 * Un reloj mal puesto sigue produciendo una fecha válida — eso es lo correcto,
 * porque el servidor filtra por su propia hora de llegada (spec §7 L6).
 */
fun fechaDelAparato(ahora: Instant, zona: ZoneId): String =
    ahora.atZone(zona).format(FORMATO_CON_ZONA)
