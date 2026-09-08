package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReintentoDeComandaTest {

    private val cocinaPrinterInfo = PrinterInfo(
        id = "pr_cocina",
        name = "Cocina Printer",
        connectionType = "NETWORK",
        address = "192.168.1.50:9100",
    )
    private val cocinaStation = StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_cocina", copies = 1)
    private val config = PrintConfig(printers = listOf(cocinaPrinterInfo), stations = listOf(cocinaStation))

    private val tacoLine = ConsolidatedLine("Taco", 2, emptyList(), null, listOf("oi_1"))
    private val cervezaLine = ConsolidatedLine("Cerveza", 1, emptyList(), null, listOf("oi_2"))

    private val planCocina = TicketPlan(stationId = "st_cocina", unrouted = false, lines = listOf(tacoLine))
    private val planBarra = TicketPlan(stationId = "st_barra", unrouted = false, lines = listOf(cervezaLine))
    private val planes = listOf(planCocina, planBarra)

    private fun exito(): ComandaPrinter.Result = ComandaPrinter.Result(
        attempted = planes.size,
        printed = planes.size,
        skippedNoPrinter = 0,
        lastError = null,
    )

    private fun fallo(
        estacion: String,
        causa: String? = null,
        planesQueFallaron: List<TicketPlan> = listOf(planCocina),
    ): ComandaPrinter.Result = ComandaPrinter.Result(
        attempted = planes.size,
        printed = 0,
        skippedNoPrinter = 0,
        lastError = causa,
        failedStations = listOf(estacion),
        failedPlans = planesQueFallaron,
    )

    private fun saltada(estacion: String): ComandaPrinter.Result = ComandaPrinter.Result(
        attempted = 1,
        printed = 0,
        skippedNoPrinter = 1,
        lastError = null,
        skippedStations = listOf(estacion),
        // Un plan SALTADO nunca entra a failedPlans — no hay impresora que reintentar.
    )

    @Test
    fun `si sale al primer intento no espera ni reintenta`() = runTest {
        val esperas = mutableListOf<Long>()
        val printer = ComandaPrinterFalso(resultados = listOf(exito()))
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { esperas += it })

        val estado = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap())

        assertEquals(EstadoDeComanda.Salio, estado)
        assertEquals(emptyList<Long>(), esperas)
        assertEquals(1, printer.llamadas)
    }

    @Test
    fun `si el primero truena y el segundo sale, sale sola sin molestar a nadie`() = runTest {
        val esperas = mutableListOf<Long>()
        val printer = ComandaPrinterFalso(resultados = listOf(fallo("Cocina"), exito()))
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { esperas += it })

        val estado = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap())

        assertEquals(EstadoDeComanda.Salio, estado)
        assertEquals(listOf(2_000L), esperas)
        assertEquals(2, printer.llamadas)
    }

    @Test
    fun `se rinde tras seis intentos y reporta la causa REAL del ultimo`() = runTest {
        val printer = ComandaPrinterFalso(resultados = List(6) { fallo("Cocina", causa = "timeout 192.168.1.141:9100") })
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { })

        val estado = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap())

        assertEquals(EstadoDeComanda.NoSalio(listOf("Cocina"), "timeout 192.168.1.141:9100", "ORD-1"), estado)
        assertEquals(6, printer.llamadas)
    }

    @Test
    fun `solo reintenta las estaciones que TRONARON, no las que ya salieron`() = runTest {
        val printer = ComandaPrinterFalso(
            resultados = listOf(fallo("Cocina", planesQueFallaron = listOf(planCocina)), exito()),
        )
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { })

        sut.insistir(listOf(planCocina, planBarra), config, "ORD-1", "En tienda", null, emptyMap())

        // El segundo intento manda SOLO el plan de cocina — reimprimir barra seria un ticket duplicado.
        assertEquals(listOf(planCocina), printer.planesDelIntento(2))
    }

    @Test
    fun `una estacion SALTADA no se reintenta ni una vez`() = runTest {
        val printer = ComandaPrinterFalso(resultados = listOf(saltada("Barra")))
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { })

        val estado = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap())

        assertEquals(1, printer.llamadas)
        assertTrue(estado is EstadoDeComanda.NoSalio)
    }

    /**
     * P1 de la ronda de arreglo 1: `alCambiarEstado` no tenía NI UNA prueba, y es justo lo que
     * la Tarea 4 usa para pintarle al cajero "Reintentando la comanda de Cocina · intento 2 de 6".
     * Aquí se fija la secuencia completa: cada `Insistiendo` trae el intento QUE SIGUE (no el que
     * ya se hizo), el tope real (`INTENTOS_MAXIMOS`), y las estaciones del fallo MÁS RECIENTE —no
     * las del primer tropiezo, que es justo lo que un cajero necesita ver actualizado en pantalla.
     * Y el estado final (`Salio`) SIEMPRE se emite, no sólo se retorna.
     */
    @Test
    fun `la secuencia de Insistiendo trae el intento y las estaciones del momento, y el final SIEMPRE se emite`() = runTest {
        val estados = mutableListOf<EstadoDeComanda>()
        val printer = ComandaPrinterFalso(
            resultados = listOf(
                fallo("Cocina", planesQueFallaron = listOf(planCocina)),
                fallo("Barra", planesQueFallaron = listOf(planBarra)),
                exito(),
            ),
        )
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { })

        val estadoFinal = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap()) { estados += it }

        assertEquals(
            listOf(
                // Tras el intento 1 (Cocina) se avisa que YA VIENE el intento 2 — no el 1, que
                // acaba de terminar. Y las estaciones son las de ESTE fallo, no un eco del anterior.
                EstadoDeComanda.Insistiendo(2, PoliticaDeReintento.INTENTOS_MAXIMOS, listOf("Cocina"), "ORD-1"),
                // Tras el intento 2 (Barra, DISTINTA de Cocina) se avisa el 3 — con Barra, no Cocina.
                EstadoDeComanda.Insistiendo(3, PoliticaDeReintento.INTENTOS_MAXIMOS, listOf("Barra"), "ORD-1"),
                EstadoDeComanda.Salio,
            ),
            estados,
        )
        assertEquals(EstadoDeComanda.Salio, estadoFinal)
        assertEquals(estados.last(), estadoFinal)
    }

    /** El otro lado de la misma moneda: cuando se rinde, lo ÚLTIMO que oye la pantalla es el NoSalio real. */
    @Test
    fun `cuando se rinde, el ultimo estado emitido es NoSalio con las estaciones y la causa reales`() = runTest {
        val estados = mutableListOf<EstadoDeComanda>()
        val printer = ComandaPrinterFalso(resultados = List(6) { fallo("Cocina", causa = "timeout 192.168.1.141:9100") })
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { })

        val estadoFinal = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap()) { estados += it }

        assertEquals(EstadoDeComanda.NoSalio(listOf("Cocina"), "timeout 192.168.1.141:9100", "ORD-1"), estados.last())
        assertEquals(estados.last(), estadoFinal)
        // 5 Insistiendo (rumbo a los intentos 2..6) y luego se rinde — el intento 6 no anuncia un
        // 7 que nunca va a pasar.
        assertEquals(5, estados.count { it is EstadoDeComanda.Insistiendo })
    }

    /**
     * Ronda de arreglo 1 (Important, decisión del founder/coordinador): el KDS necesita poder
     * capar los intentos a 1 — sin tablet hermana, insistir retendría la reclamación del pedido
     * hasta ~50 s en vez de soltarla en segundos. `maxIntentos=1` debe comportarse EXACTAMENTE
     * como el mostrador se comportaba ANTES de que existiera el reintento: un solo intento, sin
     * esperar, sin emitir `Insistiendo`.
     */
    @Test
    fun `con maxIntentos 1 no hay reintentos — un solo intento y se rinde de una vez`() = runTest {
        val estados = mutableListOf<EstadoDeComanda>()
        val esperas = mutableListOf<Long>()
        val printer = ComandaPrinterFalso(resultados = List(6) { fallo("Cocina", causa = "timeout") })
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { esperas += it })

        val estadoFinal = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap(), maxIntentos = 1) {
            estados += it
        }

        assertEquals(1, printer.llamadas)
        assertEquals(emptyList<Long>(), esperas)
        assertTrue(estados.none { it is EstadoDeComanda.Insistiendo })
        assertEquals(EstadoDeComanda.NoSalio(listOf("Cocina"), "timeout", "ORD-1"), estadoFinal)
    }

    /** El default sigue siendo el de siempre: `PoliticaDeReintento.INTENTOS_MAXIMOS` (6, ~1 min). */
    @Test
    fun `sin pasar maxIntentos el tope sigue siendo el de PoliticaDeReintento`() = runTest {
        val printer = ComandaPrinterFalso(resultados = List(6) { fallo("Cocina", causa = "timeout") })
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { })

        sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap())

        assertEquals(PoliticaDeReintento.INTENTOS_MAXIMOS, printer.llamadas)
    }
}

