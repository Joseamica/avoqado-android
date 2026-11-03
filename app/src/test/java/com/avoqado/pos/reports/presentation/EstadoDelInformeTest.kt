package com.avoqado.pos.reports.presentation

import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.designsystem.components.TextosDeLecturaFallida
import com.avoqado.pos.reports.data.model.ConsultaDeInforme
import com.avoqado.pos.reports.data.model.InformeConsultado
import com.avoqado.pos.reports.data.model.ReportData
import com.avoqado.pos.reports.data.model.SalesSummaryReport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D18 (3-nov): sin red, Informes decía «No hay datos para este periodo», y al pasar de «Hoy» a «1M» se quedaban a la
 * vista los números de «Hoy» bajo la pestaña «1M»: el informe no iba ligado a su periodo.
 */
class EstadoDelInformeTest {
    private val hoy = ConsultaDeInforme("venue", "2026-11-03T06:00:00.000Z", null, "hours")
    private val mes = ConsultaDeInforme("venue", "2026-11-01T06:00:00.000Z", null, "days")
    private val datosDeHoy = ReportData(
        summary = SalesSummaryReport(grossSales = 1234.5, transactionCount = 3),
        paymentMethods = emptyList(),
        hourlySales = emptyList(),
        periodSales = emptyList(),
        topProducts = emptyList(),
        categories = emptyList(),
    )
    private val informeDeHoy = InformeConsultado(hoy, datosDeHoy)

    @Test
    fun `el informe de Hoy no se ve bajo 1M - sin red dice Sin conexion`() {
        assertEquals(
            EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED),
            estadoDelInforme(elegida = mes, informe = informeDeHoy, cargando = false, fallo = FalloDeLectura.SIN_RED),
        )
    }

    @Test
    fun `el informe de Hoy no se ve bajo 1M - si el servidor contesto mal lo dice`() {
        assertEquals(
            EstadoDelInforme.NoSeCargo(FalloDeLectura.SERVIDOR),
            estadoDelInforme(mes, informeDeHoy, cargando = false, fallo = FalloDeLectura.SERVIDOR),
        )
    }

    @Test
    fun `el informe de Hoy no se ve bajo 1M - mientras carga el de 1M es el esqueleto`() {
        assertEquals(EstadoDelInforme.Cargando, estadoDelInforme(mes, informeDeHoy, cargando = true, fallo = null))
        assertEquals(
            EstadoDelInforme.Cargando,
            estadoDelInforme(mes, informeDeHoy, cargando = true, fallo = FalloDeLectura.SIN_RED),
        )
    }

    @Test
    fun `bajo su periodo el informe se ve aunque la recarga vaya o haya fallado`() {
        for (cargando in listOf(false, true)) {
            for (fallo in listOf(null, FalloDeLectura.SIN_RED, FalloDeLectura.SERVIDOR)) {
                assertEquals(EstadoDelInforme.Listo(datosDeHoy), estadoDelInforme(hoy, informeDeHoy, cargando, fallo))
            }
        }
    }

    @Test
    fun `el informe de otra sucursal no se ve`() {
        assertEquals(
            EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED),
            estadoDelInforme(hoy.copy(sucursal = "otra"), informeDeHoy, cargando = false, fallo = FalloDeLectura.SIN_RED),
        )
    }

    @Test
    fun `un rango personalizado con otro fin no es el mismo informe`() {
        val delUnoAlDos = ConsultaDeInforme("venue", "2026-11-01T06:00:00.000Z", "2026-11-03T05:59:59.999Z", "days")
        val delUnoAlTres = delUnoAlDos.copy(fin = "2026-11-04T05:59:59.999Z")
        assertEquals(
            EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED),
            estadoDelInforme(delUnoAlTres, InformeConsultado(delUnoAlDos, datosDeHoy), false, FalloDeLectura.SIN_RED),
        )
    }

    @Test
    fun `sin ningun informe y sin red dice Sin conexion, no No hay datos`() {
        assertEquals(
            EstadoDelInforme.NoSeCargo(FalloDeLectura.SIN_RED),
            estadoDelInforme(hoy, informe = null, cargando = false, fallo = FalloDeLectura.SIN_RED),
        )
    }

    @Test
    fun `sin informe del periodo y sin fallo todavia es el esqueleto - la consulta va en camino`() {
        assertEquals(EstadoDelInforme.Cargando, estadoDelInforme(hoy, informe = null, cargando = false, fallo = null))
        assertEquals(EstadoDelInforme.Cargando, estadoDelInforme(mes, informeDeHoy, cargando = false, fallo = null))
    }

    @Test
    fun `los textos son los del encargo`() {
        assertEquals(
            TextosDeLecturaFallida(
                "Sin conexión",
                "Los informes se calculan en el servidor. Vuelve a intentarlo al recuperar la conexión.",
            ),
            textosDeInformeSinCargar(FalloDeLectura.SIN_RED),
        )
        assertEquals(
            TextosDeLecturaFallida("No se pudo cargar el informe", "Vuelve a intentarlo en un momento."),
            textosDeInformeSinCargar(FalloDeLectura.SERVIDOR),
        )
    }
}
