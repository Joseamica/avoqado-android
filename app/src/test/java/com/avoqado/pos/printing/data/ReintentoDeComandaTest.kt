package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** «La libreta» (Task 16) por defecto no espera nada de nadie — relajado a propósito en las
 *  pruebas que no la conciernen; las que SÍ la conciernen viven al final del archivo y verifican
 *  contra su propio mock, no contra éste. */
private fun reporteDeComandasFalso(): ReporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)

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
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { esperas += it }, reporteDeComandas = reporteDeComandasFalso())

        val estado = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap())

        assertEquals(EstadoDeComanda.Salio, estado)
        assertEquals(emptyList<Long>(), esperas)
        assertEquals(1, printer.llamadas)
    }

    @Test
    fun `si el primero truena y el segundo sale, sale sola sin molestar a nadie`() = runTest {
        val esperas = mutableListOf<Long>()
        val printer = ComandaPrinterFalso(resultados = listOf(fallo("Cocina"), exito()))
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { esperas += it }, reporteDeComandas = reporteDeComandasFalso())

        val estado = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap())

        assertEquals(EstadoDeComanda.Salio, estado)
        assertEquals(listOf(2_000L), esperas)
        assertEquals(2, printer.llamadas)
    }

    @Test
    fun `se rinde tras seis intentos y reporta la causa REAL del ultimo`() = runTest {
        val printer = ComandaPrinterFalso(resultados = List(6) { fallo("Cocina", causa = "timeout 192.168.1.141:9100") })
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = reporteDeComandasFalso())

        val estado = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap())

        val fallo = estado as EstadoDeComanda.NoSalio
        assertEquals(listOf("Cocina"), fallo.estaciones)
        assertEquals("timeout 192.168.1.141:9100", fallo.causa)
        assertEquals("ORD-1", fallo.orderNumber)
        // El aviso tiene que poder reimprimir SÓLO lo que faltó — si `trabajo` fuera nulo, el
        // botón «Volver a imprimir» no tendría qué mandar.
        assertEquals(listOf(planCocina), fallo.trabajo?.planes)
        assertEquals(6, printer.llamadas)
    }

    @Test
    fun `solo reintenta las estaciones que TRONARON, no las que ya salieron`() = runTest {
        val printer = ComandaPrinterFalso(
            resultados = listOf(fallo("Cocina", planesQueFallaron = listOf(planCocina)), exito()),
        )
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = reporteDeComandasFalso())

        sut.insistir(listOf(planCocina, planBarra), config, "ORD-1", "En tienda", null, emptyMap())

        // El segundo intento manda SOLO el plan de cocina — reimprimir barra seria un ticket duplicado.
        assertEquals(listOf(planCocina), printer.planesDelIntento(2))
    }

    @Test
    fun `una estacion SALTADA no se reintenta ni una vez`() = runTest {
        val printer = ComandaPrinterFalso(resultados = listOf(saltada("Barra")))
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = reporteDeComandasFalso())

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
                // Intento 1: se manda TODO y truenan las dos.
                ComandaPrinter.Result(
                    attempted = 2, printed = 0, skippedNoPrinter = 0, lastError = "timeout",
                    failedStations = listOf("Cocina", "Barra"),
                    failedPlans = listOf(planCocina, planBarra),
                ),
                // Intento 2: se reintentan las DOS y sólo queda Barra — así las estaciones del
                // aviso cambian de verdad, sin fabricar un estado imposible (un intento no puede
                // reportar como fallido un plan que no recibió; el helper ahora lo exige).
                ComandaPrinter.Result(
                    attempted = 2, printed = 1, skippedNoPrinter = 0, lastError = "sin papel",
                    failedStations = listOf("Barra"),
                    failedPlans = listOf(planBarra),
                ),
                exito(),
            ),
        )
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = reporteDeComandasFalso())

        val estadoFinal = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap()) { estados += it }

        assertEquals(
            listOf(
                // Tras el intento 1 (Cocina) se avisa que YA VIENE el intento 2 — no el 1, que
                // acaba de terminar. Y las estaciones son las de ESTE fallo, no un eco del anterior.
                EstadoDeComanda.Insistiendo(2, PoliticaDeReintento.INTENTOS_MAXIMOS, listOf("Cocina", "Barra"), "ORD-1"),
                // Tras el intento 2 se avisa el 3 — ya sólo con Barra, no con las dos.
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
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = reporteDeComandasFalso())

        val estadoFinal = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap()) { estados += it }

        val ultimoEstado = estados.last() as EstadoDeComanda.NoSalio
        assertEquals(listOf("Cocina"), ultimoEstado.estaciones)
        assertEquals("timeout 192.168.1.141:9100", ultimoEstado.causa)
        assertEquals("ORD-1", ultimoEstado.orderNumber)
        assertEquals(listOf(planCocina), ultimoEstado.trabajo?.planes)
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
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { esperas += it }, reporteDeComandas = reporteDeComandasFalso())

        val estadoFinal = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap(), maxIntentos = 1) {
            estados += it
        }

        assertEquals(1, printer.llamadas)
        assertEquals(emptyList<Long>(), esperas)
        assertTrue(estados.none { it is EstadoDeComanda.Insistiendo })
        val finalNoSalio = estadoFinal as EstadoDeComanda.NoSalio
        assertEquals(listOf("Cocina"), finalNoSalio.estaciones)
        assertEquals("timeout", finalNoSalio.causa)
        assertEquals("ORD-1", finalNoSalio.orderNumber)
        assertEquals(listOf(planCocina), finalNoSalio.trabajo?.planes)
    }

    /** El default sigue siendo el de siempre: `PoliticaDeReintento.INTENTOS_MAXIMOS` (6, ~1 min). */
    @Test
    fun `sin pasar maxIntentos el tope sigue siendo el de PoliticaDeReintento`() = runTest {
        val printer = ComandaPrinterFalso(resultados = List(6) { fallo("Cocina", causa = "timeout") })
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = reporteDeComandasFalso())

        sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap())

        assertEquals(PoliticaDeReintento.INTENTOS_MAXIMOS, printer.llamadas)
    }

    // MARK: - «La libreta» (Task 16): AL TERMINAR, insistir() reporta — nunca a media insistencia

    @Test
    fun `al salir reporta UN agregado, sin estacion, con el venueId y el orderId reales`() = runTest {
        val reporte = mockk<ReporteDeComandas>(relaxed = true)
        val printer = ComandaPrinterFalso(resultados = listOf(exito()))
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = reporte)

        sut.insistir(
            planes, config, "ORD-1", "En tienda", null, emptyMap(),
            venueId = "venue-1", orderId = "order-1",
        )

        coVerify(exactly = 1) {
            reporte.reportar(
                venueId = "venue-1",
                orderId = "order-1",
                orderNumber = "ORD-1",
                estado = EstadoDeComanda.Salio,
                intentos = 1,
                stationId = null,
                printerId = null,
            )
        }
    }

    @Test
    fun `al rendirse reporta UNA vez POR ESTACION que de verdad tronó, con su stationId`() = runTest {
        val reporte = mockk<ReporteDeComandas>(relaxed = true)
        val printer = ComandaPrinterFalso(resultados = List(6) { fallo("Cocina", causa = "timeout") })
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = reporte)

        val estadoFinal = sut.insistir(
            planes, config, "ORD-1", "En tienda", null, emptyMap(),
            venueId = "venue-1", orderId = "order-1",
        )

        coVerify(exactly = 1) {
            reporte.reportar(
                venueId = "venue-1",
                orderId = "order-1",
                orderNumber = "ORD-1",
                estado = estadoFinal,
                intentos = PoliticaDeReintento.INTENTOS_MAXIMOS,
                stationId = "st_cocina",
                printerId = null,
            )
        }
    }

    @Test
    fun `una estacion SALTADA sin failedPlans reporta AGREGADO en vez de no reportar nada`() = runTest {
        val reporte = mockk<ReporteDeComandas>(relaxed = true)
        val printer = ComandaPrinterFalso(resultados = listOf(saltada("Barra")))
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = reporte)

        val estadoFinal = sut.insistir(
            planes, config, "ORD-1", "En tienda", null, emptyMap(),
            venueId = "venue-1", orderId = "order-1",
        )

        coVerify(exactly = 1) {
            reporte.reportar(
                venueId = "venue-1",
                orderId = "order-1",
                orderNumber = "ORD-1",
                estado = estadoFinal,
                intentos = 1,
                stationId = null,
                printerId = null,
            )
        }
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
        coEvery { printComandas(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val recibidos = firstArg<List<TicketPlan>>()
            planesPorIntento += recibidos
            val resultado = resultados[llamadas]
            // 🔴 Un intento NO puede reportar como fallido un plan que nunca recibió. Sin esta
            // comprobación, una prueba puede fijar un estado IMPOSIBLE y pasar sin ejercitar
            // nada — pasó de verdad: la prueba de la secuencia devolvía `planBarra` en un
            // intento que sólo llevaba `planCocina`, y aun así acusaba en verde.
            val intrusos = resultado.failedPlans.filterNot { it in recibidos }
            require(intrusos.isEmpty()) {
                "El intento ${llamadas + 1} recibió ${recibidos.map { it.stationId }} pero el " +
                    "resultado dice que fallaron ${intrusos.map { it.stationId }} — estado imposible."
            }
            resultado.also { llamadas++ }
        }
    }

    /** Los planes que se mandaron en el intento número [numeroDeIntento] (1-based). */
    fun planesDelIntento(numeroDeIntento: Int): List<TicketPlan> = planesPorIntento[numeroDeIntento - 1]
}