/**
 * Doble local de [ComandaPrinter]: devuelve los [resultados] preprogramados EN ORDEN (uno por
 * llamada) y registra cuántas veces se le llamó y con qué planes en cada una.
 *
 * 🔴 `ComandaPrinter` es una `class` FINAL (no `open`, no interfaz) — Kotlin no permite
 * subclasificarla, así que este doble no puede EXTENDERLA. Por dentro arma un mock de MockK, que
 * en JVM sí puede interceptar una clase final (el mismo truco que ya usa `ComandaPrinterTest`
 * para `PrinterService`, otra clase final) y lo expone en [comandaPrinter] para inyectárselo a
 * `ReintentoDeComanda`, que sigue dependiendo del tipo concreto tal como pide el brief.
 */
private class ComandaPrinterFalso(private val resultados: List<ComandaPrinter.Result>) {

    var llamadas = 0
        private set

    private val planesPorIntento = mutableListOf<List<TicketPlan>>()

    val comandaPrinter: ComandaPrinter = mockk {
        coEvery { printComandas(any(), any(), any(), any(), any(), any()) } coAnswers {
            planesPorIntento += firstArg<List<TicketPlan>>()
            resultados[llamadas].also { llamadas++ }
        }
    }

    /** Los planes que se mandaron en el intento número [numeroDeIntento] (1-based). */
    fun planesDelIntento(numeroDeIntento: Int): List<TicketPlan> = planesPorIntento[numeroDeIntento - 1]
}
