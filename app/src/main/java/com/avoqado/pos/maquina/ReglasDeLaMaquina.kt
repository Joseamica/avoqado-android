package com.avoqado.pos.maquina

/** La moneda más chica de la máquina es de $0.50: un total con otros centavos no se cobra en ella. */
fun cobrableEnMaquina(totalCents: Int): Boolean = totalCents > 0 && totalCents % 50 == 0

enum class NivelDeCambio { SUFICIENTE, POCO, DESCONOCIDO }

private val BARRA_DE_CAMBIO = Regex("^[012]{12}$")

/**
 * `changeBar` (manual 7.2): 12 caracteres, monedas de $0.50, $1, $2, $5, $10 y $20 (posiciones 0–5) y billetes de $20,
 * $50, $100, $200, $500 y $1000 (6–11). `2` suficiente, `1` poco, `0` nada. Poco si falta alguna moneda o si no hay
 * ningún billete chico ($20, $50, $100) para dar cambio.
 */
fun nivelDeCambio(changeBar: String?): NivelDeCambio {
    if (changeBar == null || !BARRA_DE_CAMBIO.matches(changeBar)) return NivelDeCambio.DESCONOCIDO
    val faltanMonedas = changeBar.substring(0, 6).any { it != '2' }
    val sinBilletesChicos = changeBar.substring(6, 9).all { it == '0' }
    return if (faltanMonedas || sinBilletesChicos) NivelDeCambio.POCO else NivelDeCambio.SUFICIENTE
}
