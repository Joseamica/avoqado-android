package com.avoqado.pos.reports.presentation

import com.avoqado.pos.reports.data.model.HourlySales
import com.avoqado.pos.reports.data.model.PeriodSales
import com.avoqado.pos.reports.data.model.ReportPeriod
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D6 (2-nov): la gráfica del Informe de ventas calculaba la escala y las fechas, pero nunca las dibujaba. Los textos y las
 * reglas son los de iOS (`SalesLineChart.formatShortCurrency`, `shortLabel`, `xAxisLabels`; `HourlySalesChart.shortHourLabel`).
 */
class EjesDeLaGraficaTest {

    // MARK: - Eje de dinero

    // D16 (3-nov, visto en la Sunmi): en 1S y 1M el punto más alto valía $1,356 y la escala decía «$1K». Debajo de $10K los
    // miles llevan un decimal; nunca un «.0» al final; la mitad sube; y la unidad se elige ya redondeado ($999.5 es «$1K»).

    @Test
    fun `el eje de dinero abrevia miles y millones`() {
        assertEquals("$0", etiquetaCortaDeDinero(0.0))
        assertEquals("$950", etiquetaCortaDeDinero(950.0))
        assertEquals("$1K", etiquetaCortaDeDinero(1_000.0))
        assertEquals("$15K", etiquetaCortaDeDinero(15_400.0))
        assertEquals("$12K", etiquetaCortaDeDinero(12_345.0))
        assertEquals("$2.3M", etiquetaCortaDeDinero(2_340_000.0))
    }

    @Test
    fun `debajo de diez mil los miles llevan un decimal sin punto cero`() {
        assertEquals("$1.4K", etiquetaCortaDeDinero(1_356.0))
        assertEquals("$2K", etiquetaCortaDeDinero(2_000.0))
        assertEquals("$9.9K", etiquetaCortaDeDinero(9_940.0))
        assertEquals("$1M", etiquetaCortaDeDinero(1_000_000.0))
    }

    @Test
    fun `el eje de dinero redondea la mitad hacia arriba`() {
        assertEquals("$1.5K", etiquetaCortaDeDinero(1_450.0))
        assertEquals("$2.5K", etiquetaCortaDeDinero(2_500.0))
        assertEquals("$1.3M", etiquetaCortaDeDinero(1_250_000.0))
        assertEquals("$999", etiquetaCortaDeDinero(999.4))
    }

    @Test
    fun `al redondear hasta la unidad siguiente el eje cambia de unidad`() {
        assertEquals("$1K", etiquetaCortaDeDinero(999.5))
        assertEquals("$10K", etiquetaCortaDeDinero(9_960.0))
        assertEquals("$1M", etiquetaCortaDeDinero(999_500.0))
    }

    // MARK: - Fechas del eje

    @Test
    fun `la fecha del eje es el nombre del servidor en seis letras como mucho`() {
        assertEquals("lun 2", etiquetaCortaDelEje("lun 2 de nov"))
        assertEquals("2 nov", etiquetaCortaDelEje("2 nov"))
        assertEquals("27 oct", etiquetaCortaDelEje("27 oct"))
        assertEquals("noviem", etiquetaCortaDelEje("noviembre de 2026"))
        assertEquals("", etiquetaCortaDelEje(""))
    }

    @Test
    fun `lleva fecha uno de cada sexto de los puntos y siempre el ultimo`() {
        assertEquals(emptyList<Int>(), indicesConEtiqueta(0))
        assertEquals(listOf(0), indicesConEtiqueta(1))
        assertEquals(listOf(0, 1, 2, 3, 4), indicesConEtiqueta(5))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), indicesConEtiqueta(7))
        assertEquals(listOf(0, 2, 4, 6, 8, 10, 11), indicesConEtiqueta(12))
        assertEquals(listOf(0, 4, 8, 12, 16, 20, 23), indicesConEtiqueta(24))
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30), indicesConEtiqueta(31))
    }

    // MARK: - Horas

    @Test
    fun `la hora del eje es corta como en iOS`() {
        assertEquals("12a", etiquetaCortaDeHora("00:00"))
        assertEquals("3a", etiquetaCortaDeHora("03:00"))
        assertEquals("11a", etiquetaCortaDeHora("11:00"))
        assertEquals("12p", etiquetaCortaDeHora("12:00"))
        assertEquals("3p", etiquetaCortaDeHora("15:00"))
        assertEquals("11p", etiquetaCortaDeHora("23:00"))
    }

    @Test
    fun `una hora que no se lee va tal cual`() {
        assertEquals("abc", etiquetaCortaDeHora("abc"))
        assertEquals("", etiquetaCortaDeHora(""))
    }

    @Test
    fun `en ventas por hora lleva etiqueta una hora de cada tres`() {
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21), indicesDeHoraConEtiqueta(24))
        assertEquals(emptyList<Int>(), indicesDeHoraConEtiqueta(0))
    }

    // MARK: - Puntos de «Ventas brutas»

    @Test
    fun `hoy la grafica va por hora del dia con la hora corta debajo`() {
        val horas = listOf(
            HourlySales(hour = "09:00", grossSales = 100.0),
            HourlySales(hour = "15:00", grossSales = 50.0),
        )
        // La serie del periodo de HOY trae instantes ISO: nunca deben salir en el eje.
        val periodo = listOf(PeriodSales(period = "2026-11-02T15:00:00.000Z", periodLabel = "2 nov, 03:00 p.m.", grossSales = 50.0))

        val puntos = puntosDeVentasBrutas(ReportPeriod.TODAY, periodo, horas)

        assertEquals(listOf("9a" to 100.0, "3p" to 50.0), puntos)
    }

    @Test
    fun `otros periodos van por la serie del periodo con su fecha corta`() {
        val periodo = listOf(
            PeriodSales(periodLabel = "lun 2 de nov", grossSales = 120.0),
            PeriodSales(periodLabel = "mar 3 de nov", grossSales = 80.5),
        )
        val horas = listOf(HourlySales(hour = "09:00", grossSales = 999.0))

        val puntos = puntosDeVentasBrutas(ReportPeriod.THIS_WEEK, periodo, horas)

        assertEquals(listOf("lun 2" to 120.0, "mar 3" to 80.5), puntos)
    }
}
