package com.avoqado.pos.printing.data

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.printing.data.model.PrinterException
import com.avoqado.pos.printing.data.model.SavedPrinter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * «La impresora que se encuentra sola», de punta a punta con un socket REAL en loopback que hace
 * de ticketera. El caso de Testarudo (2-oct-2026): el módem le dio a la tablet la dirección que
 * tenía la impresora de cocina, y cada comanda tocaba la puerta de la propia tablet.
 */
class PrinterServiceImpresoraMovidaTest {

    private class BuscadorFalso(
        val propias: List<String>,
        var ticketeras: List<String>,
        var anunciadas: Map<String, String> = emptyMap(),
        /** IP → identidad `mac:…` que publica su página. */
        var macs: Map<String, String> = emptyMap(),
    ) : BuscadorDeImpresora {
        var barridos = 0
        override suspend fun anunciadaConNombre(nombre: String, msMax: Long): String? = anunciadas[nombre]
        override suspend fun leerMac(ip: String): String? = macs[ip]
        override suspend fun anunciadas(msMax: Long): Map<String, String> = anunciadas.entries.associate { (n, ip) -> ip to n }
        override fun direccionesPropias() = propias.map { DireccionLocal(it, 24) }
        override suspend fun ticketerasEnLaRed(puerto: Int, noTocar: Set<String>): List<String> {
            barridos++
            return ticketeras.filter { it !in noTocar }
        }
    }

    private fun servicio(buscador: BuscadorDeImpresora): PrinterService {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return PrinterService(context, mockk(relaxed = true), mockk(relaxed = true)).also { it.buscador = buscador }
    }

