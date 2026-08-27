package com.avoqado.pos.core.util

import java.util.Locale

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
