package com.avoqado.pos.core.domain.printing

import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
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
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns Unit

        dispatcher = ComandaDispatcher(printConfigRepository, comandaPrinter, printerService)
    }

    // MARK: - No regresión: el camino POST-PAGO del mostrador

    @Test
    fun `sin estaciones y con fallback legado imprime UN ticket de cocina y no rutea nada`() = runTest {
        val ticketSlot = slot<KitchenTicketData>()
        coEvery { printerService.autoPrintKitchenTicket(capture(ticketSlot)) } returns Unit

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
            )

        val resultado = dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
        )

        assertEquals(1, resultado?.printed)
        assertEquals(listOf("Barra"), resultado?.skippedStations)
    }

    @Test
    fun `el camino legado devuelve null — su abanico es fire-and-forget y no sabe reportar`() = runTest {
        val resultado = dispatcher.dispatch(
            venueId = "venue-1",
            lines = listOf(taco),
            orderNumber = "1234",
            orderType = "En tienda",
            noStationsFallback = ticketLegado,
        )

        assertNull(resultado)
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
