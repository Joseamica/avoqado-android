package com.avoqado.pos.core.util

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formato de dinero de la plataforma: pesos mexicanos, `$1,234.50`.
 *
 * 🔴 El locale va FIJO a propósito. `String.format("%.2f", x)` sin locale toma el del
 * aparato, así que una tablet dejada en español de España mostraba "$20,00" —
 * encontrado en una D3 Sunmi real el 2026-08-27, incluida la pantalla de confirmar el
 * cobro. El formato del dinero depende de la MONEDA del negocio, no del idioma en que
 * alguien dejó configurado el aparato.
 */
private val MONEY_LOCALE: Locale = Locale("es", "MX")

/** `1234.5` → `"$1,234.50"`. El signo negativo va ANTES del símbolo: `-$50.00`. */
fun formatMoney(amount: Double): String {
    val cuerpo = String.format(MONEY_LOCALE, "%,.2f", kotlin.math.abs(amount))
    return if (amount < 0) "-$$cuerpo" else "$$cuerpo"
}

/** `123450` → `"$1,234.50"`. Es como viaja el dinero en el POS: en centavos enteros. */
fun formatMoneyFromCents(cents: Int): String = formatMoney(cents / 100.0)

/** Igual que [formatMoney] pero sin el símbolo, para campos de captura. */
fun formatAmountNoSymbol(amount: Double): String =
    String.format(MONEY_LOCALE, "%,.2f", amount)

/**
 * Pesos (Double) → centavos enteros. **REDONDEA, nunca trunca.**
 *
 * 🔴 `(pesos * 100).toInt()` parece equivalente y no lo es. Un `Double` no representa
 * exacto la mayoría de los importes con centavos: `4.35 * 100` vale 434.99999999999994, y
 * truncar devuelve **434** — un centavo de menos. ⚠️ A quién perjudica DEPENDE de la
 * operación: al DEVOLVER, el cliente recibe de menos; al COBRAR un producto de $4.35, el
 * negocio cobra de menos. Redondear es lo correcto en las dos, y la razón no es la
 * dirección económica sino coincidir con el servidor. Medido
 * sobre los 20 000 importes de $0.01 a $200.00: **1 145 salen cortos**.
 *
 * Encontrado el 2026-09-11 en la hoja de reembolso (Testarudo) y, al barrer el repo,
 * en otros 8 sitios más. El dashboard web (`Math.round`) e iOS (`Int(round(...))`) ya
 * redondeaban: truncar era la divergencia de Android.
 *
 * ⚠️ Medido en producción ese mismo día: **0 de 205 precios de producto activos** caen en
 * el caso malo, porque un menú real usa precios redondos. Donde SÍ muerde es en los
 * importes que TECLEA el cajero (reembolso, división de cuenta), que pueden ser
 * cualquier valor.
 */
fun Double.aCentavos(): Int = (this * 100).roundToInt()
