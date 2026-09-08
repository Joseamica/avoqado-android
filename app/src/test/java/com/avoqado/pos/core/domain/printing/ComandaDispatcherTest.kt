package com.avoqado.pos.core.domain.printing

import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ResultadoLegado
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.PoliticaDeReintento
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El despachador es la pieza compartida entre el disparo POST-PAGO (mostrador) y el PRE-PAGO
 * (vale de área). Lo que estos tests fijan, en orden de importancia:
 *
 *  1. **NO REGRESIÓN.** Un venue sin vales tiene que producir las mismas llamadas de impresión que
 *     antes de que esta clase existiera: `refresh` sólo si hay venueId, el ticket legado cuando no
 *     hay estaciones, el ruteo cuando sí las hay. Nunca las dos cosas.
 *  2. Sin estaciones NO se deja de imprimir: el default rutea igual (fail-open).
 *  3. El vale de área respeta la tabla de §5.6 y jamás cae al ticket legado del mostrador.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComandaDispatcherTest {

    private val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
    private val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)

    private lateinit var dispatcher: ComandaDispatcher

    private val taco = RoutableItem(
        orderItemId = "oi_1",
        productId = "prod_1",
        categoryId = "cat_1",
        productName = "Taco",
        quantity = 2,
    )

    /** Venue sin estaciones — lo que devuelve un venue no configurado y también un POS recién
     *  instalado que nunca pudo bajarlas. */
    private val sinEstaciones = PrintConfig()

    private val conEstaciones = PrintConfig(
        stations = listOf(StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_1", active = true)),
        defaultStationId = "st_cocina",
    )

    /** Una estación DESACTIVADA no es una estación: tiene que contar como "sin estaciones". */
    private val soloEstacionInactiva = PrintConfig(
        stations = listOf(StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_1", active = false)),
        defaultStationId = "st_cocina",
    )

    private val ticketLegado = NoStationsFallback.LegacySingleTicket(
        listOf(KitchenItem(name = "Taco", quantity = 2, modifiers = listOf("Sin cebolla"), note = "bien dorado", category = "Antojitos")),
    )

    @Before
    fun setup() {
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns sinEstaciones
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())

        // 🔴 NO REGRESIÓN (Task 4): `ComandaDispatcher` ya no llama a `comandaPrinter.printComandas`
        // directo — pasa por `ReintentoDeComanda`, REAL (sin mockear: es justo lo que ya prueba
        // ReintentoDeComandaTest, y mockearlo aquí escondería si el cableado quedó bien), armado
        // con el MISMO mock de `comandaPrinter` de siempre. `ReintentoDeComanda` YA es inyectable
        // por Hilt (Tarea 3 le quitó el parámetro función de su constructor `@Inject`), así que
        // aquí también se construye directo, sin ningún rodeo.
        dispatcher = ComandaDispatcher(
            printConfigRepository,
            ReintentoDeComanda(comandaPrinter, reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
            printerService,
        )
    }

    // MARK: - No regresión: el camino POST-PAGO del mostrador

    @Test
    fun `sin estaciones y con fallback legado imprime UN ticket de cocina y no rutea nada`() = runTest {
        val ticketSlot = slot<KitchenTicketData>()
        coEvery { printerService.autoPrintKitchenTicket(capture(ticketSlot)) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())

        dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
            noStationsFallback = ticketLegado,
        )

        coVerify(exactly = 1) { printerService.autoPrintKitchenTicket(any()) }
        coVerify(exactly = 0) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }

        // El ticket sale idéntico al de antes, `category` incluida (el ruteo por estaciones no la
        // lleva, y por eso los KitchenItem los arma quien llama).
        val ticket = ticketSlot.captured
        assertEquals("1234", ticket.orderNumber)
        assertEquals("En tienda", ticket.orderType)
        assertEquals(
            listOf(KitchenItem("Taco", 2, listOf("Sin cebolla"), "bien dorado", "Antojitos")),
            ticket.items,
        )
    }

    @Test
    fun `con estaciones activas rutea y no toca el camino legado`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones
        val plansSlot = slot<List<TicketPlan>>()
        coEvery {
            comandaPrinter.printComandas(capture(plansSlot), conEstaciones, "1234", "En tienda", null)
        } returns ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)

        dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
            noStationsFallback = ticketLegado,
        )

        coVerify(exactly = 1) { printConfigRepository.refresh("venue-1") }
        coVerify(exactly = 1) { comandaPrinter.printComandas(any(), conEstaciones, "1234", "En tienda", null) }
        coVerify(exactly = 0) { printerService.autoPrintKitchenTicket(any()) }
        assertEquals(listOf("Taco"), plansSlot.captured.single().lines.map { it.productName })
        assertEquals("st_cocina", plansSlot.captured.single().stationId)
    }

    @Test
    fun `una estacion DESACTIVADA cuenta como sin estaciones y cae al ticket legado`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns soloEstacionInactiva

        dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
            noStationsFallback = ticketLegado,
        )

        coVerify(exactly = 1) { printerService.autoPrintKitchenTicket(any()) }
        coVerify(exactly = 0) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
    }

    // MARK: - Fail-open: en este dominio el fail-safe NUNCA puede ser no imprimir

    /**
     * Es el default y es deliberado (regla offline-first §4.1a): un local sin estaciones, o un POS
     * recién instalado que nunca pudo bajarlas, tiene que seguir sacando la comanda por su
     * impresora KITCHEN. El motor arma un plan sin estación ("SIN ESTACIÓN") y ComandaPrinter cae a
     * la de cocina. Un guard aquí dejaba a la cocina sin enterarse del pedido.
     */
    @Test
    fun `sin estaciones y sin fallback legado se rutea igual`() = runTest {
        val plansSlot = slot<List<TicketPlan>>()
        coEvery { comandaPrinter.printComandas(capture(plansSlot), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)

        dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "M-8",
            orderType = "Mesa 8",
        )

        coVerify(exactly = 1) { comandaPrinter.printComandas(any(), sinEstaciones, "M-8", "Mesa 8", null) }
        coVerify(exactly = 0) { printerService.autoPrintKitchenTicket(any()) }
        assertNull(plansSlot.captured.single().stationId)
    }

    @Test
    fun `sin venueId no se refresca la config pero SI se imprime`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones

        dispatcher.dispatch(
            venueId = null,
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
            noStationsFallback = ticketLegado,
        )

        coVerify(exactly = 0) { printConfigRepository.refresh(any()) }
        coVerify(exactly = 1) { comandaPrinter.printComandas(any(), conEstaciones, "1234", "En tienda", null) }
    }

    /** Sin renglones no hay nada que mandar a cocina — y ni siquiera se toca la red. */
    @Test
    fun `sin renglones no refresca ni imprime nada`() = runTest {
        dispatcher.dispatch(
            venueId = "venue-1",
            lines = emptyList(),
            orderNumber = "1234",
            orderType = "En tienda",
            noStationsFallback = ticketLegado,
        )

        coVerify(exactly = 0) { printConfigRepository.refresh(any()) }
        coVerify(exactly = 0) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { printerService.autoPrintKitchenTicket(any()) }
    }

    // MARK: - El resultado del ruteo REGRESA al caller (aviso de comanda que no salió)
    // Testarudo (2026-08-31): la comanda de barra murió en silencio durante días porque el
    // camino automático tiraba el Result. El cobro jamás se frena — pero el cajero se entera.

    @Test
    fun `dispatch devuelve el resultado del ruteo para que el caller pueda avisar`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(
                attempted = 2,
                printed = 1,
                skippedNoPrinter = 1,
                lastError = null,
                skippedStations = listOf("Barra"),
                // Un plan SALTADO nunca entra a `failedPlans` — no hay impresora que reintentar,
                // así que ReintentoDeComanda da esto por definitivo en el primer intento.
            )

        val resultado = dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
        )

        val noSalio = resultado as? EstadoDeComanda.NoSalio
        assertEquals(listOf("Barra"), noSalio?.estaciones)
    }

    /**
     * P1 #10 de la auditoría de Codex (2026-09-07). Esta prueba fijaba el DEFECTO: el camino
     * legado (venue sin estaciones) devolvía `null` siempre, así que un fallo de impresora ahí
     * era indistinguible de un éxito — sin aviso al cajero, sin reintento y sin telemetría.
     * Era exactamente el fallo silencioso que este trabajo existe para matar, conservado en la
     * rama que nadie miró. Ahora el camino legado DA UN VEREDICTO.
     */
    @Test
    fun `P1 el camino legado dice que SALIO cuando ninguna impresora falla`() = runTest {
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())

        val resultado = dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
            noStationsFallback = ticketLegado,
        )

        assertEquals(EstadoDeComanda.Salio, resultado)
    }

    /**
     * P2 #14 de la 2ª auditoría de Codex (2026-09-07). `autoPrintKitchenTicket` devolvía una
     * lista vacía tanto cuando TODAS imprimieron como cuando no había NINGUNA impresora que
     * intentarlo, y el despachador leía lo segundo como éxito.
     *
     * 🔴 Es la mentira más cara de todas: el mostrador cree que la cocina recibió el pedido.
     */
    @Test
    fun `P2 el camino legado NO canta exito cuando no habia ninguna impresora`() = runTest {
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns
            ResultadoLegado(intentadas = 0, fallidas = emptyList())

        val resultado = dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
            noStationsFallback = ticketLegado,
        )

        val noSalio = resultado as? EstadoDeComanda.NoSalio
        assertEquals(
            "cero intentos se leyó como éxito: el mostrador cree que la cocina recibió el pedido",
            "No hay ninguna impresora de cocina configurada.",
            noSalio?.causa,
        )
    }

    @Test
    fun `P1 el camino legado DICE que no salio, con el nombre de la impresora`() = runTest {
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns ResultadoLegado(intentadas = 1, fallidas = listOf("Cocina"))

        val resultado = dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
            noStationsFallback = ticketLegado,
        )

        val noSalio = resultado as? EstadoDeComanda.NoSalio
        assertEquals("un fallo del camino legado volvió a ser mudo", listOf("Cocina"), noSalio?.estaciones)
        assertEquals("1234", noSalio?.orderNumber)
        // Aquí NO hay planes que reenviar: la pantalla no debe ofrecer «Volver a imprimir».
        assertNull("el camino legado no tiene trabajo reenviable", noSalio?.trabajo)
    }

    // MARK: - maxIntentos se hereda hasta ReintentoDeComanda (ronda de arreglo 1, el KDS)

    /**
     * El KDS pasa `maxIntentos = 1` para no retener la reclamación del pedido mientras otra
     * tablet podría tomarlo. Esta prueba fija que `dispatch` REALMENTE lo hereda hasta
     * `ReintentoDeComanda.insistir` — que un fallo NO dispare ni un solo reintento.
     */
    @Test
    fun `dispatch con maxIntentos 1 no reintenta aunque la impresora siga fallando`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(
                attempted = 1,
                printed = 0,
                skippedNoPrinter = 0,
                lastError = "timeout",
                failedStations = listOf("Cocina"),
                failedPlans = listOf(TicketPlan(stationId = "st_cocina", unrouted = false, lines = emptyList())),
            )

        val resultado = dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
            maxIntentos = 1,
        )

        coVerify(exactly = 1) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
        assertTrue(resultado is EstadoDeComanda.NoSalio)
    }

    /** Sin pasar `maxIntentos`, el default sigue siendo el de siempre — el mostrador no cambia. */
    @Test
    fun `dispatch sin maxIntentos conserva el reintento completo de siempre`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(
                attempted = 1,
                printed = 0,
                skippedNoPrinter = 0,
                lastError = "timeout",
                failedStations = listOf("Cocina"),
                failedPlans = listOf(TicketPlan(stationId = "st_cocina", unrouted = false, lines = emptyList())),
            )

        dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
        )

        coVerify(exactly = PoliticaDeReintento.INTENTOS_MAXIMOS) {
            comandaPrinter.printComandas(any(), any(), any(), any(), any())
        }
    }

    // MARK: - Vale de área (PRE-PAGO) — §5.6

    @Test
    fun `IMMEDIATE dispara la comanda al emitir el vale, con el codigo como numero de orden`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones

        val impreso = dispatcher.dispatchAreaComanda(
            venueId = "venue-1",
            lines = listOf(taco),
            areaTicketCode = "9470000015",
            areaName = "Panadería",
            mode = FulfillmentMode.IMMEDIATE,
            moment = ComandaMoment.AREA_TICKET_ISSUED,
        )

        assertTrue(impreso)
        // El vale (10 dígitos) es el papel que el cliente trae; `ORD-<epoch>` no cabe en 58 mm.
        coVerify(exactly = 1) {
            comandaPrinter.printComandas(any(), conEstaciones, "9470000015", "Panadería", null)
        }
    }

    @Test
    fun `HOLD_UNTIL_PAID imprime al emitir el vale y NO cuando regresa pagado`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones

        val alEmitir = dispatcher.dispatchAreaComanda(
            venueId = "venue-1",
            lines = listOf(taco),
            areaTicketCode = "9470000015",
            areaName = "Cremería",
            mode = FulfillmentMode.HOLD_UNTIL_PAID,
            moment = ComandaMoment.AREA_TICKET_ISSUED,
        )
        val alPagar = dispatcher.dispatchAreaComanda(
            venueId = "venue-1",
            lines = listOf(taco),
            areaTicketCode = "9470000015",
            areaName = "Cremería",
            mode = FulfillmentMode.HOLD_UNTIL_PAID,
            moment = ComandaMoment.AREA_TICKET_PAID,
        )

        assertTrue(alEmitir)
        assertFalse(alPagar)
        coVerify(exactly = 1) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `PREPARE_ON_PAID no imprime al emitir y si al regresar pagado, con el sello PAGADO`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones

        val alEmitir = dispatcher.dispatchAreaComanda(
            venueId = "venue-1",
            lines = listOf(taco),
            areaTicketCode = "9470000015",
            areaName = "Cafetería",
            mode = FulfillmentMode.PREPARE_ON_PAID,
            moment = ComandaMoment.AREA_TICKET_ISSUED,
        )
        assertFalse(alEmitir)
        // Un modo que no toca no debe ni refrescar la config: cero efectos.
        coVerify(exactly = 0) { printConfigRepository.refresh(any()) }
        coVerify(exactly = 0) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }

        val alPagar = dispatcher.dispatchAreaComanda(
            venueId = "venue-1",
            lines = listOf(taco),
            areaTicketCode = "9470000015",
            areaName = "Cafetería",
            mode = FulfillmentMode.PREPARE_ON_PAID,
            moment = ComandaMoment.AREA_TICKET_PAID,
        )
        assertTrue(alPagar)
        coVerify(exactly = 1) {
            comandaPrinter.printComandas(any(), conEstaciones, "9470000015", "Cafetería · PAGADO", null)
        }
    }

    /** El vale NUNCA cae al ticket legado del mostrador: rutea siempre (fail-open). */
    @Test
    fun `el vale rutea aunque el venue no tenga estaciones`() = runTest {
        val impreso = dispatcher.dispatchAreaComanda(
            venueId = "venue-1",
            lines = listOf(taco),
            areaTicketCode = "9470000015",
            areaName = null,
            mode = FulfillmentMode.IMMEDIATE,
            moment = ComandaMoment.AREA_TICKET_ISSUED,
        )

        assertTrue(impreso)
        coVerify(exactly = 1) {
            comandaPrinter.printComandas(any(), sinEstaciones, "9470000015", "Vale de área", null)
        }
        coVerify(exactly = 0) { printerService.autoPrintKitchenTicket(any()) }
    }
}