// ═══════════════════════════════════════════════════════════════════════════════════════════
// Ronda de arreglo tras la auditoría de Codex (gpt-6-astra, 2026-09-07): P1 #1, #2 y #3.
// Los tres salen de la MISMA raíz — el estado `NoSalio` no sabía QUÉ faltó ni DE QUÉ venta,
// sólo nombres para pintar en pantalla. Con eso, el veredicto olvidaba estaciones saltadas y
// el botón «Volver a imprimir» tenía que adivinar reconstruyendo el carrito ACTUAL.
// ═══════════════════════════════════════════════════════════════════════════════════════════

class ReintentoDeComandaRondaDeArregloTest {

    private val cocinaPrinterInfo = PrinterInfo("pr_cocina", "Cocina Printer", "NETWORK", "192.168.1.50:9100")
    private val cocinaStation = StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_cocina", copies = 1)
    private val config = PrintConfig(printers = listOf(cocinaPrinterInfo), stations = listOf(cocinaStation))

    private val planCocina = TicketPlan("st_cocina", false, listOf(ConsolidatedLine("Taco", 2, emptyList(), null, listOf("oi_1"))))
    private val planBarra = TicketPlan("st_barra", false, listOf(ConsolidatedLine("Cerveza", 1, emptyList(), null, listOf("oi_2"))))
    private val planes = listOf(planCocina, planBarra)

