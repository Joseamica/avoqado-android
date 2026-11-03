package com.avoqado.pos.reports.presentation

import com.avoqado.pos.reports.data.model.HourlySales
import com.avoqado.pos.reports.data.model.PeriodSales
import com.avoqado.pos.reports.data.model.ReportPeriod
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Los textos de los ejes del Informe de ventas. D6 (2-nov-2026): la gráfica calculaba la escala y las fechas pero nunca las
 * dibujaba, así que salía sin escala. Mismos textos y reglas que iOS (`SalesLineChart` y `HourlySalesChart`), salvo la escala de
 * dinero, que D16 afinó primero aquí (ver `etiquetaCortaDeDinero`). Puro: sin Android.
 */

/**
 * El máximo (y el cero) del eje de dinero: «$0», «$950», «$1.4K», «$12K», «$1.3M».
 *
 * D16 (3-nov, visto en la Sunmi): en 1S y 1M el punto más alto valía $1,356 y la escala decía «$1K»; con $1,500 diría «$2K».
 * Ahora, debajo de $10K los miles llevan un decimal («$1.4K»), nunca con «.0» al final («$2K», «$1M»); la mitad sube; y la
 * unidad se elige ya redondeado: $999.5 es «$1K» (nunca «$1000» ni «$1.0K») y $999,500 es «$1M». Antes era el
 * `formatShortCurrency` de iOS (miles sin decimales, «$1.0M», la mitad al par); iOS va después.
 */
internal fun etiquetaCortaDeDinero(valor: Double): String {
    if (!valor.isFinite()) return "$0"
    // El decimal escrito y no el binario: $1,450 son 1.45K exactos y suben a «$1.5K» (con el Double serían 1.4499…).
    val exacto = BigDecimal.valueOf(valor)
    val pesos = exacto.setScale(0, RoundingMode.HALF_UP)
    if (pesos < MIL) return "$" + pesos.toPlainString()
    val miles = exacto.movePointLeft(3)
    val milesRedondeados = miles.setScale(if (miles < DIEZ) 1 else 0, RoundingMode.HALF_UP)
    if (milesRedondeados < MIL) return "$" + sinPuntoCero(milesRedondeados) + "K"
    return "$" + sinPuntoCero(exacto.movePointLeft(6).setScale(1, RoundingMode.HALF_UP)) + "M"
}

private val MIL = BigDecimal(1_000)
private val DIEZ = BigDecimal.TEN

/** «2.0» → «2», «10.0» → «10», «1.4» se queda. */
private fun sinPuntoCero(numero: BigDecimal): String = numero.stripTrailingZeros().toPlainString()

/** La fecha de un punto: el nombre que manda el servidor, en 6 letras como mucho («lun 2», «27 oct»; `shortLabel` de iOS). */
internal fun etiquetaCortaDelEje(etiqueta: String): String = etiqueta.take(6).trimEnd()

/** Los puntos que llevan su fecha debajo: uno de cada `cuantos / 6` y siempre el último (`xAxisLabels` de iOS). */
internal fun indicesConEtiqueta(cuantos: Int): List<Int> {
    val paso = maxOf(1, cuantos / 6)
    return (0 until cuantos).filter { it % paso == 0 || it == cuantos - 1 }
}

/** La hora de un punto o una barra: «12a», «9a», «12p», «3p» (`shortHourLabel` de iOS). Si no se lee, va tal cual. */
internal fun etiquetaCortaDeHora(hora: String): String {
    val h = hora.split(":").first().toIntOrNull() ?: return hora
    return when {
        h == 0 -> "12a"
        h < 12 -> "${h}a"
        h == 12 -> "12p"
        else -> "${h - 12}p"
    }
}

/** En «Ventas por hora» lleva etiqueta una hora de cada tres: 12a, 3a, 6a… (iOS igual). */
internal fun indicesDeHoraConEtiqueta(cuantos: Int): List<Int> = (0 until cuantos).filter { it % 3 == 0 }

/**
 * Los puntos de «Ventas brutas» con la etiqueta que va debajo de cada uno. HOY va por hora del día (las 24 horas que trae el
 * resumen, con la hora corta); los demás periodos, por la serie del periodo con su fecha corta. La serie de HOY trae instantes
 * ISO («2026-11-02T21:00:00.000Z»), que nunca deben salir en el eje.
 */
internal fun puntosDeVentasBrutas(
    periodo: ReportPeriod,
    periodSales: List<PeriodSales>,
    hourlySales: List<HourlySales>,
): List<Pair<String, Double>> = if (periodo == ReportPeriod.TODAY) {
    hourlySales.map { Pair(etiquetaCortaDeHora(it.hour), it.grossSales) }
} else {
    periodSales.map { Pair(etiquetaCortaDelEje(it.periodLabel), it.grossSales) }
}
