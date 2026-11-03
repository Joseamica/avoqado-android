package com.avoqado.pos.printing.data

import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.printing.data.model.KitchenItem
import com.avoqado.pos.printing.data.model.KitchenTicketData
import com.avoqado.pos.printing.data.model.MARCA_NEGRITA_ENCABEZADO
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import io.mockk.coEvery
import io.mockk.mockk
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ruling 19: el ticket puede llevar un encabezado extra (pedido en línea); sin él, NADA cambia ni un byte. */
class EncabezadoDeComandaTest {

    private val ticket = KitchenTicketData(
        orderNumber = "101", orderType = "En tienda", tableName = "8", serverName = "Luis", stationName = "Barra", notes = "Sin hielo",
        timestamp = Date(0),
        items = listOf(KitchenItem("Mojito", 2, listOf("sin azúcar"), "rápido"), KitchenItem("Taco", 1)),
    )

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hexDe(texto: String) = hex(texto.toByteArray(Charsets.ISO_8859_1))

    /** Los dos relojes del ticket (hora y fecha) cambian con la zona del aparato: se sustituyen por marcas fijas. */
    private fun sinRelojes(bytes: ByteArray, t: KitchenTicketData): String {
        val fecha = VenueTimeZone.at(t.timestamp.toInstant()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale("es", "MX")))
        return hex(bytes).replace(hexDe(fecha), hexDe("@@FECHA@@")).replace(hexDe(t.formattedTime), hexDe("@@HORA@@"))
    }

    /** Bytes de este ticket capturados con el código ANTERIOR a Ruling 19 (la línea del encabezado quitada del ESCPOSPrinter). */
    private val dorado =
        "1b401b74101d4c00001d5740021b61011b213042415252410a1b21003d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d" +
        "3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d0a1b61001b45014f7264656e20233a2020202020" +
        "20202020202020202020202020202020202020202020202020202020202020203130310a1b4500486f72613a20202020" +
        "202020202020202020202020202020202020202020202020202020202020202020204040484f524140400a5469706f3a" +
        "20202020202020202020202020202020202020202020202020202020202020202020456e207469656e64610a1b21104d" +
        "6573613a202020202020202020202020202020202020202020202020202020202020202020202020202020202020380a" +
        "1b21004d657365726f3a202020202020202020202020202020202020202020202020202020202020202020202020204c" +
        "7569730a3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d" +
        "3d3d3d3d0a1b21103278204d6f6a69746f0a1b21002020202d3e2073696e20617afa6361720a1b45012020204e6f7461" +
        "3a2072e17069646f0a1b45001b21103178205461636f0a1b21001b21101b21003d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d" +
        "3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d0a1b45014e4f5441533a0a1b45005369" +
        "6e206869656c6f0a3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d" +
        "3d3d3d3d3d3d3d3d0a1b61014040464543484140400a1b64051d5601"

    @Test
    fun `a ticket without header is byte for byte the same as before`() {
        val bytes = ESCPOSPrinter(PaperWidth.MM80).generateKitchenTicket(ticket)
        assertEquals(dorado, sinRelojes(bytes, ticket))
        // Una lista vacía tampoco emite nada de más.
        assertEquals(dorado, sinRelojes(ESCPOSPrinter(PaperWidth.MM80).generateKitchenTicket(ticket.copy(encabezado = emptyList())), ticket))
    }

    @Test
    fun `the header lines print under the type block and above the items`() {
        val t = ticket.copy(encabezado = listOf("Ana", "5512345678", "Pagado"))
        val h = hex(ESCPOSPrinter(PaperWidth.MM80).generateKitchenTicket(t))
        val mesero = h.indexOf(hexDe("Luis"))
        val ana = h.indexOf(hexDe("Ana\n"))
        val pagado = h.indexOf(hexDe("Pagado\n"))
        val mojito = h.indexOf(hexDe("x Mojito"))
        assertTrue(mesero in 0 until ana)
        assertTrue(ana < pagado && pagado < mojito)
    }

    private val planCocina = TicketPlan(stationId = "st_cocina", unrouted = false, lines = listOf(ConsolidatedLine("Taco", 2, emptyList(), null, listOf("oi_1"))))
    private val config = PrintConfig(
        printers = listOf(PrinterInfo("pr_cocina", "Cocina Printer", "NETWORK", "192.168.1.50:9100")),
        stations = listOf(StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_cocina", copies = 1)),
    )
    private val encabezado = listOf("Ana", "Pagado")

    private fun fallo() = ComandaPrinter.Result(
        attempted = 1, printed = 0, skippedNoPrinter = 0, lastError = "sin papel",
        failedStations = listOf("Cocina"), failedPlans = listOf(planCocina),
    )

    @Test
    fun `ComandaPrinter resolve puts the header on the ticket`() {
        val printer = ComandaPrinter(mockk(relaxed = true))
        val con = printer.resolve(planCocina, config, "W-1", "Para recoger", encabezado = encabezado)
        assertEquals(encabezado, con.ticket.encabezado)
        assertNull(printer.resolve(planCocina, config, "W-1", "Para recoger").ticket.encabezado)
    }

    @Test
    fun `a retry row keeps its header`() = runTest {
        val recibidos = mutableListOf<List<String>?>()
        val printer = mockk<ComandaPrinter> {
            coEvery { printComandas(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                recibidos += arg<List<String>?>(7)
                fallo()
            }
        }
        val sut = ReintentoDeComanda(printer, esperar = { }, reporteDeComandas = mockk(relaxed = true))

        val estado = sut.insistir(listOf(planCocina), config, "W-1", "Para recoger", null, emptyMap(), maxIntentos = 2, encabezado = encabezado) as EstadoDeComanda.NoSalio
        assertEquals(encabezado, estado.trabajo?.encabezado)
        assertEquals(listOf(encabezado, encabezado), recibidos)

        // «Volver a imprimir» manda el MISMO papel con su encabezado.
        recibidos.clear()
        sut.reintentar(estado.trabajo!!)
        assertEquals(listOf(encabezado), recibidos)

        // Y la fila guardada en disco (JSON) lo conserva; una fila vieja, sin el campo, se lee como null.
        val json = Json { ignoreUnknownKeys = true }
        val ida = json.encodeToString(TrabajoPendiente.serializer(), estado.trabajo!!)
        assertEquals(encabezado, json.decodeFromString(TrabajoPendiente.serializer(), ida).encabezado)
        val sinCampo = json.encodeToString(TrabajoPendiente.serializer(), estado.trabajo!!.copy(encabezado = null))
        assertTrue(!sinCampo.contains("encabezado"))
        assertNull(json.decodeFromString(TrabajoPendiente.serializer(), sinCampo).encabezado)
    }

    @Test
    fun `a header line with the bold mark prints in bold and without the mark`() {
        val t = ticket.copy(encabezado = listOf("Ana", MARCA_NEGRITA_ENCABEZADO + "Cobrar al recoger \$185.50"))
        val h = hex(ESCPOSPrinter(PaperWidth.MM80).generateKitchenTicket(t))
        // ESC E 1 · texto · LF · ESC E 0
        assertTrue(h.contains("1b4501" + hexDe("Cobrar al recoger \$185.50") + "0a1b4500"))
        // La línea sin marca no lleva negrita propia.
        assertTrue(h.contains(hexDe("Ana\n")) && !h.contains("1b4501" + hexDe("Ana")))
    }

    @Test
    fun `a control byte in the header never reaches the printer`() {
        // ESC p 0 abre el cajón: un texto de fuera nunca manda un comando a la impresora (PR #53).
        val t = ticket.copy(encabezado = listOf("Ana\u001Bp0\u0019", MARCA_NEGRITA_ENCABEZADO + "Nota\u0007"))
        val h = hex(ESCPOSPrinter(PaperWidth.MM80).generateKitchenTicket(t))
        assertTrue(h.contains(hexDe("Anap0\n")))
        assertTrue(h.contains("1b4501" + hexDe("Nota") + "0a1b4500"))
        assertTrue(!h.contains("1b7030"))
    }
}
