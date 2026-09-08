package com.avoqado.pos.printing.data

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.PrinterStatus
import com.avoqado.pos.printing.data.model.SavedPrinter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
            // 🔴 Se exige la llamada DENTRO del `onDispose`, no en cualquier parte del archivo.
            // Codex (P2 #20, 2026-09-07) señaló que el `contains` suelto pasaba aunque la
            // llamada quedara comentada o en código inalcanzable. Sigue siendo una comprobación
            // ESTRUCTURAL —montar y desmontar Compose pediría Robolectric— y se declara como tal:
            // demuestra que el cableado está escrito, no que el ciclo de vida lo ejecute.
            val texto = archivo.readText()
            assertTrue(
                "disconnectAll() volvio a ser codigo muerto en $nombre: la conexion con la impresora se queda abierta",
                Regex("""onDispose\s*\{[^}]*printerService\.disconnectAll\(\)""").containsMatchIn(texto),
            )
        }
    }

    // MARK: - estadoInicial() ya NO miente — Task 7, "el letrero deja de mentir"
    //
    // Caso real: el cajero de una cafeteria abria Ajustes > Impresoras, veia la
    // de cocina como "Desconectada", le picaba "Conectar", respondia AL
    // INSTANTE (la impresora siempre estuvo bien) y creia haber arreglado algo.
    // `estadoInicial()` marcaba Disconnected a TODA impresora que no fuera la
    // integrada SIN PROBAR NADA, y ese estado solo vivia en memoria: cada
    // reinicio de la app volvia a mentir. El mismo defecto ya se habia cazado
    // en agosto y arreglado SOLO para la integrada (ver el comentario de
    // estadoInicial en PrinterService.kt); las de red se quedaron con la
    // mentira.
    //
    // El escenario real es un REINICIO DE LA APP: la impresora ya estaba
    // guardada (persistida en SharedPreferences) de una sesion anterior, y
    // loadSavedPrinters() la relee al arrancar — por eso el helper siembra la
    // preferencia directamente en vez de pasar por savePrinter().

    private fun printerServiceConImpresoraPersistida(
        printer: SavedPrinter,
        hasPhysicalPrinter: Boolean = false,
    ): PrinterService {
        val serializado = Json { ignoreUnknownKeys = true }.encodeToString(
            ListSerializer(SavedPrinter.serializer()),
            listOf(printer),
        )
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns serializado
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val innerPrinter = mockk<SunmiInnerPrinter>(relaxed = true)
        every { innerPrinter.hasPhysicalPrinter } returns hasPhysicalPrinter
        val receiptBranding = mockk<ReceiptBranding>(relaxed = true)
        return PrinterService(context, innerPrinter, receiptBranding)
    }

    @Test
    fun `una impresora de red arranca SIN COMPROBAR, no en desconectada`() {
        val printerWifi = SavedPrinter(
            id = "cocina-wifi",
            name = "Cocina",
            connectionType = "wifi",
            address = "192.168.1.55",
        )
        val service = printerServiceConImpresoraPersistida(printerWifi)

        service.loadSavedPrinters()

        assertEquals(PrinterStatus.SinComprobar, service.printerStatuses.value[printerWifi.id])
    }

    @Test
    fun `la integrada sigue arrancando conectada (no se rompio lo que ya se arreglo en agosto)`() {
        val printerIntegrada = SavedPrinter(
            id = "internal-auto-receipt",
            name = "Impresora integrada",
            connectionType = "internal",
            address = "internal",
        )
        val service = printerServiceConImpresoraPersistida(printerIntegrada, hasPhysicalPrinter = true)

        service.loadSavedPrinters()

        assertEquals(PrinterStatus.Connected, service.printerStatuses.value[printerIntegrada.id])
    }

    // MARK: - probarTodas() no rompe el camino compartido de impresion
    //
    // PrinterStatus.SinComprobar tiene isConnected = false para que
    // shouldReconnect() la trate exactamente como hoy trata a Disconnected:
    // reconectar antes de mandar los bytes. shouldReconnect() es codigo
    // COMPARTIDO — lo usa sendData() para recibos, comandas Y el corte de
    // caja (sendPrintData) — asi que si SinComprobar dejara de disparar la
    // reconexion, una impresora recien arrancada (o recien agregada) se
    // quedaria sin poder imprimir NADA hasta que alguien la tocara a mano.

    @Test
    fun `una impresora SinComprobar SI dispara reconexion, igual que Disconnected hoy`() {
        val result = shouldReconnect(
            status = PrinterStatus.SinComprobar,
            cachedEndpoint = null,
            requestedEndpoint = endpointA,
            socketClosed = true,
        )
        assertTrue(
            "SinComprobar debe reconectar como Disconnected — si no, una impresora recien arrancada no imprime nada",
            result,
        )
    }

    @Test
    fun `SinComprobar y Disconnected reconectan identico ante los mismos parametros`() {
        val parametros = listOf(
            Triple(endpointA, endpointA, false),
            Triple(endpointA, endpointB, false),
            Triple(null as String?, endpointA, true),
        )
        parametros.forEach { (cached, requested, closed) ->
            val comoSinComprobar = shouldReconnect(PrinterStatus.SinComprobar, cached, requested, closed)
            val comoDisconnected = shouldReconnect(PrinterStatus.Disconnected, cached, requested, closed)
            assertEquals(
                "SinComprobar y Disconnected deben coincidir para cached=$cached requested=$requested closed=$closed",
                comoDisconnected,
                comoSinComprobar,
            )
        }
    }

    @Test
    fun `una impresora SinComprobar SI imprime - se reconecta y suelta despues, sin quedarse muda`() = runTest {
        // Reproduce el camino REAL de sendData(): una impresora que arranca
        // SinComprobar (nunca se probo) manda un recibo. Si SinComprobar no
        // disparara reconexion, sendDataWiFi() reventaria con NotConnected
        // porque wifiConnections[id] jamas se habria llenado.
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
                id = "cocina-wifi-sin-comprobar",
                name = "Cocina",
                connectionType = "wifi",
                address = "127.0.0.1",
                port = impresoraFalsa.localPort,
            )
            service.savePrinter(printer)
            // savePrinter() ya deja SinComprobar via estadoInicial(); lo
            // confirmamos explicito para que el test documente la premisa.
            assertEquals(PrinterStatus.SinComprobar, service.printerStatuses.value[printer.id])

            service.sendPrintData(byteArrayOf(0x1B, 0x40), printer)

            ladoDeLaImpresora.get(5, TimeUnit.SECONDS)
            assertEquals(
                "tras imprimir, una impresora SinComprobar debe terminar Conectada (probada de verdad)",
                PrinterStatus.Connected,
                service.printerStatuses.value[printer.id],
            )
        } finally {
            impresoraFalsa.close()
            hiloAceptador.interrupt()
        }
    }

    // MARK: - probarTodas() / probar() — la comprobación misma

    @Test
    fun `probarTodas responde CONECTADA cuando la impresora SI responde, y suelta el socket`() = runTest {
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
                id = "cocina-wifi-probar",
                name = "Cocina",
                connectionType = "wifi",
                address = "127.0.0.1",
                port = impresoraFalsa.localPort,
            )
            service.savePrinter(printer)
            assertEquals(PrinterStatus.SinComprobar, service.printerStatuses.value[printer.id])

            service.probarTodas()

            assertEquals(
                "una impresora que SI responde debe quedar Conectada tras probarTodas()",
                PrinterStatus.Connected,
                service.printerStatuses.value[printer.id],
            )

            // "suelta lo que abres": si probar() hubiera dejado el socket vivo,
            // este read() colgaria hasta el timeout en vez de ver el cierre.
            val socketDeLaImpresora = ladoDeLaImpresora.get(5, TimeUnit.SECONDS)
            socketDeLaImpresora.soTimeout = 2000
            assertEquals(
                "probar() debe SOLTAR el socket tras comprobar - si no, es el mismo telefono descolgado de Task 5",
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
    fun `probarTodas responde NO RESPONDE cuando la impresora no contesta`() = runTest {
        // Puerto reservado y liberado de inmediato: nada escucha ahi. Conectar
        // a un puerto cerrado en loopback rechaza al instante (ECONNREFUSED),
        // asi que esta prueba no depende de esperar PROBE_TIMEOUT_MS.
        val puertoLibre = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        val service = printerServiceConDependenciasFalsas()
        val printer = SavedPrinter(
            id = "cocina-wifi-apagada",
            name = "Cocina",
            connectionType = "wifi",
            address = "127.0.0.1",
            port = puertoLibre,
        )
        service.savePrinter(printer)
        assertEquals(PrinterStatus.SinComprobar, service.printerStatuses.value[printer.id])

        service.probarTodas()

        assertEquals(
            "una impresora apagada debe quedar Desconectada (No responde) tras probarTodas(), nunca Error",
            PrinterStatus.Disconnected,
            service.printerStatuses.value[printer.id],
        )
    }

    // MARK: - probarTodas() SOLO prueba WiFi — ronda 1 de revisión, 2026-09-07
    //
    // Hallazgo Important de la revisión: probar() llamaba connect() de forma
    // INCONDICIONAL sobre TODAS las guardadas — el primer sitio del repo que
    // conecta sin que nadie lo pida (los otros 3 llamadores van detras de un
    // toque explicito). Bluetooth NO libera su socket tras imprimir (a
    // diferencia de WiFi, que sí lo hace en cada sendData()) — asi que
    // probarla de nuevo aqui sobreescribiria btConnections[id] sin cerrar el
    // socket viejo: el mismo "telefono descolgado" de Task 5, reabierto para
    // Bluetooth por este mecanismo nuevo. Y USB puede hacer saltar el dialogo
    // de permiso del sistema solo por abrir la pantalla. Arreglo: probar()
    // sólo toca WIFI; Bluetooth/USB se quedan en SinComprobar hasta
    // "Conectar" a mano.

    @Test
    fun `probarTodas NO toca Bluetooth ni USB - se quedan en SinComprobar`() = runTest {
        val service = printerServiceConDependenciasFalsas()
        val printerBt = SavedPrinter(
            id = "impresora-bt",
            name = "Caja",
            connectionType = "bluetooth",
            address = "00:11:22:33:44:55",
        )
        val printerUsb = SavedPrinter(
            id = "impresora-usb",
            name = "Mostrador",
            connectionType = "usb",
            address = "usb:1234:5678",
        )
        service.savePrinter(printerBt)
        service.savePrinter(printerUsb)
        assertEquals(PrinterStatus.SinComprobar, service.printerStatuses.value[printerBt.id])
        assertEquals(PrinterStatus.SinComprobar, service.printerStatuses.value[printerUsb.id])

        service.probarTodas()

        assertEquals(
            "probarTodas() NO debe tocar Bluetooth - conectar sin que nadie lo pida filtra su socket, que no se libera tras imprimir",
            PrinterStatus.SinComprobar,
            service.printerStatuses.value[printerBt.id],
        )
        assertEquals(
            "probarTodas() NO debe tocar USB - podria disparar el dialogo de permiso del sistema solo con abrir la pantalla",
            PrinterStatus.SinComprobar,
            service.printerStatuses.value[printerUsb.id],
        )
    }

    @Test
    fun `probarTodas SI prueba wifi en una flota mixta, bluetooth y usb quedan intactos`() = runTest {
        // El escenario real que motivo el hallazgo: un local con una impresora
        // de cocina por WiFi y una de recibos por Bluetooth, probadas juntas.
        val impresoraFalsa = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val ladoDeLaImpresora = CompletableFuture<Socket>()
        val hiloAceptador = Thread {
            runCatching { impresoraFalsa.accept() }
                .onSuccess { ladoDeLaImpresora.complete(it) }
                .onFailure { ladoDeLaImpresora.completeExceptionally(it) }
        }.apply { isDaemon = true; start() }

        try {
            val service = printerServiceConDependenciasFalsas()
            val printerWifi = SavedPrinter(
                id = "cocina-wifi-flota",
                name = "Cocina",
                connectionType = "wifi",
                address = "127.0.0.1",
                port = impresoraFalsa.localPort,
            )
            val printerBt = SavedPrinter(
                id = "recibos-bt-flota",
                name = "Recibos",
                connectionType = "bluetooth",
                address = "00:11:22:33:44:66",
            )
            service.savePrinter(printerWifi)
            service.savePrinter(printerBt)

            service.probarTodas()

            ladoDeLaImpresora.get(5, TimeUnit.SECONDS)
            assertEquals(
                "la WiFi de la flota SI debe probarse y quedar Conectada",
                PrinterStatus.Connected,
                service.printerStatuses.value[printerWifi.id],
            )
            assertEquals(
                "la Bluetooth de la MISMA flota NO debe tocarse",
                PrinterStatus.SinComprobar,
                service.printerStatuses.value[printerBt.id],
            )
        } finally {
            impresoraFalsa.close()
            hiloAceptador.interrupt()
        }
    }

    @Test
    fun `una impresora WIFI ya CONECTADA no se vuelve a probar`() = runTest {
        // Defensa barata que pidio la revision: si ya esta conectada, no la
        // toques. Sin esto, re-probar una WiFi con un socket vivo (alguien
        // toco "Conectar" a mano y no ha impreso desde entonces) sufriria el
        // MISMO hueco que Bluetooth: connectWiFi() tampoco cierra un socket
        // cacheado antes de sobreescribirlo.
        //
        // La señal NO es "el socket viejo sigue vivo" (eso es ambiguo: en el
        // camino roto, probar() abriria una SEGUNDA conexion y luego la
        // soltaria en su propio finally, dejando el socket VIEJO igual de
        // huerfano y sin cerrar que en el camino correcto — read()/EOF no
        // distingue los dos casos). La señal real es CUANTAS conexiones TCP
        // llegan a la impresora: exactamente 1 si se salto la prueba, 2 si no.
        val impresoraFalsa = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val conexionesAceptadas = java.util.concurrent.LinkedBlockingQueue<Socket>()
        val hiloAceptador = Thread {
            runCatching {
                while (!impresoraFalsa.isClosed) {
                    conexionesAceptadas.put(impresoraFalsa.accept())
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val service = printerServiceConDependenciasFalsas()
            val printer = SavedPrinter(
                id = "cocina-wifi-ya-conectada",
                name = "Cocina",
                connectionType = "wifi",
                address = "127.0.0.1",
                port = impresoraFalsa.localPort,
            )
            service.savePrinter(printer)
            // Conecta DE VERDAD a mano, como si el cajero hubiera tocado
            // "Conectar" en PrinterConfigSheet.
            service.connect(printer)
            val primeraConexion = conexionesAceptadas.poll(5, TimeUnit.SECONDS)
            assertTrue("debe haber exactamente una conexion tras el connect() manual", primeraConexion != null)
            assertEquals(PrinterStatus.Connected, service.printerStatuses.value[printer.id])

            service.probarTodas()

            // Si probar() hubiera re-probado, la segunda conexion llegaria
            // casi al instante (loopback) — margen corto pero generoso.
            val segundaConexion = conexionesAceptadas.poll(1500, TimeUnit.MILLISECONDS)
            assertNull(
                "probar() NO debe abrir una SEGUNDA conexion sobre una impresora ya conectada",
                segundaConexion,
            )
            assertEquals(
                "sigue Conectada - probarTodas() no debe haberla tocado",
                PrinterStatus.Connected,
                service.printerStatuses.value[printer.id],
            )
        } finally {
            impresoraFalsa.close()
            hiloAceptador.interrupt()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════════════
// P1 #5 de la auditoría de Codex (2026-09-07) — introducido por el propio arreglo de la Tarea 5.
// `onDispose { disconnectAll() }` cerraba TODOS los transportes, incluida una impresora que
// estuviera escribiendo una comanda en ese instante — y podía ser OTRA impresora: una comanda
// reintentando en segundo plano mientras el cajero configura una distinta.
// ═══════════════════════════════════════════════════════════════════════════════════════════

class PuedeSoltarseLaConexionTest {

    @Test
    fun `P1 una impresora IMPRIMIENDO conserva su conexion`() {
        assertFalse(
            "cerrar el socket a media escritura deja la comanda cortada, y nadie se entera",
            puedeSoltarseLaConexion(PrinterStatus.Printing),
        )
    }

    @Test
    fun `P1 todo lo demas se suelta — dejarlo abierto es el telefono descolgado`() {
        listOf(
            PrinterStatus.Connected,
            PrinterStatus.Disconnected,
            PrinterStatus.SinComprobar,
            PrinterStatus.Error("sin papel"),
            null,
        ).forEach { estado ->
            assertTrue("$estado deberia soltarse", puedeSoltarseLaConexion(estado))
        }
    }
}
