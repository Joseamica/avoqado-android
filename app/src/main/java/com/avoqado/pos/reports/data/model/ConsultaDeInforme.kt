package com.avoqado.pos.reports.data.model

/**
 * Qué informe es (D18, 3-nov-2026): lo mismo que se le pide al servidor —la sucursal, el inicio, el fin y el tipo de
 * la gráfica—. Los periodos fijos (Hoy, 1S, 1M, 3M, 1A) piden «hasta ahora»: su [fin] es `null`, porque la hora de
 * cada recarga no hace otro informe. Un informe sólo se enseña bajo la consulta que lo trajo: el de «Hoy» nunca bajo
 * «1M», ni el de otra sucursal.
 */
data class ConsultaDeInforme(val sucursal: String, val inicio: String, val fin: String?, val tipo: String)

/** Un informe y la consulta que lo trajo. */
data class InformeConsultado(val consulta: ConsultaDeInforme, val datos: ReportData)
