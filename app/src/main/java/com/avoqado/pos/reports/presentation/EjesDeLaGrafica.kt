package com.avoqado.pos.reports.presentation

import com.avoqado.pos.reports.data.model.HourlySales
import com.avoqado.pos.reports.data.model.PeriodSales
import com.avoqado.pos.reports.data.model.ReportPeriod
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Los textos de los ejes del Informe de ventas. D6 (2-nov-2026): la gráfica calculaba la escala y las fechas pero nunca las
 * dibujaba, así que salía sin escala. Mismos textos y reglas que iOS (`SalesLineChart` y `HourlySalesChart`). Puro: sin Android.
 */

/**
 * El máximo del eje de dinero: «$0», «$950», «$12K», «$1.2M» (`formatShortCurrency` de iOS). La mitad se redondea al par, como el
 * `String(format: "%.0f")` de iOS: $2,500 dice «$2K» en las dos apps.
 */
internal fun etiquetaCortaDeDinero(valor: Double): String = when {
    !valor.isFinite() -> "$0"
    valor >= 1_000_000 -> "$" + redondeado(valor / 1_000_000, 1) + "M"
    valor >= 1_000 -> "$" + redondeado(valor / 1_000, 0) + "K"
    else -> "$" + redondeado(valor, 0)
}

private fun redondeado(valor: Double, decimales: Int): String =
    BigDecimal(valor).setScale(decimales, RoundingMode.HALF_EVEN).toPlainString()

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
