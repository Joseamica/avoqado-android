package com.avoqado.pos.reports.presentation

import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.designsystem.components.TextosDeLecturaFallida
import com.avoqado.pos.reports.data.model.ConsultaDeInforme
import com.avoqado.pos.reports.data.model.InformeConsultado
import com.avoqado.pos.reports.data.model.ReportData

/**
 * Qué enseña Informes debajo de los periodos (D18, 3-nov-2026). Ya no hay «No hay datos para este periodo»: un informe
 * vacío que el servidor SÍ mandó se ve como siempre (resumen en ceros y «Sin datos para este periodo» en la gráfica), y
 * la falta de informe es que va en camino, que no hubo red o que el servidor contestó mal.
 */
internal sealed interface EstadoDelInforme {
    /** Va en camino el informe del periodo elegido: el esqueleto de siempre. */
    data object Cargando : EstadoDelInforme

    /** El informe del periodo elegido. */
    data class Listo(val datos: ReportData) : EstadoDelInforme

    /** No hay informe del periodo elegido porque la lectura falló. */
    data class NoSeCargo(val fallo: FalloDeLectura) : EstadoDelInforme

    /**
     * Con el selector de «Personalizado» abierto: ningún informe es de las fechas que se están eligiendo, así que debajo
     * no se enseña ninguno (Codex r1 #5). «Aplicar» lo pide; «Cancelar» vuelve al periodo de antes con su informe.
     */
    data object EligiendoFechas : EstadoDelInforme
}

/**
 * PURA. [elegida] = la consulta del periodo elegido; [informe] = el último informe bueno con la consulta que lo trajo;
 * [fallo] = por qué falló la última carga (`null` si salió bien).
 *
 * Un informe sólo se enseña bajo SU consulta: con red o sin ella, el de «Hoy» nunca se ve bajo «1M» (ni el de otra
 * sucursal). Bajo su consulta se ve aunque la recarga vaya en camino o haya fallado: la recarga es silenciosa.
 * Sin informe del periodo y sin fallo, la consulta va en camino (el gate la pide al elegir el periodo). Con el selector de
 * fechas abierto ([eligiendoFechas]) no se enseña ninguno.
 */
internal fun estadoDelInforme(
    elegida: ConsultaDeInforme,
    informe: InformeConsultado?,
    cargando: Boolean,
    fallo: FalloDeLectura?,
    eligiendoFechas: Boolean = false,
): EstadoDelInforme = when {
    eligiendoFechas -> EstadoDelInforme.EligiendoFechas
    informe != null && informe.consulta == elegida -> EstadoDelInforme.Listo(informe.datos)
    cargando -> EstadoDelInforme.Cargando
    fallo != null -> EstadoDelInforme.NoSeCargo(fallo)
    else -> EstadoDelInforme.Cargando
}

/** Título y texto de Informes cuando [EstadoDelInforme.NoSeCargo]. */
internal fun textosDeInformeSinCargar(fallo: FalloDeLectura): TextosDeLecturaFallida = when (fallo) {
    FalloDeLectura.SIN_RED -> TextosDeLecturaFallida(TextosDeInformes.SIN_RED_TITULO, TextosDeInformes.SIN_RED_TEXTO)
    FalloDeLectura.SERVIDOR -> TextosDeLecturaFallida(TextosDeInformes.SERVIDOR_TITULO, TextosDeInformes.SERVIDOR_TEXTO)
}

internal object TextosDeInformes {
    const val SIN_RED_TITULO = "Sin conexión"
    const val SIN_RED_TEXTO = "Los informes se calculan en el servidor. Se vuelven a pedir solos al volver la conexión."
    const val SERVIDOR_TITULO = "No se pudo cargar el informe"
    const val SERVIDOR_TEXTO = "Vuelve a intentarlo en un momento."
}
