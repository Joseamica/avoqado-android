package com.avoqado.pos.maquina

/** La moneda más chica de la máquina es de $0.50: un total con otros centavos no se cobra en ella. */
fun cobrableEnMaquina(totalCents: Int): Boolean = totalCents > 0 && totalCents % 50 == 0

enum class NivelDeCambio { SUFICIENTE, POCO, DESCONOCIDO }

private val BARRA_DE_CAMBIO = Regex("^[012]{12}$")

/**
 * `changeBar` (manual 7.2): 12 caracteres, monedas de $0.50, $1, $2, $5, $10 y $20 (posiciones 0–5) y billetes de $20,
 * $50, $100, $200, $500 y $1000 (6–11). `2` suficiente, `1` poco, `0` nada.
 *
 * La máquina real sólo trae tubos de monedas de $1, $2, $5 y $10: en los ejemplos de APS las posiciones de $0.50 (0) y de
 * la moneda de $20 (5) siempre vienen en `0`, así que no cuentan (las de $0.50 se informan aparte, [hayMonedasDe50]).
 * Poco si alguna moneda de $1 a $10 está en `1` o `0`, o si no hay ni billetes de $20 ni de $50 para dar cambio.
 */
fun nivelDeCambio(changeBar: String?): NivelDeCambio {
    if (changeBar == null || !BARRA_DE_CAMBIO.matches(changeBar)) return NivelDeCambio.DESCONOCIDO
    val faltanMonedas = changeBar.substring(1, 5).any { it != '2' }
    val sinBilletesChicos = changeBar[6] == '0' && changeBar[7] == '0'
    return if (faltanMonedas || sinBilletesChicos) NivelDeCambio.POCO else NivelDeCambio.SUFICIENTE
}

/** ¿Hay monedas de $0.50 (posición 0 en `1` o `2`)? null si la barra no se puede leer. */
fun hayMonedasDe50(changeBar: String?): Boolean? =
    changeBar?.takeIf { BARRA_DE_CAMBIO.matches(it) }?.let { it[0] != '0' }