    /**
     * P1 #1 de Codex. Barra se SALTA en el intento 1 (sin impresora resoluble) y Cocina falla.
     * El intento 2 sólo lleva Cocina, que sale. El veredicto se calculaba sobre el ÚLTIMO
     * resultado, así que Barra —que nunca imprimió— desaparecía y el cajero veía «Salio».
     *
     * 🔴 Es el bug original regresando por otra puerta: una comanda que no salió y nadie avisa.
     */
    @Test
    fun `P1 una estacion SALTADA en el primer intento no desaparece del veredicto`() = runTest {
        val intento1 = ComandaPrinter.Result(
            attempted = 2, printed = 0, skippedNoPrinter = 1, lastError = "timeout",
            failedStations = listOf("Cocina"), skippedStations = listOf("Barra"),
            failedPlans = listOf(planCocina),
        )
        val intento2 = ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)
        val printer = ComandaPrinterFalso(listOf(intento1, intento2))
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = mockk(relaxed = true))

        val estado = sut.insistir(planes, config, "ORD-1", "En tienda", null, emptyMap())

        assertTrue("Barra nunca imprimió y el veredicto dijo que todo salió", estado is EstadoDeComanda.NoSalio)
        assertEquals(listOf("Barra"), (estado as EstadoDeComanda.NoSalio).estaciones)
    }

    /**
     * P1 #2 de Codex. El aviso tiene que llevar EXACTAMENTE lo que faltó — nunca el lote
     * entero. Si Cocina imprimió y Barra no, reenviar todo le manda a Cocina un ticket
     * duplicado, y en una cocina eso es un platillo de más.
     */
    @Test
    fun `P1 NoSalio lleva SOLO los planes que faltaron, nunca los que si salieron`() = runTest {
        val soloBarraFalla = ComandaPrinter.Result(
            attempted = 2, printed = 1, skippedNoPrinter = 0, lastError = "sin papel",
            failedStations = listOf("Barra"), failedPlans = listOf(planBarra),
        )
        val printer = ComandaPrinterFalso(List(PoliticaDeReintento.INTENTOS_MAXIMOS) { soloBarraFalla })
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = mockk(relaxed = true))

        val estado = sut.insistir(planes, config, "ORD-7", "En tienda", "Ana", emptyMap(), venueId = "v1", orderId = "o1")

        val trabajo = (estado as EstadoDeComanda.NoSalio).trabajo
        assertEquals("sin el trabajo pendiente, reimprimir tiene que adivinar", listOf(planBarra), trabajo?.planes)
        assertEquals("ORD-7", trabajo?.orderNumber)
        assertEquals("Ana", trabajo?.serverName)
        assertEquals("o1", trabajo?.orderId)
    }

    /**
     * P1 #2/#3 de Codex. Reintentar a mano manda ese trabajo y NADA MÁS: ni el lote original,
     * ni el carrito que el cajero tenga enfrente en ese momento.
     */
    @Test
    fun `P1 reintentar manda EXACTAMENTE los planes pendientes, no el lote entero`() = runTest {
        val soloBarraFalla = ComandaPrinter.Result(
            attempted = 2, printed = 1, skippedNoPrinter = 0, lastError = "sin papel",
            failedStations = listOf("Barra"), failedPlans = listOf(planBarra),
        )
        val printer = ComandaPrinterFalso(List(PoliticaDeReintento.INTENTOS_MAXIMOS + 1) { soloBarraFalla })
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = mockk(relaxed = true))
        val fallo = sut.insistir(planes, config, "ORD-7", "En tienda", null, emptyMap()) as EstadoDeComanda.NoSalio
        val llamadasAntes = printer.llamadas

        sut.reintentar(fallo.trabajo!!, maxIntentos = 1)

        assertEquals("reintentar tiene que mandar exactamente un lote", llamadasAntes + 1, printer.llamadas)
        assertEquals(listOf(planBarra), printer.planesDelIntento(llamadasAntes + 1))
    }

    /**
     * P1 #1 de la 2ª auditoría de Codex (2026-09-07). El arreglo de las copias sólo protegía los
     * intentos del MISMO ciclo automático; el reintento manual arranca un ciclo nuevo, y ahí
     * volvía a mandar las copias que ya habían salido.
     */
    @Test
    fun `P1 el trabajo congelado lleva las copias que faltan, para que reimprimir no las repita`() = runTest {
        val faltaUna = ComandaPrinter.Result(
            attempted = 1, printed = 0, skippedNoPrinter = 0, lastError = "sin papel",
            failedStations = listOf("Cocina"), failedPlans = listOf(planCocina),
            copiasPendientes = mapOf<String?, Int>("st_cocina" to 1),
        )
        val printer = ComandaPrinterFalso(List(PoliticaDeReintento.INTENTOS_MAXIMOS) { faltaUna })
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = mockk(relaxed = true))

        val fallo = sut.insistir(listOf(planCocina), config, "ORD-9", "En tienda", null, emptyMap()) as EstadoDeComanda.NoSalio

        assertEquals(
            "sin esto, «Volver a imprimir» reimprime las copias que ya salieron",
            mapOf<String?, Int>("st_cocina" to 1),
            fallo.trabajo?.copiasPendientes,
        )
    }

    /**
     * P1 #2 de la 2ª auditoría. `saltadas` vivía sólo dentro de una llamada a `insistir`: al
     * reintentar a mano, la estación sin impresora desaparecía del veredicto y el aviso se
     * borraba entero aunque esa estación nunca hubiera impreso.
     */
    @Test
    fun `P1 una estacion saltada sigue contando al reintentar A MANO`() = runTest {
        val cocinaFallaBarraSaltada = ComandaPrinter.Result(
            attempted = 2, printed = 0, skippedNoPrinter = 1, lastError = "timeout",
            failedStations = listOf("Cocina"), skippedStations = listOf("Barra"),
            failedPlans = listOf(planCocina),
        )
        val printer = ComandaPrinterFalso(
            List(PoliticaDeReintento.INTENTOS_MAXIMOS) { cocinaFallaBarraSaltada } +
                // El reintento manual: Cocina ya se reparó y sale.
                ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null),
        )
        val sut = ReintentoDeComanda(printer.comandaPrinter, esperar = { }, reporteDeComandas = mockk(relaxed = true))
        val fallo = sut.insistir(planes, config, "ORD-9", "En tienda", null, emptyMap()) as EstadoDeComanda.NoSalio
        assertEquals(listOf("Barra"), fallo.trabajo?.saltadas)

        val trasReintentar = sut.reintentar(fallo.trabajo!!, maxIntentos = 1)

        assertTrue(
            "Cocina imprimió y el aviso desapareció entero, con Barra todavía sin comanda",
            trasReintentar is EstadoDeComanda.NoSalio,
        )
        assertEquals(listOf("Barra"), (trasReintentar as EstadoDeComanda.NoSalio).estaciones)
    }
}
