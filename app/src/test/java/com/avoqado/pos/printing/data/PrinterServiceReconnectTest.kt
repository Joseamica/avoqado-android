package com.avoqado.pos.printing.data

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.PrinterStatus
import com.avoqado.pos.printing.data.model.SavedPrinter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [shouldReconnect] — the pure decision function [PrinterService.sendData]
 * uses to decide whether the cached printer socket must be dropped and a fresh
 * connection opened, instead of being reused as-is.
 *
 * This targets the silent print-loss bug: [PrinterService] cached WiFi/Bluetooth
 * sockets keyed only by `printer.id`, with no awareness of the printer's address.
 * When an operator edited a printer's IP (or a refetched config changed it) while
 * the in-memory status still said "Connected", the stale socket pointing at the
 * OLD address was reused. The write into that dead socket did not throw, so the
 * app logged success while nothing printed.
 */
class PrinterServiceReconnectTest {

    private val endpointA = "192.168.1.50:9100"
    private val endpointB = "10.0.2.2:9100"

    @Test
    fun `regression guard - address changed while status says connected must reconnect, not reuse`() {
        // This is exactly the reproduced bug: printer.address was edited (e.g. via
        // the dashboard) from 127.0.0.1:9100 to 10.0.2.2:9100, status still says
        // Connected, and the socket is technically still open (it just points at
        // the wrong host). The legacy code reused it silently; the fix must not.
        val result = shouldReconnect(
            status = PrinterStatus.Connected,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointB,
            socketClosed = false,
        )
        assertTrue("Endpoint changed while 'connected' must force a reconnect", result)
    }

    @Test
    fun `connected plus same endpoint plus socket open means reuse - legacy behavior guard`() {
        // The additive contract: when the cache is genuinely still valid, sendData
        // must behave byte-for-byte like today - no reconnect, no extra latency.
        val result = shouldReconnect(
            status = PrinterStatus.Connected,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointA,
            socketClosed = false,
        )
        assertFalse("A valid, matching, open cached socket must be reused as-is", result)
    }

    @Test
    fun `printing plus same endpoint plus socket open also means reuse`() {
        // PrinterStatus.isConnected is true for both Connected and Printing.
        val result = shouldReconnect(
            status = PrinterStatus.Printing,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointA,
            socketClosed = false,
        )
        assertFalse(result)
    }

    @Test
    fun `connected but socket already closed must reconnect`() {
        val result = shouldReconnect(
            status = PrinterStatus.Connected,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointA,
            socketClosed = true,
        )
        assertTrue("A closed cached socket must never be reused", result)
    }

    @Test
    fun `status disconnected must reconnect - today's existing behavior`() {
        val result = shouldReconnect(
            status = PrinterStatus.Disconnected,
            cachedEndpoint = null,
            requestedEndpoint = endpointA,
            socketClosed = true,
        )
        assertTrue(result)
    }

    @Test
    fun `status connecting must reconnect`() {
        val result = shouldReconnect(
            status = PrinterStatus.Connecting,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointA,
            socketClosed = false,
        )
        assertTrue(result)
    }

    @Test
    fun `status error must reconnect`() {
        val result = shouldReconnect(
            status = PrinterStatus.Error("boom"),
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointA,
            socketClosed = false,
        )
        assertTrue(result)
    }

    @Test
    fun `connected with no cached endpoint at all must reconnect`() {
        // Defensive case: status says connected but there is no record of what
        // endpoint the socket was opened for (e.g. pre-fix cache state).
        val result = shouldReconnect(
            status = PrinterStatus.Connected,
            cachedEndpoint = null,
            requestedEndpoint = endpointA,
            socketClosed = false,
        )
        assertTrue(result)
    }

    @Test
    fun `connected with different endpoint and closed socket must reconnect`() {
        val result = shouldReconnect(
            status = PrinterStatus.Connected,
            cachedEndpoint = endpointA,
            requestedEndpoint = endpointB,
            socketClosed = true,
        )
        assertTrue(result)
    }

    @Test
    fun `integrated Sunmi is plug and play fallback for receipt printing`() {
        val selected = selectDefaultPrinter(
            role = PrinterRole.RECEIPT,
            configured = emptyList(),
            integratedAvailable = true,
            integratedPaperWidthMm = 80,
        )

        assertEquals("internal", selected?.connectionType)
        assertEquals("internal", selected?.address)
        assertEquals(80, selected?.paperWidthMm)
        assertTrue(selected?.hasRole(PrinterRole.RECEIPT) == true)
    }

    @Test
    fun `configured receipt printer wins over integrated fallback`() {
        val configured = SavedPrinter(
            id = "configured",
            name = "Epson",
            connectionType = "wifi",
            address = "192.168.1.80",
            roles = listOf(PrinterRole.RECEIPT.value),
        )

        val selected = selectDefaultPrinter(
            role = PrinterRole.RECEIPT,
            configured = listOf(configured),
            integratedAvailable = true,
            integratedPaperWidthMm = 80,
        )

        assertEquals("configured", selected?.id)
    }

