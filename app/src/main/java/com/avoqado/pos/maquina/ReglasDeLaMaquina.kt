package com.avoqado.pos.maquina

/** La moneda más chica de la máquina es de $0.50: un total con otros centavos no se cobra en ella. */
fun cobrableEnMaquina(totalCents: Int): Boolean = totalCents > 0 && totalCents % 50 == 0

enum class NivelDeCambio { SUFICIENTE, POCO, DESCONOCIDO }

private val BARRA_DE_CAMBIO = Regex("^[012]{12}$")

/**
 * `changeBar` (manual 7.2): 12 caracteres, monedas de $0.50, $1, $2, $5, $10 y $20 (posiciones 0–5) y billetes de $20,
 * $50, $100, $200, $500 y $1000 (6–11). `2` suficiente, `1` poco, `0` nada.
 *
 * Codex código r2 H12: la regla ACORDADA del plan (Task C1), tal cual: POCO si alguna moneda de $0.50 a $20 (posiciones 0–5) está en
 * `0` o `1`, o si los billetes de $20, $50 y $100 (posiciones 6–8) están todos en `0`. Ni el manual ni el plan dicen que una posición
 * venga siempre en `0` (eso sólo se vio en el simulador y está preguntado a APS): ninguna se ignora. No depende del total: vale igual
 * con el precio cotizado y la propina.
 */
fun nivelDeCambio(changeBar: String?): NivelDeCambio {
    if (changeBar == null || !BARRA_DE_CAMBIO.matches(changeBar)) return NivelDeCambio.DESCONOCIDO
    val faltanMonedas = changeBar.substring(0, 6).any { it != '2' }
    val sinBilletesChicos = changeBar.substring(6, 9).all { it == '0' }
    return if (faltanMonedas || sinBilletesChicos) NivelDeCambio.POCO else NivelDeCambio.SUFICIENTE
}

/** El aviso ANTES de cobrar: con poco cambio (o sin saberlo), el texto fijo de poco cambio. null = nada que avisar. */
fun avisoDeCambio(changeBar: String?): String? = TEXTO_POCO_CAMBIO.takeIf { nivelDeCambio(changeBar) != NivelDeCambio.SUFICIENTE }