    private fun puertoCerrado(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun `P1 si la direccion guardada es la de esta tablet la busca e imprime en la nueva`() = runBlocking {
        // La "ticketera" escucha en loopback; la tablet dice que 127.0.0.1 es SUYA, así que la
        // dirección guardada no se intenta y se barre la red. El barrido la encuentra como "localhost".
        val ticketera = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val recibido = CompletableFuture<ByteArray>()
        Thread {
            runCatching {
                ticketera.accept().use { s ->
                    s.soTimeout = 5_000
                    recibido.complete(s.getInputStream().readBytes())
                }
            }.onFailure { recibido.completeExceptionally(it) }
        }.apply { isDaemon = true; start() }

        val buscador = BuscadorFalso(propias = listOf("127.0.0.1"), ticketeras = listOf("localhost"))
        val service = servicio(buscador)
        val cocina = SavedPrinter(
            id = "cocina",
            name = "Impresora 127.0.0.1",
            connectionType = "wifi",
            address = "127.0.0.1",
            port = ticketera.localPort,
        )
        service.savePrinter(cocina)

        try {
            service.sendPrintData(byteArrayOf(0x41, 0x42), cocina)

            val bytes = recibido.get(10, TimeUnit.SECONDS)
            assertTrue("la comanda debe llegar a la impresora encontrada", bytes.takeLast(2) == listOf<Byte>(0x41, 0x42))
            assertEquals(1, buscador.barridos)
            val guardada = service.savedPrinters.value.single { it.id == "cocina" }
            assertEquals("la guardada se corrige a la dirección nueva", "localhost", guardada.address)
            assertEquals("el nombre automático sigue a la dirección", "Impresora localhost", guardada.name)
        } finally {
            ticketera.close()
        }
    }

    @Test
    fun `una Epson que se anuncia se encuentra por su nombre sin barrer la red`() = runBlocking {
        val ticketera = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread { runCatching { ticketera.accept().use { it.getInputStream().readBytes() } } }.apply { isDaemon = true; start() }
        // Hay dos ticketeras en la red (adivinar sería imposible), pero el nombre la identifica.
        val buscador = BuscadorFalso(
            propias = listOf("192.168.100.235"),
            ticketeras = listOf("192.168.100.50", "192.168.100.51"),
            anunciadas = mapOf("EPSON TM-m30III" to "localhost"),
        )
        val service = servicio(buscador)
        val epson = SavedPrinter(
            id = "epson",
            name = "EPSON TM-m30III",
            connectionType = "wifi",
            address = "127.0.0.1",
            port = ticketera.localPort,
        )
        service.savePrinter(epson.copy(address = "192.168.100.235"))
        try {
            service.sendPrintData(byteArrayOf(0x41), epson.copy(address = "192.168.100.235"))
            assertEquals(0, buscador.barridos)
            assertEquals("localhost", service.savedPrinters.value.single { it.id == "epson" }.address)
        } finally {
            ticketera.close()
        }
    }

    @Test
    fun `P1 con dos ticketeras libres no adivina y lo dice en palabras de cajero`() = runBlocking {
        val buscador = BuscadorFalso(propias = listOf("192.168.1.64"), ticketeras = listOf("192.168.1.67", "192.168.1.70"))
        val service = servicio(buscador)
        val cocina = SavedPrinter(id = "cocina", name = "Cocina", connectionType = "wifi", address = "127.0.0.1", port = puertoCerrado())

        try {
            service.sendPrintData(byteArrayOf(0x41), cocina)
            fail("no debe imprimir en una impresora adivinada")
        } catch (e: PrinterException.NoEstaEnSuDireccion) {
            assertEquals(ImpresoraMovida.avisoVarias("127.0.0.1", 2), e.message)
        }
    }

    @Test
    fun `una busqueda fallida no se repite en cada reintento de la comanda`() = runBlocking {
        val buscador = BuscadorFalso(propias = listOf("192.168.1.64"), ticketeras = emptyList())
        val service = servicio(buscador)
        val cocina = SavedPrinter(id = "cocina", name = "Cocina", connectionType = "wifi", address = "127.0.0.1", port = puertoCerrado())

        repeat(3) {
            try {
                service.sendPrintData(byteArrayOf(0x41), cocina)
                fail("no hay impresora: debe fallar")
            } catch (e: PrinterException.NoEstaEnSuDireccion) {
                assertEquals(ImpresoraMovida.avisoNinguna("127.0.0.1"), e.message)
            }
        }
        assertEquals("tres reintentos seguidos = un solo barrido de la red", 1, buscador.barridos)
    }

    @Test
    fun `la direccion de otra impresora conocida no se toca ni se adopta`() = runBlocking {
        // Barra (config del servidor) vive en .70 y contesta: Cocina NO puede quedarse con ella.
        val buscador = BuscadorFalso(propias = listOf("192.168.1.64"), ticketeras = listOf("192.168.1.70"))
        val service = servicio(buscador)
        service.conocerImpresorasDeRed(mapOf("barra" to "192.168.1.70"))
        val cocina = SavedPrinter(id = "cocina", name = "Cocina", connectionType = "wifi", address = "127.0.0.1", port = puertoCerrado())

        try {
            service.sendPrintData(byteArrayOf(0x41), cocina)
            fail("la única ticketera es la de Barra: no debe adoptarla")
        } catch (e: PrinterException.NoEstaEnSuDireccion) {
            assertEquals(ImpresoraMovida.avisoNinguna("127.0.0.1"), e.message)
        }
    }

    // MARK: - Identidad, vigilante y avisos

    private val MAC_A = "mac:02107B1A76FC"
    private val MAC_B = "mac:0210BBBBBBBB"

    @Test
    fun `P1 dos mudanzas antes de que el servidor se entere no regresan a la direccion vieja`() = runBlocking {
        val buscador = BuscadorFalso(propias = listOf("192.168.1.64"), ticketeras = emptyList(), anunciadas = mapOf("EPSON TM-m30III" to "192.168.1.67"))
        val service = servicio(buscador)
        service.conocerImpresorasDeRed(mapOf("cocina" to "192.168.1.64"), mapOf("cocina" to "mdns:EPSON TM-m30III"))

        assertEquals(1, service.vigilar())
        assertEquals("192.168.1.67", service.direccionVigente("cocina", "192.168.1.64"))

        buscador.anunciadas = mapOf("EPSON TM-m30III" to "192.168.1.70")
        assertEquals(1, service.vigilar())
        assertEquals("192.168.1.70", service.direccionVigente("cocina", "192.168.1.64"))
    }

    @Test
    fun `P1 dos ticketeras que se intercambian la IP se corrigen por su identidad`() = runBlocking {
        val buscador = BuscadorFalso(
            propias = listOf("192.168.1.64"),
            ticketeras = listOf("192.168.1.67", "192.168.1.70"),
            // Intercambiadas: en la .67 (de Cocina) ahora contesta Barra, y al revés.
            macs = mapOf("192.168.1.67" to MAC_B, "192.168.1.70" to MAC_A),
        )
        val service = servicio(buscador)
        service.conocerImpresorasDeRed(
            mapOf("cocina" to "192.168.1.67", "barra" to "192.168.1.70"),
            mapOf("cocina" to MAC_A, "barra" to MAC_B),
        )

        assertEquals(2, service.vigilar())
        assertEquals("192.168.1.70", service.direccionVigente("cocina", "192.168.1.67"))
        assertEquals("192.168.1.67", service.direccionVigente("barra", "192.168.1.70"))
        // Ya en su lugar: la siguiente ronda no cambia nada.
        assertEquals(0, service.vigilar())
    }

    @Test
    fun `aprende la identidad y deja el aviso al servidor guardado`() = runBlocking {
        val buscador = BuscadorFalso(propias = listOf("192.168.1.64"), ticketeras = emptyList(), macs = mapOf("192.168.1.67" to MAC_A))
        val service = servicio(buscador).also { it.venueActual = { "venue_testarudo" } }
        service.conocerImpresorasDeRed(mapOf("cocina" to "192.168.1.67"))

        service.vigilar()

        assertEquals(MAC_A, service.identidadDe("cocina"))
        assertEquals(
            AvisoDeImpresora(venueId = "venue_testarudo", previousAddress = "192.168.1.67", address = "192.168.1.67", stableKey = MAC_A),
            service.avisosPendientes.todas()["cocina"],
        )
    }

    @Test
    fun `una mudanza deja el aviso con la direccion que el servidor tenia`() = runBlocking {
        val buscador = BuscadorFalso(
            propias = listOf("192.168.1.64"),
            ticketeras = listOf("192.168.1.80"),
            macs = mapOf("192.168.1.67" to MAC_B, "192.168.1.80" to MAC_A),
        )
        val service = servicio(buscador).also { it.venueActual = { "venue_testarudo" } }
        service.conocerImpresorasDeRed(mapOf("cocina" to "192.168.1.67"), mapOf("cocina" to MAC_A))

        service.vigilar()

        val aviso = service.avisosPendientes.todas()["cocina"]
        assertEquals("192.168.1.67", aviso?.previousAddress)
        assertEquals("192.168.1.80", aviso?.address)
        assertEquals(MAC_A, aviso?.stableKey)
    }

    @Test
    fun `P1 un reemplazo solo se confirma con dos rondas separadas`() = runBlocking {
        val buscador = BuscadorFalso(propias = listOf("192.168.1.64"), ticketeras = listOf("192.168.1.67"), macs = mapOf("192.168.1.67" to MAC_B))
        val service = servicio(buscador)
        var ahora = 1_000_000L
        service.reloj = { ahora }
        service.conocerImpresorasDeRed(mapOf("cocina" to "192.168.1.67"), mapOf("cocina" to MAC_A))

        // Primera ronda: la suya puede estar apagada o arrancando — todavía no es un reemplazo.
        assertEquals(0, service.vigilar())
        assertEquals(MAC_A, service.identidadDe("cocina"))

        // Dos minutos después sigue sin aparecer: tampoco.
        ahora += 2 * 60_000L
        service.vigilar()
        assertEquals(MAC_A, service.identidadDe("cocina"))

        // Diez minutos después de la primera vez, la misma desconocida sigue en su lugar: reemplazo.
        ahora += 8 * 60_000L
        service.vigilar()
        assertEquals(MAC_B, service.identidadDe("cocina"))
    }

    @Test
    fun `P1 la de OTRA impresora conocida nunca se toma como reemplazo y la comanda no va ahi`() = runBlocking {
        // Cocina (A) está apagada; en su .67 contesta Barra (B), que es conocida.
        val buscador = BuscadorFalso(propias = listOf("192.168.1.64"), ticketeras = listOf("192.168.1.67"), macs = mapOf("192.168.1.67" to MAC_B))
        val service = servicio(buscador)
        var ahora = 1_000_000L
        service.reloj = { ahora }
        service.conocerImpresorasDeRed(
            mapOf("cocina" to "192.168.1.67", "barra" to "192.168.1.67"),
            mapOf("cocina" to MAC_A, "barra" to MAC_B),
        )
        service.vigilar()
        ahora += 20 * 60_000L
        service.vigilar()
        assertEquals(MAC_A, service.identidadDe("cocina"))

        // Y una comanda de Cocina no se manda a la .67: busca la suya y, si no está, lo dice.
        val cocina = SavedPrinter(id = "cocina", name = "Cocina", connectionType = "wifi", address = "192.168.1.67", port = puertoCerrado())
        try {
            service.sendPrintData(byteArrayOf(0x41), cocina)
            fail("no debe imprimir la comanda de Cocina en la impresora de Barra")
        } catch (e: PrinterException.NoEstaEnSuDireccion) {
            assertEquals(ImpresoraMovida.avisoNinguna("192.168.1.67"), e.message)
        }
    }

    @Test
    fun `P1 si regresa a una direccion que ya tuvo despues de que el servidor acepto la anterior, se encuentra`() = runBlocking {
        val buscador = BuscadorFalso(
            propias = listOf("192.168.1.64"),
            ticketeras = listOf("192.168.1.67"),
            macs = mapOf("192.168.1.67" to MAC_A),
        )
        val service = servicio(buscador)
        service.conocerImpresorasDeRed(mapOf("cocina" to "192.168.1.64"), mapOf("cocina" to MAC_A))
        // .64 → .67 (en la .64 contesta otra desconocida, la suya está en la .67).
        buscador.macs = mapOf("192.168.1.64" to "mac:0210CCCCCCCC", "192.168.1.67" to MAC_A)
        assertEquals(1, service.vigilar())
        assertEquals("192.168.1.67", service.direccionVigente("cocina", "192.168.1.64"))

        // El servidor ya aceptó la .67: la config llega con la .67.
        service.conocerImpresorasDeRed(mapOf("cocina" to "192.168.1.67"), mapOf("cocina" to MAC_A))
        // Y regresa a la .64 (en la .67 ahora hay otra desconocida).
        buscador.ticketeras = listOf("192.168.1.64")
        buscador.macs = mapOf("192.168.1.64" to MAC_A, "192.168.1.67" to "mac:0210CCCCCCCC")
        assertEquals(1, service.vigilar())
        assertEquals("192.168.1.64", service.direccionVigente("cocina", "192.168.1.67"))
    }

    @Test
    fun `P1 con identidad conocida el barrido no adopta a otra aunque sea la unica libre`() = runBlocking {
        // El módem se reinició: Cocina (A) aún arranca y la única ticketera libre es otra (B).
        val buscador = BuscadorFalso(propias = listOf("192.168.1.64"), ticketeras = listOf("192.168.1.80"), macs = mapOf("192.168.1.80" to MAC_B))
        val service = servicio(buscador)
        service.conocerImpresorasDeRed(mapOf("cocina" to "127.0.0.1"), mapOf("cocina" to MAC_A))
        val cocina = SavedPrinter(id = "cocina", name = "Cocina", connectionType = "wifi", address = "127.0.0.1", port = puertoCerrado())
        try {
            service.sendPrintData(byteArrayOf(0x41), cocina)
            fail("no debe adoptar otra ticketera")
        } catch (e: PrinterException.NoEstaEnSuDireccion) {
            assertEquals(ImpresoraMovida.avisoNinguna("127.0.0.1"), e.message)
        }
        assertEquals("127.0.0.1", service.direccionVigente("cocina", "127.0.0.1"))
    }

    @Test
    fun `P1 sin identidad, una candidata que es OTRA impresora conocida no se adopta`() = runBlocking {
        val buscador = BuscadorFalso(propias = listOf("192.168.1.64"), ticketeras = listOf("192.168.1.80"), macs = mapOf("192.168.1.80" to MAC_B))
        val service = servicio(buscador)
        // Barra (B) se movió a la .80 y nadie lo sabe aún; Cocina no tiene identidad.
        service.conocerImpresorasDeRed(mapOf("cocina" to "127.0.0.1", "barra" to "192.168.1.70"), mapOf("barra" to MAC_B))
        val cocina = SavedPrinter(id = "cocina", name = "Cocina", connectionType = "wifi", address = "127.0.0.1", port = puertoCerrado())
        try {
            service.sendPrintData(byteArrayOf(0x41), cocina)
            fail("la .80 es Barra: no es Cocina")
        } catch (e: PrinterException.NoEstaEnSuDireccion) {
            assertEquals(ImpresoraMovida.avisoNinguna("127.0.0.1"), e.message)
        }
    }

    @Test
    fun `una impresora que no contesta no se toca en la ronda`() = runBlocking {
        val buscador = BuscadorFalso(propias = listOf("192.168.1.64"), ticketeras = listOf("192.168.1.80"), macs = mapOf("192.168.1.80" to MAC_A))
        val service = servicio(buscador)
        service.conocerImpresorasDeRed(mapOf("cocina" to "192.168.1.67"), mapOf("cocina" to MAC_A))

        assertEquals(0, service.vigilar())
        assertEquals("192.168.1.67", service.direccionVigente("cocina", "192.168.1.67"))
    }

    @Test
    fun `P1 al imprimir con dos ticketeras libres la identidad dice cual es`() = runBlocking {
        val ticketera = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread { runCatching { ticketera.accept().use { it.getInputStream().readBytes() } } }.apply { isDaemon = true; start() }
        val buscador = BuscadorFalso(
            propias = listOf("127.0.0.1"),
            ticketeras = listOf("192.168.1.71", "localhost"),
            macs = mapOf("localhost" to MAC_A, "192.168.1.71" to MAC_B),
        )
        val service = servicio(buscador)
        service.conocerImpresorasDeRed(mapOf("cocina" to "127.0.0.1"), mapOf("cocina" to MAC_A))
        val cocina = SavedPrinter(id = "cocina", name = "Cocina", connectionType = "wifi", address = "127.0.0.1", port = ticketera.localPort)
        try {
            service.sendPrintData(byteArrayOf(0x41), cocina)
            assertEquals("localhost", service.direccionVigente("cocina", "127.0.0.1"))
        } finally {
            ticketera.close()
        }
    }
}