    @Test
    fun `integrated fallback never invents a kitchen route`() {
        val selected = selectDefaultPrinter(
            role = PrinterRole.KITCHEN,
            configured = emptyList(),
            integratedAvailable = true,
            integratedPaperWidthMm = 80,
        )

        assertNull(selected)
    }

    // MARK: - disconnectAll() — Task 5, "la comanda que no sale"
    //
    // `disconnectAll()` YA EXISTÍA — el defecto real es que nadie la llamaba desde
    // la pantalla de Ajustes › Impresora. Tocar "Conectar" abría un socket TCP y lo
    // dejaba abierto para siempre: muchas impresoras ESC/POS de puerto 9100 aceptan
    // UNA sola conexión a la vez, así que la caja se quedaba con el teléfono
    // descolgado y la primera comanda de cocina del día no entraba. Con el POS
    // anterior del cliente (SoftRestaurant, Windows) esto no pasaba porque imprime
    // por el spooler: conecta, imprime y cuelga, cada vez.

    private fun printerServiceConDependenciasFalsas(): PrinterService {
        // Sin esto, el `init { loadSavedPrinters() }` del constructor real
        // truena: no hay Robolectric en este módulo (unitTests.isReturnDefaultValues
        // basta para android.util.Log, pero context.getSharedPreferences(...)
        // devuelve un objeto, no un primitivo, y sin stub sería null).
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val innerPrinter = mockk<SunmiInnerPrinter>(relaxed = true)
        val receiptBranding = mockk<ReceiptBranding>(relaxed = true)
        return PrinterService(context, innerPrinter, receiptBranding)
    }

    @Test
    fun `soltar todas cierra el socket y deja el estado en desconectada`() = runTest {
        // "socketFalso": un ServerSocket real en loopback hace de impresora, para
        // comprobar que el socket TCP se cierra DE VERDAD — no sólo que se olvida
        // del mapa en memoria, que es una prueba mucho más débil.
        val impresoraFalsa = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val ladoDeLaImpresora = CompletableFuture<Socket>()
        val hiloAceptador = Thread {
            runCatching { impresoraFalsa.accept() }
                .onSuccess { ladoDeLaImpresora.complete(it) }
                .onFailure { ladoDeLaImpresora.completeExceptionally(it) }
        }.apply { isDaemon = true; start() }

        try {
            val service = printerServiceConDependenciasFalsas()
            val printer = SavedPrinter(
                id = "cocina-wifi",
                name = "Cocina",
                connectionType = "wifi",
                address = "127.0.0.1",
                port = impresoraFalsa.localPort,
            )
            // Con una impresora WIFI conectada en el cache:
            service.savePrinter(printer)
            service.connect(printer)

            val socketDeLaImpresora = ladoDeLaImpresora.get(5, TimeUnit.SECONDS)

            service.disconnectAll()

            assertEquals(
                "disconnectAll() debe dejar el estado en Desconectada",
                PrinterStatus.Disconnected,
                service.printerStatuses.value[printer.id],
            )

            // -1 = la impresora falsa ve el cierre (FIN/EOF) de verdad. Si el
            // socket se hubiera quedado abierto —el defecto real—, este read()
            // colgaría hasta el timeout: exactamente el "teléfono descolgado"
            // del caso de la cafetería.
            socketDeLaImpresora.soTimeout = 2000
            assertEquals(
                "el socket TCP debe cerrarse; si no, la impresora queda ocupada para siempre",
                -1,
                socketDeLaImpresora.getInputStream().read(),
            )
            socketDeLaImpresora.close()
        } finally {
            impresoraFalsa.close()
            hiloAceptador.interrupt()
        }
    }

    @Test
    fun `alguien llama a disconnectAll (si no, la conexion queda abierta para siempre)`() {
        // POR ARCHIVO, no concatenado: un `contains` sobre el directorio entero
        // pegado en un solo String deja pasar el sabotaje de quitar la llamada
        // de UNO solo de los dos sitios — el otro archivo "tapa" al que quedó
        // huérfano. Verificado adversarialmente (ronda 1 de revisión): quitar
        // sólo la de PrinterConfigSheet.kt, o sólo la de PrinterSettingsSheet.kt,
        // seguía dando 14/14 en verde con la versión concatenada.
        val base = File("src/main/java/com/avoqado/pos/printing/presentation")
        val archivosQueDebenSoltarLaConexion = listOf(
            "PrinterSettingsSheet.kt",
            "PrinterConfigSheet.kt",
        )
        archivosQueDebenSoltarLaConexion.forEach { nombre ->
            val archivo = File(base, nombre)
            assertTrue(
                "no encontre $nombre en ${base.path} — el test necesita ajustar la ruta",
                archivo.exists(),
            )
            assertTrue(
                "disconnectAll() volvio a ser codigo muerto en $nombre: la conexion con la impresora se queda abierta",
                archivo.readText().contains("disconnectAll()"),
            )
        }
    }
}
