package com.avoqado.pos.maquina

/** La moneda más chica de la máquina es de $0.50: un total con otros centavos no se cobra en ella. */
fun cobrableEnMaquina(totalCents: Int): Boolean = totalCents > 0 && totalCents % 50 == 0

enum class NivelDeCambio { SUFICIENTE, POCO, DESCONOCIDO }

private val BARRA_DE_CAMBIO = Regex("^[012]{12}$")

/**
 * `changeBar` (manual 7.2): 12 caracteres, monedas de $0.50, $1, $2, $5, $10 y $20 (posiciones 0–5) y billetes de $20,
 * $50, $100, $200, $500 y $1000 (6–11). `2` suficiente, `1` poco, `0` nada.
 *
 * Codex código r1 #16: la máquina real NO tiene tubo de $0.50 ni de la moneda de $20 (el manual dice que esas posiciones siempre vienen
 * en `0`): son «no hay monedas», no «poco cambio» (si contaran, siempre diría poco). Las de $0.50 deciden aparte ([avisoDeCambio]).
 * Poco si alguna moneda de $1 a $10 está en `1` o `0`, o si no hay billetes de $20, $50 ni $100 para dar cambio (la regla del plan).
 */
fun nivelDeCambio(changeBar: String?): NivelDeCambio {
    if (changeBar == null || !BARRA_DE_CAMBIO.matches(changeBar)) return NivelDeCambio.DESCONOCIDO
    val faltanMonedas = changeBar.substring(1, 5).any { it != '2' }
    val sinBilletesChicos = changeBar.substring(6, 9).all { it == '0' }
    return if (faltanMonedas || sinBilletesChicos) NivelDeCambio.POCO else NivelDeCambio.SUFICIENTE
}

/** ¿Hay monedas de $0.50 (posición 0 en `1` o `2`)? null si la barra no se puede leer. */
fun hayMonedasDe50(changeBar: String?): Boolean? =
    changeBar?.takeIf { BARRA_DE_CAMBIO.matches(it) }?.let { it[0] != '0' }

const val TEXTO_PODRIA_DEBER_50 = "La máquina podría quedarte a deber 50 centavos"

/**
 * Codex código r1 #16: el aviso ANTES de cobrar [dueCents]. Poco cambio (o sin saberlo): el texto fijo de poco cambio. Si no, un total que
 * termina en .50 avisa que la máquina podría quedar a deber 50 centavos (sólo sin monedas de $0.50 confirmadas: la real no las tiene).
 * null = nada que avisar.
 */
fun avisoDeCambio(changeBar: String?, dueCents: Int): String? = when {
    nivelDeCambio(changeBar) != NivelDeCambio.SUFICIENTE -> TEXTO_POCO_CAMBIO
    dueCents % 100 == 50 && hayMonedasDe50(changeBar) != true -> TEXTO_PODRIA_DEBER_50
    else -> null
}
