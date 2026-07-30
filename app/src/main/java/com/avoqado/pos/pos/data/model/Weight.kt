// Venta por peso (báscula) — helpers puros, sin dependencias de Compose, para que la
// aritmética y el parser se puedan probar en unit tests JVM y compartir entre el panel de
// captura ([WeightCapturePanel]) y el carrito ([CartItem]).
//
// Unidades: TODO se normaliza a kg (3 decimales). El total de línea se calcula en CENTAVOS
// como round(weightKg × precio/kg en centavos) HALF-UP — la MISMA aritmética que aplica el
// server (Product.price × weightQuantity a 2 decimales), verificada por test de paridad.
package com.avoqado.pos.pos.data.model

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Rango válido en kg (3 decimales): 1 g a 99.999 kg — igual que el gate del server. */
const val MIN_WEIGHT_KG = 0.001
const val MAX_WEIGHT_KG = 99.999

/**
 * Parser tolerante de la captura manual (o del tecleo HID de una báscula):
 *  - acepta `0.435` y `0,435` (coma decimal → punto);
 *  - IGNORA caracteres no numéricos (una báscula puede colar "kg" o espacios);
 *  - [gramsMode]: la báscula manda GRAMOS ("435") → se divide ÷1000;
 *  - vacío, dos separadores decimales o fuera de rango 0.001–99.999 kg → null (inválido);
 *  - el resultado se redondea a 3 decimales (milésima de kg = gramo).
 */
fun parseWeightKg(raw: String, gramsMode: Boolean = false): Double? {
    val trimmed = raw.trim()
    if ('-' in trimmed) return null
    val cleaned = trimmed.map { c -> if (c == ',') '.' else c }.filter { it.isDigit() || it == '.' }
        .joinToString("")
    if (cleaned.isEmpty() || cleaned.count { it == '.' } > 1) return null
    val value = cleaned.toDoubleOrNull() ?: return null
    val kg = if (gramsMode) value / 1000.0 else value
    val milli = (kg * 1000).roundToLong()
    if (milli < 1 || milli > 99_999) return null
    return milli / 1000.0
}

/**
 * Total de línea en centavos: round HALF-UP de kg × precio/kg (roundToInt = Math.round, ties
 * hacia arriba — kotlin.math.round sería rint/ties-to-even y desviaría los .005). Paridad al
 * centavo con el server y con [CartItem.totalPrice].
 */
fun weightTotalCents(weightKg: Double, unitPriceCents: Int): Int =
    (weightKg * unitPriceCents).roundToInt()

/** "0.435" — 3 decimales, sin depender de Locale (String.format es Locale-sensible). */
fun formatWeightKg(kg: Double): String {
    val milli = (kg * 1000).roundToLong()
    return "${milli / 1000}.${(milli % 1000).toString().padStart(3, '0')}"
}
