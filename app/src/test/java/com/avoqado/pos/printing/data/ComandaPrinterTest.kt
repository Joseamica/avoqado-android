package com.avoqado.pos.printing.data

import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.data.model.PrinterConnectionType
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComandaPrinterTest {

    private val printerService = mockk<PrinterService>(relaxed = true)
    private lateinit var comandaPrinter: ComandaPrinter

    private val cocinaPrinterInfo = PrinterInfo(
        id = "pr_cocina",
        name = "Cocina Printer",
        connectionType = "NETWORK",
        address = "192.168.1.50:9100",
    )
    private val barraPrinterInfo = PrinterInfo(
        id = "pr_barra",
        name = "Barra Printer",
        connectionType = "NETWORK",
        address = "192.168.1.51", // no explicit port -> default 9100
    )
    private val bluetoothPrinterInfo = PrinterInfo(
        id = "pr_bt",
        name = "Bluetooth Printer",
        connectionType = "BLUETOOTH",
        address = "AA:BB:CC:DD:EE:FF",
    )
    private val usbSpoolerPrinterInfo = PrinterInfo(
        id = "pr_usb",
        name = "USB Spooler Printer",
        connectionType = "USB_SPOOLER",
        address = "usb-spooler-1",
    )

    // POS_INTERNAL: la integrada del PROPIO aparato — sin dirección a propósito.
    // El server la registra con paperWidthMm 80 (su default); el ancho real lo pone el hardware.
    private val posInternalPrinterInfo = PrinterInfo(
        id = "pr_pos",
        name = "Integrada del punto",
        connectionType = "POS_INTERNAL",
        address = null,
    )
    private val cocinaStation = StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_cocina", copies = 1)
    private val barraStation = StationInfo(id = "st_barra", name = "Barra", printerId = "pr_barra", copies = 2)
    private val orphanStation = StationInfo(id = "st_orphan", name = "Postres", printerId = null)
    private val bluetoothStation = StationInfo(id = "st_bt", name = "Bluetooth Station", printerId = "pr_bt", copies = 1)
    private val usbSpoolerStation = StationInfo(id = "st_usb", name = "USB Station", printerId = "pr_usb", copies = 1)
    private val posInternalStation = StationInfo(id = "st_pos", name = "Barra integrada", printerId = "pr_pos", copies = 1)

    private val config = PrintConfig(
        printers = listOf(cocinaPrinterInfo, barraPrinterInfo, bluetoothPrinterInfo, usbSpoolerPrinterInfo, posInternalPrinterInfo),
        stations = listOf(cocinaStation, barraStation, orphanStation, bluetoothStation, usbSpoolerStation, posInternalStation),
    )

    private val integradaDelAparato = SavedPrinter(
        id = "internal",
        name = "Impresora integrada",
        connectionType = PrinterConnectionType.INTERNAL.value,
        address = "internal",
        paperWidthMm = 58,
    )

    private fun plan(stationId: String?, lines: List<ConsolidatedLine>) =
        TicketPlan(stationId = stationId, unrouted = stationId == null, lines = lines)

    private val tacoLine = ConsolidatedLine("Taco", 2, emptyList(), null, listOf("oi_1"))
    private val cervezaLine = ConsolidatedLine("Cerveza", 1, emptyList(), null, listOf("oi_2"))

    @Before
    fun setup() {
        comandaPrinter = ComandaPrinter(printerService)
    }

    // MARK: - resolve() — pure routing/target-resolution logic

    @Test
    fun `resolve picks the station's configured printer with parsed host and port`() {
        val resolved = comandaPrinter.resolve(
            plan("st_cocina", listOf(tacoLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
        )

        assertEquals("192.168.1.50", resolved.savedPrinter?.address)
        assertEquals(9100, resolved.savedPrinter?.port)
        assertEquals("Cocina", resolved.stationLabel)
        assertEquals(1, resolved.copies)
        assertEquals(listOf("Taco"), resolved.ticket.items.map { it.name })
        assertEquals("Cocina", resolved.ticket.stationName)
    }

    @Test
    fun `resolve defaults to port 9100 when the printer address has none`() {
        val resolved = comandaPrinter.resolve(
            plan("st_barra", listOf(cervezaLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
        )

        assertEquals("192.168.1.51", resolved.savedPrinter?.address)
        assertEquals(9100, resolved.savedPrinter?.port)
        assertEquals(2, resolved.copies) // station copies = 2
    }

    @Test
    fun `resolve returns null printer for the unrouted bucket and labels SIN ESTACION`() {
        val resolved = comandaPrinter.resolve(
            plan(null, listOf(tacoLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
        )

        assertNull(resolved.savedPrinter)
        assertEquals("SIN ESTACIÓN", resolved.stationLabel)
        assertEquals("SIN ESTACIÓN", resolved.ticket.stationName)
    }

    @Test
    fun `resolve returns null printer for a station with no printer assigned but keeps its name`() {
        val resolved = comandaPrinter.resolve(
            plan("st_orphan", listOf(tacoLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
        )

        assertNull(resolved.savedPrinter)
        assertEquals("Postres", resolved.stationLabel) // NOT "SIN ESTACIÓN" — station is known, just unwired
    }

    @Test
    fun `resolve falls back to SIN ESTACION for a stationId not present in the config (deleted station)`() {
        val resolved = comandaPrinter.resolve(
            plan("st_deleted", listOf(tacoLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
        )

        assertNull(resolved.savedPrinter)
        assertEquals("SIN ESTACIÓN", resolved.stationLabel)
    }

    // MARK: - printComandas() — orchestration against a mocked PrinterService

    @Test
    fun `printComandas prints each routed plan to its own resolved printer`() = runTest {
        val plans = listOf(
            plan("st_cocina", listOf(tacoLine)),
            plan("st_barra", listOf(cervezaLine)),
        )
        val printerSlots = mutableListOf<SavedPrinter>()
        coEvery { printerService.printKitchenTicket(any(), capture(printerSlots)) } returns Unit

        comandaPrinter.printComandas(plans, config, orderNumber = "9999")

        // Cocina: 1 copy, Barra: 2 copies (station.copies)
        assertEquals(3, printerSlots.size)
        assertTrue(printerSlots.any { it.address == "192.168.1.50" })
        assertEquals(2, printerSlots.count { it.address == "192.168.1.51" })
    }

    @Test
    fun `printComandas falls back the unrouted plan to the default KITCHEN printer`() = runTest {
        val fallback = SavedPrinter(
            id = "default-kitchen",
            name = "Default Kitchen",
            connectionType = "wifi",
            address = "10.0.0.5",
            port = 9100,
        )
        every { printerService.getDefaultPrinter(PrinterRole.KITCHEN) } returns fallback
        val ticketSlot = mutableListOf<KitchenTicketData>()
        coEvery { printerService.printKitchenTicket(capture(ticketSlot), fallback) } returns Unit

        comandaPrinter.printComandas(listOf(plan(null, listOf(tacoLine))), config, orderNumber = "9999")

        coVerify(exactly = 1) { printerService.printKitchenTicket(any(), fallback) }
        assertEquals("SIN ESTACIÓN", ticketSlot.first().stationName)
    }

    @Test
    fun `printComandas skips a plan with no resolvable printer and no default KITCHEN printer, without crashing`() = runTest {
        every { printerService.getDefaultPrinter(PrinterRole.KITCHEN) } returns null

        comandaPrinter.printComandas(listOf(plan(null, listOf(tacoLine))), config, orderNumber = "9999")

        coVerify(exactly = 0) { printerService.printKitchenTicket(any(), any()) }
    }

    @Test
    fun `printComandas keeps printing remaining plans when one plan's printer throws`() = runTest {
        val goodTicketSlot = mutableListOf<KitchenTicketData>()
        coEvery { printerService.printKitchenTicket(any(), match { it.address == "192.168.1.50" }) } throws
            RuntimeException("printer offline")
        coEvery { printerService.printKitchenTicket(capture(goodTicketSlot), match { it.address == "192.168.1.51" }) } returns Unit

        val plans = listOf(
            plan("st_cocina", listOf(tacoLine)), // will throw
            plan("st_barra", listOf(cervezaLine)), // must still print
        )

        comandaPrinter.printComandas(plans, config, orderNumber = "9999")

        // Barra (2 copies) still printed despite Cocina failing
        assertEquals(2, goodTicketSlot.size)
    }

    // MARK: - Bluetooth / unsupported-transport connectionType mapping

    @Test
    fun `resolve maps a BLUETOOTH station printer to a Bluetooth SavedPrinter with the MAC intact`() {
        val resolved = comandaPrinter.resolve(
            plan("st_bt", listOf(tacoLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
        )

        // Regression guard: a MAC contains ':' — must NOT be run through the host:port
        // parser used for NETWORK printers, or it would get truncated/corrupted.
        assertEquals(PrinterConnectionType.BLUETOOTH, resolved.savedPrinter?.connectionTypeEnum)
        assertEquals("AA:BB:CC:DD:EE:FF", resolved.savedPrinter?.address)
        assertNull(resolved.savedPrinter?.port)
    }

    @Test
    fun `printComandas prints the Bluetooth station using the SavedPrinter's MAC as-is`() = runTest {
        val printerSlots = mutableListOf<SavedPrinter>()
        coEvery { printerService.printKitchenTicket(any(), capture(printerSlots)) } returns Unit

        comandaPrinter.printComandas(listOf(plan("st_bt", listOf(tacoLine))), config, orderNumber = "9999")

        assertEquals(1, printerSlots.size)
        assertEquals(PrinterConnectionType.BLUETOOTH, printerSlots.first().connectionTypeEnum)
        assertEquals("AA:BB:CC:DD:EE:FF", printerSlots.first().address)
    }

    @Test
    fun `resolve returns null for an unsupported connectionType like USB_SPOOLER`() {
        val resolved = comandaPrinter.resolve(
            plan("st_usb", listOf(tacoLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
        )

        assertNull(resolved.savedPrinter)
        // Station is known, so it keeps its real name (not the unrouted "SIN ESTACIÓN" bucket).
        assertEquals("USB Station", resolved.stationLabel)
    }

    @Test
    fun `printComandas skips a USB_SPOOLER station and still prints the other stations`() = runTest {
        every { printerService.getDefaultPrinter(PrinterRole.KITCHEN) } returns null
        val printerSlots = mutableListOf<SavedPrinter>()
        coEvery { printerService.printKitchenTicket(any(), capture(printerSlots)) } returns Unit

        val plans = listOf(
            plan("st_usb", listOf(tacoLine)), // unsupported transport, no default configured -> skipped
            plan("st_cocina", listOf(tacoLine)), // must still print
        )

        comandaPrinter.printComandas(plans, config, orderNumber = "9999")

        assertEquals(1, printerSlots.size)
        assertEquals("192.168.1.50", printerSlots.first().address)
    }

    // MARK: - POS_INTERNAL — la comanda sale en el aparato que cobró
    // Caso real (Testarudo, 2026-08-31): la estación Barra apuntaba a una impresora
    // NETWORK con la IP del propio Sunmi y las comandas de barra morían en silencio.

    @Test
    fun `resolve maps a POS_INTERNAL station printer to this device's integrated printer`() {
        val resolved = comandaPrinter.resolve(
            plan("st_pos", listOf(cervezaLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
            internalPrinter = integradaDelAparato,
        )

        assertEquals(PrinterConnectionType.INTERNAL, resolved.savedPrinter?.connectionTypeEnum)
        // El ancho lo manda el HARDWARE (58), no el default del server (80): ESC/POS de
        // 80 columnas en un cabezal de 58 sale con las líneas cortadas.
        assertEquals(58, resolved.savedPrinter?.paperWidthMm)
        assertTrue(resolved.savedPrinter?.hasRole(PrinterRole.KITCHEN) == true)
        assertEquals("Barra integrada", resolved.stationLabel)
        assertEquals("Barra integrada", resolved.ticket.stationName)
    }

    @Test
    fun `resolve returns null for POS_INTERNAL when this device has no integrated printer`() {
        // internalPrinter = null (default): una T3 sin cabezal, o un aparato ajeno.
        val resolved = comandaPrinter.resolve(
            plan("st_pos", listOf(cervezaLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
        )

        assertNull(resolved.savedPrinter)
        // La estación se conoce: conserva su nombre para que el fallback lo imprima en el ticket.
        assertEquals("Barra integrada", resolved.stationLabel)
    }

    @Test
    fun `printComandas prints a POS_INTERNAL station on the device's integrated printer`() = runTest {
        coEvery { printerService.internalPrinterForRouting() } returns integradaDelAparato
        val printerSlots = mutableListOf<SavedPrinter>()
        coEvery { printerService.printKitchenTicket(any(), capture(printerSlots)) } returns Unit

        comandaPrinter.printComandas(listOf(plan("st_pos", listOf(cervezaLine))), config, orderNumber = "9999")

        assertEquals(1, printerSlots.size)
        assertEquals(PrinterConnectionType.INTERNAL, printerSlots.first().connectionTypeEnum)
    }

    @Test
    fun `printComandas falls back a POS_INTERNAL station to the default KITCHEN printer on a device with no head`() = runTest {
        coEvery { printerService.internalPrinterForRouting() } returns null
        val fallback = SavedPrinter(
            id = "default-kitchen",
            name = "Default Kitchen",
            connectionType = "wifi",
            address = "10.0.0.5",
            port = 9100,
        )
        every { printerService.getDefaultPrinter(PrinterRole.KITCHEN) } returns fallback

        comandaPrinter.printComandas(listOf(plan("st_pos", listOf(cervezaLine))), config, orderNumber = "9999")

        coVerify(exactly = 1) { printerService.printKitchenTicket(any(), fallback) }
    }

    /**
     * El Result NOMBRA las estaciones cuya comanda no salió — sin nombres, el aviso al
     * cajero sólo podría decir "algo falló" y nadie sabría qué impresora revisar.
     */
    @Test
    fun `Result nombra las estaciones cuya comanda no salio, separando fallo de salto`() = runTest {
        every { printerService.getDefaultPrinter(PrinterRole.KITCHEN) } returns null
        coEvery { printerService.printKitchenTicket(any(), match { it.address == "192.168.1.50" }) } throws
            RuntimeException("printer offline")

        val result = comandaPrinter.printComandas(
            listOf(
                plan("st_cocina", listOf(tacoLine)), // la impresora truena → failed
                plan("st_usb", listOf(cervezaLine)), // transporte no servible, sin default → skipped
            ),
            config,
            orderNumber = "9999",
        )

        assertEquals(listOf("Cocina"), result.failedStations)
        assertEquals(listOf("USB Station"), result.skippedStations)
        assertEquals(0, result.printed)
    }

    /** La consulta a la integrada es PEREZOSA: un venue sin impresora POS_INTERNAL no paga el bind. */
    @Test
    fun `printComandas does not query the integrated printer when no POS_INTERNAL printer exists in the config`() = runTest {
        val configSinPosInternal = PrintConfig(
            printers = listOf(cocinaPrinterInfo),
            stations = listOf(cocinaStation),
        )
        coEvery { printerService.printKitchenTicket(any(), any()) } returns Unit

        comandaPrinter.printComandas(listOf(plan("st_cocina", listOf(tacoLine))), configSinPosInternal, orderNumber = "9999")

        coVerify(exactly = 0) { printerService.internalPrinterForRouting() }
    }

    // MARK: - Combos en la comanda (founder 2026-08-18, patrón Fudo)

    /**
     * El caso que de verdad cuesta: un combo cuyos productos se reparten entre
     * cocina y barra. Cada estación tiene que encabezar SUS productos con el
     * nombre del combo — si sólo lo llevara una, la otra prepara a ciegas.
     */
    @Test
    fun `resolve headers each station's products with the combo they belong to`() {
        val comboNames = mapOf("oi_1" to "Combo del día", "oi_2" to "Combo del día")

        val cocina = comandaPrinter.resolve(
            plan("st_cocina", listOf(tacoLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
            comboNames = comboNames,
        )
        val barra = comandaPrinter.resolve(
            plan("st_barra", listOf(cervezaLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
            comboNames = comboNames,
        )

        assertEquals(listOf("Combo del día", "Taco"), cocina.ticket.items.map { it.name })
        assertEquals(listOf("Combo del día", "Cerveza"), barra.ticket.items.map { it.name })
        assertTrue(cocina.ticket.items.first().isComboHeader)
        assertTrue(cocina.ticket.items.last().isComboComponent)
        assertEquals(2, cocina.ticket.items.last().quantity) // la cantidad del producto, intacta
    }

    /** REGRESIÓN: sin combos la comanda sale EXACTAMENTE igual que siempre. */
    @Test
    fun `resolve without combo names produces todays comanda untouched`() {
        val resolved = comandaPrinter.resolve(
            plan("st_cocina", listOf(tacoLine, cervezaLine)),
            config,
            orderNumber = "1234",
            orderType = "En tienda",
        )

        assertEquals(listOf("Taco", "Cerveza"), resolved.ticket.items.map { it.name })
        assertTrue(resolved.ticket.items.none { it.isComboHeader || it.isComboComponent })
    }
}
