package com.avoqado.pos.core.domain.printing

import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.EntregaKds
import com.avoqado.pos.printing.data.EntregaPorWifi
import com.avoqado.pos.printing.data.ResultadoLegado
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.PoliticaDeReintento
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.data.TrabajoPendiente
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.KitchenDeliveryPolicy
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.ProductOverride
import com.avoqado.pos.printing.routing.RoutableItem
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        coEvery { printConfigRepository.refreshConTope(any(), any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns sinEstaciones
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)
        coEvery { printerService.autoPrintKitchenTicket(any()) } returns ResultadoLegado(intentadas = 1, fallidas = emptyList())
        coEvery { entrega.entregar(any(), any()) } returns emptySet()
        every { entrega.deviceId } returns "tablet-1"

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

        coVerify(exactly = 1) { printConfigRepository.refreshConTope("venue-1", any()) }
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

        coVerify(exactly = 0) { printConfigRepository.refreshConTope(any(), any()) }
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

        coVerify(exactly = 0) { printConfigRepository.refreshConTope(any(), any()) }
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
        coVerify(exactly = 0) { printConfigRepository.refreshConTope(any(), any()) }
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

    // MARK: - Etapa 3 del KDS (3.4): la caja decide por estación

    private val cola = mockk<SyncOutbox>(relaxed = true)
    private val entrega = mockk<EntregaPorWifi>(relaxed = true)
    private val barraSoloPantalla = StationInfo(id = "st_barra", name = "Barra", printerId = null, active = true, hasKitchenDisplay = true)
    private val conBarraSoloPantalla = PrintConfig(
        stations = listOf(StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_1", active = true), barraSoloPantalla),
        productOverrides = listOf(ProductOverride(productId = "prod_cafe", printStationId = "st_barra")),
        defaultStationId = "st_cocina",
    )
    private val cafe = RoutableItem(orderItemId = "oi_2", productId = "prod_cafe", categoryId = null, productName = "Café", quantity = 1)

    private fun despachadorConCola() = ComandaDispatcher(
        printConfigRepository,
        ReintentoDeComanda(comandaPrinter, reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
        printerService,
        cola,
        entrega,
    )

    /** La impresora imprime TODO; anota qué planes y con qué config (la de la hoja de respaldo). */
    private fun imprimeTodo(planes: MutableList<List<TicketPlan>>, configs: MutableList<PrintConfig>) {
        coEvery { comandaPrinter.printComandas(capture(planes), capture(configs), any(), any(), any()) } answers {
            val p = firstArg<List<TicketPlan>>()
            ComandaPrinter.Result(attempted = p.size, printed = p.size, skippedNoPrinter = 0, lastError = null)
        }
    }

    @Test
    fun `P1 con acuse de la pantalla la estacion solo pantalla NO se imprime ni se marca`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        val planes = mutableListOf<List<TicketPlan>>()
        val configs = mutableListOf<PrintConfig>()
        imprimeTodo(planes, configs)
        coEvery { entrega.entregar(any(), any()) } returns setOf("st_barra")

        despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(taco, cafe), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = true, origenDelFolio = "sale:ext-1",
        )

        assertEquals(listOf("st_cocina"), planes.single().map { it.stationId })
        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 sin acuse la estacion solo pantalla sale de RESPALDO aunque el servidor tenga la venta, y se marca con el folio del servidor`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        val planes = mutableListOf<List<TicketPlan>>()
        val configs = mutableListOf<PrintConfig>()
        imprimeTodo(planes, configs)
        val marca = slot<JsonObject>()
        coEvery { cola.enqueue("venue-1", "KDS_TICKET_MARK", capture(marca), any(), false) } returns "m-1"

        despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(taco, cafe), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = true, origenDelFolio = "sale:ext-1",
        )

        assertEquals(listOf("st_cocina", "st_barra"), planes.single().map { it.stationId })
        assertEquals(listOf(false, true), configs.single().stations.map { it.respaldoLocal })
        assertEquals("sale:ext-1:st_barra", marca.captured["sourceKey"]!!.jsonPrimitive.content)
        assertEquals("st_barra", marca.captured["stationId"]!!.jsonPrimitive.content)
        assertEquals("FALLBACK_PRINTED", marca.captured["action"]!!.jsonPrimitive.content)
        assertEquals("1234", marca.captured["label"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    /**
     * I-1 de la revisión (ronda 1): hoy esto se sostiene sólo porque `ReintentoDeComanda.insistir` mete las
     * `saltadas` dentro de `NoSalio.estaciones` (`ReintentoDeComanda.kt:177`). Sin esta prueba, una regresión
     * que leyera sólo `failedStations` marcaría papel que nunca existió y la pantalla escondería una comanda
     * que nadie vio.
     */
    @Test
    fun `P1 sin ninguna impresora a la mano el respaldo se salta y no se marca`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns ComandaPrinter.Result(
            attempted = 1, printed = 0, skippedNoPrinter = 1, lastError = null,
            skippedStations = listOf("Barra"), failedPlans = emptyList(),
        )

        val estado = despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = false, origenDelFolio = "sale:ext-1",
        )

        assertTrue(estado is EstadoDeComanda.NoSalio)
        assertEquals(listOf("Barra"), (estado as EstadoDeComanda.NoSalio).estaciones)
        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    /** I-1 de la revisión: dos estaciones de respaldo, una sale y la otra truena — sólo la que salió se marca. */
    @Test
    fun `P1 dos respaldos - Barra sale y Postres truena - solo Barra se marca`() = runTest {
        val postresSoloPantalla = StationInfo(id = "st_postres", name = "Postres", printerId = null, active = true, hasKitchenDisplay = true)
        val conDosRespaldos = conBarraSoloPantalla.copy(
            stations = conBarraSoloPantalla.stations + postresSoloPantalla,
            productOverrides = conBarraSoloPantalla.productOverrides + ProductOverride(productId = "prod_postre", printStationId = "st_postres"),
        )
        every { printConfigRepository.getCurrentConfig() } returns conDosRespaldos
        val postre = RoutableItem(orderItemId = "oi_3", productId = "prod_postre", categoryId = null, productName = "Pastel", quantity = 1)
        val planPostres = TicketPlan("st_postres", false, listOf(ConsolidatedLine("Pastel", 1, emptyList(), null, listOf("oi_3"))))
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns ComandaPrinter.Result(
            attempted = 2, printed = 1, skippedNoPrinter = 0, lastError = "offline",
            failedStations = listOf("Postres"), failedPlans = listOf(planPostres),
        )
        val marcas = mutableListOf<JsonObject>()
        coEvery { cola.enqueue(any(), any(), any(), any(), any()) } answers {
            marcas += thirdArg<JsonObject>()
            "m-x"
        }

        despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(cafe, postre), orderNumber = "1234", orderType = "En tienda",
            maxIntentos = 1, servidorLaTiene = false, origenDelFolio = "sale:ext-1",
        )

        assertEquals(1, marcas.size)
        assertEquals("sale:ext-1:st_barra", marcas.single()["sourceKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `P1 si el papel de respaldo NO salio no se marca - la pantalla lo mostrara al volver la red`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        val planBarra = TicketPlan("st_barra", false, listOf(ConsolidatedLine("Café", 1, emptyList(), null, listOf("oi_2"))))
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns ComandaPrinter.Result(
            attempted = 1, printed = 0, skippedNoPrinter = 0, lastError = "offline",
            failedStations = listOf("Barra"), failedPlans = listOf(planBarra),
        )

        val estado = despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "En tienda",
            maxIntentos = 1, servidorLaTiene = false, origenDelFolio = "sale:ext-1",
        )

        assertTrue(estado is EstadoDeComanda.NoSalio)
        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sin folio el respaldo se imprime igual pero no se marca`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        val planes = mutableListOf<List<TicketPlan>>()
        val configs = mutableListOf<PrintConfig>()
        imprimeTodo(planes, configs)

        despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = false, origenDelFolio = null,
        )

        assertEquals(listOf("st_barra"), planes.single().map { it.stationId })
        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sin decision del llamador (null) la estacion solo pantalla se imprime como antes`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        val planes = mutableListOf<List<TicketPlan>>()
        val configs = mutableListOf<PrintConfig>()
        imprimeTodo(planes, configs)

        despachadorConCola().dispatch(venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "Delivery")

        assertEquals(listOf("st_barra"), planes.single().map { it.stationId })
        assertEquals(listOf(false, false), configs.single().stations.map { it.respaldoLocal })
        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 un venue SIN pantallas no cambia nada ni toca la cola aunque vaya sin red`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones
        val planes = mutableListOf<List<TicketPlan>>()
        val configs = mutableListOf<PrintConfig>()
        imprimeTodo(planes, configs)

        despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(taco), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = false, origenDelFolio = "sale:ext-1",
        )

        assertEquals(conEstaciones, configs.single())
        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `si TODO va a pantallas que acusaron no imprime ni reporta`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        coEvery { entrega.entregar(any(), any()) } returns setOf("st_barra")

        val estado = despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = true, origenDelFolio = "sale:ext-1",
        )

        assertNull(estado)
        coVerify(exactly = 0) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
    }

    /**
     * M-4 de la revisión (ronda 1): en producción `trabajo` sobrevive a un reinicio de la app dentro de
     * `ComandasPendientesStore`, que lo pasa por JSON. `respaldoLocal` no es `@Transient` y `true` no es su
     * default, así que el round-trip debería conservarlo — pero nada lo fijaba. Aquí se serializa y
     * deserializa ANTES de reintentar, igual que el store real.
     */
    @Test
    fun `P1 volver a imprimir un respaldo conserva la marca de respaldo con la config vigente`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        val planes = mutableListOf<List<TicketPlan>>()
        val configs = mutableListOf<PrintConfig>()
        imprimeTodo(planes, configs)
        val trabajoOriginal = TrabajoPendiente(
            planes = listOf(TicketPlan("st_barra", false, listOf(ConsolidatedLine("Café", 1, emptyList(), null, listOf("oi_2"))))),
            config = KitchenDeliveryPolicy.conRespaldo(conBarraSoloPantalla, listOf("st_barra")),
            orderNumber = "1234",
            orderType = "En tienda",
            serverName = null,
            comboNames = emptyMap(),
            venueId = "venue-1",
            orderId = null,
        )
        val json = Json { ignoreUnknownKeys = true }
        val trabajo = json.decodeFromString(
            TrabajoPendiente.serializer(),
            json.encodeToString(TrabajoPendiente.serializer(), trabajoOriginal),
        )
        assertTrue("el round-trip de JSON tiene que conservar la marca de respaldo", trabajo.config.stations.single { it.id == "st_barra" }.respaldoLocal)

        despachadorConCola().reintentar(trabajo)

        assertEquals(listOf(false, true), configs.single().stations.map { it.respaldoLocal })
    }

    /**
     * El despacho de una RONDA corre en el ámbito del despachador (vive con la app), no en el de la pantalla que lo
     * pidió: `despacharEnFondo` regresa al instante y el estado final llega después, por su callback. Que cerrar la
     * pantalla de la mesa no lo cancela lo prueba `TableOrderRondaConLlaveTest` (Task 9).
     */
    @Test
    fun `despacharEnFondo corre el mismo despacho por pedido y en orden, y entrega cada estado por su callback`() {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones
        val estados = java.util.concurrent.CopyOnWriteArrayList<EstadoDeComanda>()
        val encabezados = java.util.concurrent.CopyOnWriteArrayList<String>()
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } answers {
            encabezados += arg<String>(3)
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)
        }

        dispatcher.despacharEnFondo(
            venueId = "venue-1",
            orderNumber = "1234",
            pedidos = listOf(
                ComandaDispatcher.Pedido(listOf(taco), "Mesa 5 · Aperitivos"),
                ComandaDispatcher.Pedido(listOf(taco), "Mesa 5 · Principales"),
            ),
            alCambiarEstado = { estados += it },
        )

        runBlocking { withTimeout(5_000) { while (estados.size < 2) delay(10) } }
        assertEquals(listOf(EstadoDeComanda.Salio, EstadoDeComanda.Salio), estados.toList())
        assertEquals(listOf("Mesa 5 · Aperitivos", "Mesa 5 · Principales"), encabezados.toList())
        coVerify(exactly = 2) { printConfigRepository.refreshConTope("venue-1", any()) }
    }

    /**
     * I-2 de la revisión (ronda 1): sin guarda por pedido, un `dispatch` que revienta se saltaba TODO lo que
     * seguía en el `for` — el mesero de "Mesa 5 · Principales" nunca se enteraba, y el único rastro quedaba en
     * el log del `CoroutineExceptionHandler`. `dispatch` está documentado como que no lanza, pero
     * `internalPrinterForRouting()` (bind AIDL, fuera del try/catch por plan) y el `alCambiarEstado` del
     * llamador sí pueden.
     */
    @Test
    fun `despacharEnFondo no deja que un pedido que truena se lleve entre pies a los que siguen`() {
        every { printConfigRepository.getCurrentConfig() } returns conEstaciones
        val estados = java.util.concurrent.CopyOnWriteArrayList<EstadoDeComanda>()
        var llamada = 0
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } answers {
            llamada++
            if (llamada == 1) throw RuntimeException("AIDL no conectó")
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)
        }

        dispatcher.despacharEnFondo(
            venueId = "venue-1",
            orderNumber = "1234",
            pedidos = listOf(
                ComandaDispatcher.Pedido(listOf(taco), "Mesa 5 · Aperitivos"),
                ComandaDispatcher.Pedido(listOf(taco), "Mesa 5 · Principales"),
            ),
            alCambiarEstado = { estados += it },
        )

        runBlocking { withTimeout(5_000) { while (estados.size < 2) delay(10) } }
        assertTrue("el primer pedido tiene que avisar NoSalio, no desaparecer", estados[0] is EstadoDeComanda.NoSalio)
        assertEquals(listOf("Mesa 5 · Aperitivos"), (estados[0] as EstadoDeComanda.NoSalio).estaciones)
        assertEquals("el segundo pedido tiene que seguir imprimiendo", EstadoDeComanda.Salio, estados[1])
    }

    @Test
    fun `P1 la entrega se guarda antes de empujar y se cierra despues del papel`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        val planes = mutableListOf<List<TicketPlan>>()
        val configs = mutableListOf<PrintConfig>()
        imprimeTodo(planes, configs)
        val entregas = slot<List<EntregaKds>>()
        coEvery { entrega.entregar(capture(entregas), any()) } returns emptySet()

        despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(taco, cafe), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = true, origenDelFolio = "sale:ext-1", orderId = "ord-1",
        )

        // Sólo Barra (sólo pantalla) se empuja: Cocina no tiene pantalla. Y la entrega lleva su trabajo de respaldo congelado.
        val e = entregas.captured.single()
        assertEquals("sale:ext-1:st_barra", e.mensaje.sourceKey)
        assertEquals("tablet-1", e.mensaje.deviceId)
        assertEquals("ord-1", e.mensaje.orderId)
        assertEquals(listOf("Café"), e.mensaje.items.map { it.productName })
        assertEquals(listOf(false, true), e.trabajoDeRespaldo!!.config.stations.map { it.respaldoLocal })
        coVerifyOrder {
            entrega.entregar(any(), any())
            comandaPrinter.printComandas(any(), any(), any(), any(), any())
            entrega.cerrar(any())
        }
    }

    @Test
    fun `impresora mas pantalla se empuja SIN trabajo de respaldo y su papel sale como hoy`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig(
            stations = listOf(StationInfo(id = "st_barra", name = "Barra", printerId = "pr_2", active = true, hasKitchenDisplay = true)),
            productOverrides = listOf(ProductOverride(productId = "prod_cafe", printStationId = "st_barra")),
            defaultStationId = "st_barra",
        )
        val planes = mutableListOf<List<TicketPlan>>()
        val configs = mutableListOf<PrintConfig>()
        imprimeTodo(planes, configs)
        val entregas = slot<List<EntregaKds>>()
        coEvery { entrega.entregar(capture(entregas), any()) } returns setOf("st_barra")

        despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = true, origenDelFolio = "sale:ext-1",
        )

        assertNull(entregas.captured.single().trabajoDeRespaldo)
        assertEquals(listOf("st_barra"), planes.single().map { it.stationId })
        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sin folio o sin decision del llamador no se empuja nada`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        imprimeTodo(mutableListOf(), mutableListOf())

        despachadorConCola().dispatch(venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "En tienda", servidorLaTiene = true, origenDelFolio = null)
        despachadorConCola().dispatch(venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "Delivery")

        coVerify(exactly = 0) { entrega.entregar(any(), any()) }
    }

    // MARK: - Etapa 3 del KDS (3.5), ronda 1 de la revisión: cuándo se borra la fila de la entrega (I2)

    @Test
    fun `P1 si insistir truena las filas de la entrega NO se borran - el replay las retoma`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } throws RuntimeException("AIDL no conectó")

        val resultado = runCatching {
            despachadorConCola().dispatch(
                venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "En tienda",
                servidorLaTiene = true, origenDelFolio = "sale:ext-1",
            )
        }

        assertTrue(resultado.isFailure)
        coVerify(exactly = 1) { entrega.entregar(any(), any()) }
        coVerify(exactly = 0) { entrega.cerrar(any()) }
        // N2 (ronda 2): se SUELTA para que el reloj del replay la retome en este mismo proceso, sin esperar a reabrir.
        coVerify(exactly = 1) { entrega.soltar(match { l -> l.map { it.mensaje.sourceKey } == listOf("sale:ext-1:st_barra") }, any()) }
    }

    @Test
    fun `P1 la fila del respaldo que TRONO se queda y la del respaldo que SI salio se borra`() = runTest {
        val postresSoloPantalla = StationInfo(id = "st_postres", name = "Postres", printerId = null, active = true, hasKitchenDisplay = true)
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla.copy(
            stations = conBarraSoloPantalla.stations + postresSoloPantalla,
            productOverrides = conBarraSoloPantalla.productOverrides + ProductOverride(productId = "prod_postre", printStationId = "st_postres"),
        )
        val postre = RoutableItem(orderItemId = "oi_3", productId = "prod_postre", categoryId = null, productName = "Pastel", quantity = 1)
        // Barra sale, Postres truena: el plan que truena es EL MISMO que se mandó (ComandaPrinter devuelve sus instancias).
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } answers {
            val postres = firstArg<List<TicketPlan>>().filter { it.stationId == "st_postres" }
            ComandaPrinter.Result(
                attempted = 2, printed = 1, skippedNoPrinter = 0, lastError = "offline",
                failedStations = listOf("Postres"), failedPlans = postres,
            )
        }
        val cerradas = slot<List<EntregaKds>>()
        coEvery { entrega.cerrar(capture(cerradas)) } returns Unit

        val estado = despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(cafe, postre), orderNumber = "1234", orderType = "En tienda",
            maxIntentos = 1, servidorLaTiene = true, origenDelFolio = "sale:ext-1",
        )

        assertTrue(estado is EstadoDeComanda.NoSalio)
        assertEquals(listOf("sale:ext-1:st_barra"), cerradas.captured.map { it.mensaje.sourceKey })
        // N2 (ronda 2): la que tronó se queda Y se suelta — el reloj del replay la retoma sin reabrir la app.
        coVerify(exactly = 1) { entrega.soltar(match { l -> l.map { it.mensaje.sourceKey } == listOf("sale:ext-1:st_postres") }, any()) }
    }

    /** Decidida: sin impresora no hay papel que reintentar (reenviarla no le inventa una); su pantalla la verá por el servidor. */
    @Test
    fun `una estacion solo pantalla sin ninguna impresora a la mano se da por decidida y su fila se borra`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns ComandaPrinter.Result(
            attempted = 1, printed = 0, skippedNoPrinter = 1, lastError = null,
            skippedStations = listOf("Barra"), failedPlans = emptyList(),
        )
        val cerradas = slot<List<EntregaKds>>()
        coEvery { entrega.cerrar(capture(cerradas)) } returns Unit

        despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = true, origenDelFolio = "sale:ext-1",
        )

        assertEquals(listOf("sale:ext-1:st_barra"), cerradas.captured.map { it.mensaje.sourceKey })
    }

    /** M9 (a): la promesa de la 3.5 — sin internet, la pantalla acusa por el WiFi del local y no sale papel. */
    @Test
    fun `P1 sin red pero con acuse la estacion solo pantalla no se imprime ni se marca`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        val planes = mutableListOf<List<TicketPlan>>()
        imprimeTodo(planes, mutableListOf())
        coEvery { entrega.entregar(any(), any()) } returns setOf("st_barra")

        despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(taco, cafe), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = false, origenDelFolio = "sale:ext-1",
        )

        assertEquals(listOf("st_cocina"), planes.single().map { it.stationId })
        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    /**
     * M5 (declarado): en línea y SIN folio no hay nada que empujar ni acuse posible. En la 3.4 la «sólo pantalla» no se
     * imprimía (el servidor alimenta su pantalla); desde la 3.5 el acuse decide, así que sale en papel de respaldo SIN
     * marca y la pantalla también la mostrará: duplicado, nunca pérdida.
     */
    @Test
    fun `en linea y sin folio la estacion solo pantalla sale en papel sin marca`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        val planes = mutableListOf<List<TicketPlan>>()
        imprimeTodo(planes, mutableListOf())

        despachadorConCola().dispatch(
            venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "En tienda",
            servidorLaTiene = true, origenDelFolio = null,
        )

        assertEquals(listOf("st_barra"), planes.single().map { it.stationId })
        coVerify(exactly = 0) { entrega.entregar(any(), any()) }
        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    /** El papel de respaldo que por fin sale («Volver a imprimir» o el reloj de la libreta) cierra su fila pendiente. */
    @Test
    fun `P1 volver a imprimir cierra la fila del respaldo que por fin salio, y un trabajo sin respaldo no toca nada`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        imprimeTodo(mutableListOf(), mutableListOf())
        val planBarra = TicketPlan("st_barra", false, listOf(ConsolidatedLine("Café", 1, emptyList(), null, listOf("oi_2"))))
        val planCocina = TicketPlan("st_cocina", false, listOf(ConsolidatedLine("Taco", 2, emptyList(), null, listOf("oi_1"))))
        val trabajo = TrabajoPendiente(
            planes = listOf(planCocina, planBarra),
            config = KitchenDeliveryPolicy.conRespaldo(conBarraSoloPantalla, listOf("st_barra")),
            orderNumber = "1234", orderType = "En tienda", serverName = null, comboNames = emptyMap(),
            venueId = "venue-1", orderId = null,
        )

        despachadorConCola().reintentar(trabajo)
        despachadorConCola().reintentar(trabajo.copy(config = conBarraSoloPantalla))

        coVerify(exactly = 1) { entrega.cerrarPorPapel("venue-1", "1234", listOf(planBarra)) }
        coVerify(exactly = 1) { entrega.cerrarPorPapel(any(), any(), any()) }
    }

    /**
     * Ronda 3 (la duda 2 de la ronda 2): un despacho CANCELADO a media impresión (se cayó su ámbito) también SUELTA sus
     * filas — bajo `NonCancellable` — para que el reloj del replay las retome en este mismo proceso.
     */
    @Test
    fun `P1 si el despacho se cancela a media impresion sus filas se sueltan`() = runTest {
        every { printConfigRepository.getCurrentConfig() } returns conBarraSoloPantalla
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        // m2 de la re-revisión 2: el `soltar` real escribe en Room, que en un contexto CANCELADO lanza. El mock hace lo
        // mismo (`ensureActive`) y sólo cuenta el soltar que TERMINÓ: sin `NonCancellable` esto se queda en 0.
        var soltadasDeVerdad = 0
        coEvery { entrega.soltar(any(), any()) } coAnswers {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            soltadasDeVerdad++
        }

        val despacho = launch {
            despachadorConCola().dispatch(
                venueId = "venue-1", lines = listOf(cafe), orderNumber = "1234", orderType = "En tienda",
                servidorLaTiene = true, origenDelFolio = "sale:ext-1",
            )
        }
        runCurrent()
        despacho.cancelAndJoin()

        coVerify(exactly = 0) { entrega.cerrar(any()) }
        coVerify(exactly = 1) { entrega.soltar(match { l -> l.map { it.mensaje.sourceKey } == listOf("sale:ext-1:st_barra") }, any()) }
        assertEquals("el soltar tiene que TERMINAR aunque el despacho esté cancelado", 1, soltadasDeVerdad)
    }
}
