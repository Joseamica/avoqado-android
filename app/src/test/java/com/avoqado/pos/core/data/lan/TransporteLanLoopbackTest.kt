package com.avoqado.pos.core.data.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.StationInfo
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * El transporte único con sockets REALES por loopback (etapa 3 del KDS, 3.5, D1). NSD no existe en la JVM (el
 * `Context` es un mock ⇒ «NSD no disponible», se registra y se sigue): aquí se prueba el socket, el ruteo y el ciclo de
 * vida; el descubrimiento se prueba en el aparato (Cierre). Espejo de `TransporteLanLoopbackTests` de iOS.
 */
class TransporteLanLoopbackTest {

    private val config = MutableStateFlow(PrintConfig())
    private val printConfig = mockk<PrintConfigRepository> {
        // `config` a secas, dentro de este bloque, es la propiedad del MOCK (receptor implícito más cercano), no la de
        // la prueba: sin el calificador `returns` le pedía `getConfig()` a un mock sin respuesta y cada prueba moría.
        every { this@mockk.config } returns this@TransporteLanLoopbackTest.config
        every { getCurrentConfig() } answers { this@TransporteLanLoopbackTest.config.value }
    }
    private val outbox = mockk<SyncOutbox> { every { deviceId } returns "tablet-prueba" }
    private lateinit var transporte: TransporteLan

    @Before
    fun armar() {
        transporte = TransporteLan(mockk<Context>(relaxed = true), outbox, printConfig)
    }

    @After
    fun apagar() {
        transporte.detener()
    }

    /**
     * El puerto sale positivo dentro de `abrirSocket`, ANTES de que `encender` publique el peer propio: se espera a los
     * dos (revisión M4). 10 s y no 3: esta Mac corre con la carga por encima de 100.
     */
    private suspend fun esperarPuerto(t: TransporteLan = transporte): Int {
        withTimeout(10_000) { while (t.puerto <= 0 || t.peers.value.none { it.deviceId == "tablet-prueba" }) delay(25) }
        return t.puerto
    }

    private fun enviarLinea(puerto: Int, linea: String): String? = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", puerto), 1_000)
        s.soTimeout = 3_000
        s.getOutputStream().run { write((linea + "\n").toByteArray()); flush() }
        LineaAcotada.leer(BufferedInputStream(s.getInputStream()))
    }

    @Test
    fun `sin hub, sin receptor y sin pantallas en la config el socket NO se abre`() = runBlocking {
        transporte.iniciar("venue-1")
        delay(300)
        assertEquals(-1, transporte.puerto)
    }

    @Test
    fun `P1 con el hub conectado el socket abre y un lease va y vuelve por loopback`() = runBlocking {
        val server = LeaseServer(nowMillis = { 1_000_000 })
        transporte.iniciar("venue-1")
        transporte.conectarHub(server::respondTo)
        val puerto = esperarPuerto()

        val r = LeaseClient().acquire(LanPeer("otro", "127.0.0.1", puerto), "m5", "otro", "s1", "Juan")
        assertEquals(LeaseProtocol.STATUS_GRANTED, r?.status)
        assertEquals(listOf("m5"), LeaseClient().list(LanPeer("otro", "127.0.0.1", puerto))?.leases?.map { it.tableId })
        // Este aparato está en su propia lista: sirve leases y, sin Tablero, no anuncia estaciones.
        val yo = transporte.peers.value.single { it.deviceId == "tablet-prueba" }
        assertTrue(yo.sirveLeases)
        assertEquals(emptySet<String>(), yo.kdsStations)
    }

    @Test
    fun `P1 sin hub un lease recibe Operacion desconocida, y con receptor una comanda se acusa con su folio`() = runBlocking {
        val recibidas = mutableListOf<String>()
        transporte.iniciar("venue-1")
        transporte.activarReceptor(setOf("st_barra")) { recibidas += it.sourceKey; true }
        val puerto = esperarPuerto()

        val lease = LeaseClient().acquire(LanPeer("otro", "127.0.0.1", puerto), "m5", "otro", "s1", "Juan")
        assertEquals("Operación desconocida: acquire", lease?.message)

        val barra = KdsComanda(venueId = "venue-1", deviceId = "otro", sourceKey = "sale:ext-1:st_barra", stationId = "st_barra", orderNumber = "1", orderType = "En tienda", createdAtMillis = 1, items = emptyList())
        assertTrue(KdsLanProtocol.esAcuse(enviarLinea(puerto, KdsLanProtocol.encode(barra)), "sale:ext-1:st_barra"))
        val postres = barra.copy(sourceKey = "sale:ext-1:st_postres", stationId = "st_postres")
        assertFalse(KdsLanProtocol.esAcuse(enviarLinea(puerto, KdsLanProtocol.encode(postres)), "sale:ext-1:st_postres"))
        assertEquals(listOf("sale:ext-1:st_barra"), recibidas)
        assertFalse(transporte.peers.value.single { it.deviceId == "tablet-prueba" }.sirveLeases)
        assertEquals(setOf("st_barra"), transporte.pantallasDe("st_barra").map { it.kdsStations }.single())
    }

    /** Una fuente de conectividad FALSA: la prueba decide cuándo hay WiFi/Ethernet (I4) invocando al vigía registrado. */
    private val vigias = mutableListOf<ConnectivityManager.NetworkCallback>()
    private val wifi = mockk<Network>()

    private fun transporteConRed(): TransporteLan {
        val cm = mockk<ConnectivityManager>(relaxed = true) {
            every { registerNetworkCallback(any<NetworkRequest>(), capture(vigias)) } just runs
        }
        return TransporteLan(mockk<Context>(relaxed = true) { every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm }, outbox, printConfig)
    }

    /**
     * Paridad con iOS (ronda de la Task 3): «recibiendo» = receptor enganchado Y el socket sirviendo (y, desde I4, con
     * WiFi o Ethernet). La pantalla de cocina dice «recibiendo por el WiFi del local» con esto: enganchado sin socket (red
     * local apagada o reabriéndose) sería decirle a la cocina que le llegan comandas que en realidad salen en papel.
     */
    @Test
    fun `P1 receptorActivo exige el socket sirviendo, no solo el receptor enganchado`() = runBlocking {
        val t = transporteConRed()
        try {
            t.activarReceptor(setOf("st_barra")) { true } // enganchado, pero el transporte aún no arranca: sin socket
            t.iniciar("venue-1")
            vigias.single().onAvailable(wifi)
            assertTrue("con el socket sirviendo sí recibe", withTimeout(10_000) { while (!t.receptorActivo.value) delay(25); true })

            t.detener() // la red local se cae con el receptor todavía enganchado
            assertFalse("socket cerrado: ya no recibe", t.receptorActivo.value)

            t.iniciar("venue-1") // se reabre sola: el receptor sigue enganchado
            esperarPuerto(t)
            assertTrue(t.receptorActivo.value)

            t.desactivarReceptor()
            assertFalse(t.receptorActivo.value)
        } finally {
            t.detener()
        }
    }

    @Test
    fun `P1 enganchado sin socket NO es recibir`() {
        val t = transporteConRed()
        t.activarReceptor(setOf("st_barra")) { true }
        assertFalse(t.receptorActivo.value)
    }

    /**
     * I4 (revisión T8): `ServerSocket(0)` escucha en 0.0.0.0 y sigue «sirviendo» sólo por loopback cuando se cae el
     * router. Sin WiFi ni Ethernet nadie del local alcanza la pantalla: la banda NO puede decir que recibe por el WiFi.
     */
    @Test
    fun `P1 I4 sin WiFi ni Ethernet el receptor NO dice que recibe, y al volver la red si`() = runBlocking {
        val t = transporteConRed()
        try {
            t.iniciar("venue-1")
            t.activarReceptor(setOf("st_barra")) { true }
            esperarPuerto(t)
            assertFalse("socket abierto pero sin red local: no recibe", t.receptorActivo.value)

            vigias.single().onAvailable(wifi)
            assertTrue(t.receptorActivo.value)

            vigias.single().onLost(wifi) // se cae el router
            assertFalse(t.receptorActivo.value)
            assertTrue("el socket sigue abierto: es la red la que falta", t.puerto > 0)
        } finally {
            t.detener()
        }
    }

    /**
     * M3 (revisión T8): la pantalla se cerró (o cambió de estación) MIENTRAS se guardaba. Guardada queda; el acuse sólo
     * sale si sigue enganchado el MISMO receptor con esa estación. Sin acuse, la caja imprime el papel: el lado seguro.
     */
    @Test
    fun `P1 M3 si la pantalla se cierra mientras se guarda NO sale el acuse`() = runBlocking {
        // Una estación con pantalla en la config: la red local sigue viva al soltar el receptor (sin carrera con el cierre).
        config.value = PrintConfig(stations = listOf(StationInfo(id = "st_barra", name = "Barra", hasKitchenDisplay = true)))
        val guardadas = mutableListOf<String>()
        transporte.iniciar("venue-1")
        transporte.activarReceptor(setOf("st_barra")) { guardadas += it.sourceKey; transporte.desactivarReceptor(); true }
        val puerto = esperarPuerto()

        val barra = KdsComanda(venueId = "venue-1", deviceId = "otro", sourceKey = "sale:ext-1:st_barra", stationId = "st_barra", orderNumber = "1", orderType = "En tienda", createdAtMillis = 1, items = emptyList())
        val respuesta = enviarLinea(puerto, KdsLanProtocol.encode(barra))

        assertEquals("se guardó", listOf("sale:ext-1:st_barra"), guardadas)
        assertFalse("pero sin acuse: $respuesta", KdsLanProtocol.esAcuse(respuesta, "sale:ext-1:st_barra"))
    }

    /**
     * Task 8b (paridad iOS `RuteoLan.fijar`): `sincronizarReceptor()` re-llama `ReceptorDeComandas.activar` en CADA
     * lectura de rutina con una lambda NUEVA. Comparar identidad (`receptor === r`) le quitaba el acuse a un guardado
     * que cruzaba con esa re-activación de la MISMA estación: papel de más sin motivo. Con generación, una
     * re-activación que NO cambia de estación (y el receptor ya estaba puesto) no sube la generación y el acuse sale.
     */
    @Test
    fun `P1 una reactivacion de rutina con la MISMA estacion mientras se guarda SI acusa`() = runBlocking {
        val guardadas = mutableListOf<String>()
        transporte.iniciar("venue-1")
        transporte.activarReceptor(setOf("st_barra")) { c ->
            guardadas += c.sourceKey
            // Lambda NUEVA, misma estación: lo que hace `ReceptorDeComandas.activar` en cada sondeo.
            transporte.activarReceptor(setOf("st_barra")) { true }
            true
        }
        val puerto = esperarPuerto()

        val barra = KdsComanda(venueId = "venue-1", deviceId = "otro", sourceKey = "sale:ext-1:st_barra", stationId = "st_barra", orderNumber = "1", orderType = "En tienda", createdAtMillis = 1, items = emptyList())
        val respuesta = enviarLinea(puerto, KdsLanProtocol.encode(barra))

        assertEquals("se guardó", listOf("sale:ext-1:st_barra"), guardadas)
        assertTrue("re-activar con la MISMA estación no debe invalidar el acuse: $respuesta", KdsLanProtocol.esAcuse(respuesta, "sale:ext-1:st_barra"))
    }

    /** Complemento del anterior: un CAMBIO de estación mientras se guarda sigue sin acusar (la estación ya no coincide). */
    @Test
    fun `P1 un cambio de estacion mientras se guarda NO acusa`() = runBlocking {
        val guardadas = mutableListOf<String>()
        transporte.iniciar("venue-1")
        transporte.activarReceptor(setOf("st_barra")) { c ->
            guardadas += c.sourceKey
            transporte.activarReceptor(setOf("st_cocina")) { true }
            true
        }
        val puerto = esperarPuerto()

        val barra = KdsComanda(venueId = "venue-1", deviceId = "otro", sourceKey = "sale:ext-1:st_barra", stationId = "st_barra", orderNumber = "1", orderType = "En tienda", createdAtMillis = 1, items = emptyList())
        val respuesta = enviarLinea(puerto, KdsLanProtocol.encode(barra))

        assertEquals("se guardó", listOf("sale:ext-1:st_barra"), guardadas)
        assertFalse("cambiar de estación invalida el acuse: $respuesta", KdsLanProtocol.esAcuse(respuesta, "sale:ext-1:st_barra"))
    }

    /**
     * Ronda 1 (M1): el caso donde la GENERACIÓN es lo único que decide. El Tablero se va a segundo plano y vuelve
     * (desactivar + re-activar LA MISMA estación) mientras se guarda: al terminar, la estación SÍ está anunciada y hay
     * un receptor puesto — sólo la generación (dos incrementos) dice que ese guardado ya no es de esta pantalla. Las
     * otras dos pruebas de invalidación pasan por la pertenencia a la estación y no fijan el mecanismo: sin
     * `generacion++` en `fijarReceptor`, ésta es la que cae. Hay que dejar viva la red (una estación con pantalla en la
     * config), como en la de M3, para que soltar el receptor un instante no cierre el socket.
     */
    @Test
    fun `P1 desactivar y re-activar la MISMA estacion mientras se guarda NO acusa - solo la generacion lo decide`() = runBlocking {
        config.value = PrintConfig(stations = listOf(StationInfo(id = "st_barra", name = "Barra", hasKitchenDisplay = true)))
        val guardadas = mutableListOf<String>()
        transporte.iniciar("venue-1")
        transporte.activarReceptor(setOf("st_barra")) { c ->
            guardadas += c.sourceKey
            // El Tablero sale y vuelve: al terminar de guardar la estación sigue anunciada, con un receptor puesto.
            transporte.desactivarReceptor()
            transporte.activarReceptor(setOf("st_barra")) { true }
            true
        }
        val puerto = esperarPuerto()

        val barra = KdsComanda(venueId = "venue-1", deviceId = "otro", sourceKey = "sale:ext-1:st_barra", stationId = "st_barra", orderNumber = "1", orderType = "En tienda", createdAtMillis = 1, items = emptyList())
        val respuesta = enviarLinea(puerto, KdsLanProtocol.encode(barra))

        assertEquals("se guardó", listOf("sale:ext-1:st_barra"), guardadas)
        assertFalse("desactivar y volver a activar invalida el acuse aunque la estación coincida: $respuesta", KdsLanProtocol.esAcuse(respuesta, "sale:ext-1:st_barra"))
    }

    /**
     * M2 (revisión final, 8b OOS4): cambiar de sucursal en la tablet de cocina reinicia el transporte (`detener` +
     * `iniciar`) con el receptor todavía enganchado a la MISMA estación. Un guardado que cruzaba ese reinicio conservaba
     * su acuse y la fila quedaba bajo la sucursal vieja, donde nadie la ve. `detener` sube la generación: sin acuse, la caja
     * imprime el papel.
     */
    @Test
    fun `P1 M2 reiniciar el transporte mientras se guarda NO acusa`() = runBlocking {
        val guardadas = mutableListOf<String>()
        transporte.iniciar("venue-1")
        transporte.activarReceptor(setOf("st_barra")) { c ->
            guardadas += c.sourceKey
            transporte.detener() // cambio de sucursal: el receptor sigue puesto con la misma estación
            transporte.iniciar("venue-2")
            true
        }
        val puerto = esperarPuerto()

        val barra = KdsComanda(venueId = "venue-1", deviceId = "otro", sourceKey = "sale:ext-1:st_barra", stationId = "st_barra", orderNumber = "1", orderType = "En tienda", createdAtMillis = 1, items = emptyList())
        val respuesta = enviarLinea(puerto, KdsLanProtocol.encode(barra))

        assertEquals("se guardó", listOf("sale:ext-1:st_barra"), guardadas)
        assertFalse("reiniciar el transporte invalida el acuse: $respuesta", KdsLanProtocol.esAcuse(respuesta, "sale:ext-1:st_barra"))
    }

    @Test
    fun `P1 una linea de mas de 64 KiB se corta sin responder`() = runBlocking {
        transporte.iniciar("venue-1")
        transporte.activarReceptor(setOf("st_barra")) { true }
        val puerto = esperarPuerto()
        val enorme = "{\"op\":\"comanda\",\"x\":\"" + "y".repeat(LineaAcotada.MAX_BYTES + 10) + "\"}"
        assertEquals(null, runCatching { enviarLinea(puerto, enorme) }.getOrNull())
    }

    @Test
    fun `detener cierra el socket y cambiar de venue reinicia`() = runBlocking {
        transporte.iniciar("venue-1")
        transporte.conectarHub(LeaseServer()::respondTo)
        val p1 = esperarPuerto()
        transporte.detener()
        assertEquals(-1, transporte.puerto)
        assertTrue(runCatching { enviarLinea(p1, "{}") }.isFailure)

        transporte.iniciar("venue-2")
        transporte.conectarHub(LeaseServer()::respondTo)
        assertTrue(esperarPuerto() > 0)
    }

    @Test
    fun `una estacion activa con pantalla abre el socket aunque no haya hub ni receptor`() = runBlocking {
        config.value = PrintConfig(stations = listOf(StationInfo(id = "st_barra", name = "Barra", hasKitchenDisplay = true)))
        transporte.iniciar("venue-1")
        assertTrue(esperarPuerto() > 0)
    }

    /**
     * Revisión I3: el plazo de 3 s es para la LÍNEA ENTERA, como `LectorDeLinea` de iOS. `soTimeout` sólo corta un
     * silencio: un peer que gotea un byte cada 500 ms nunca lo dispara y retendría el hilo 65 536 × 3 s.
     */
    @Test
    fun `P1 un peer que gotea un byte cada medio segundo se corta a los 3 s de la linea entera`() = runBlocking {
        transporte.iniciar("venue-1")
        transporte.activarReceptor(setOf("st_barra")) { true }
        val puerto = esperarPuerto()
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", puerto), 1_000)
            s.soTimeout = 9_000
            val inicio = System.nanoTime()
            val goteo = launch(Dispatchers.IO) {
                runCatching {
                    val out = s.getOutputStream()
                    out.write('{'.code); out.flush()
                    while (isActive) { delay(500); out.write('a'.code); out.flush() }
                }
            }
            val fin = runCatching { s.getInputStream().read() } // -1 (cerró) o reset; nunca una respuesta
            val ms = (System.nanoTime() - inicio) / 1_000_000
            goteo.cancelAndJoin()
            assertFalse("el servidor no cortó: venció el plazo del cliente ($ms ms)", fin.exceptionOrNull() is SocketTimeoutException)
            assertTrue("cerró sin responder: $fin", fin.getOrNull()?.let { it == -1 } ?: true)
            assertTrue("cortó a los $ms ms, no a los 3 s de la línea", ms in 2_500..6_500)
        }
    }

    /**
     * Revisión I2: un resolve que contesta DESPUÉS de apagar la red (cambio de sucursal, cierre de sesión, config) no
     * puede colarse al plano cuando la red vuelve: un peer viejo con `hub=1` ganaría la elección aquí y no en los demás.
     */
    @Test
    fun `P1 un resolve tardio de la red apagada no entra al plano cuando la red vuelve`() = runBlocking {
        val busquedas = mutableListOf<NsdManager.DiscoveryListener>()
        val resoluciones = mutableListOf<NsdManager.ResolveListener>()
        val nsd = mockk<NsdManager>(relaxed = true) {
            every { discoverServices(any<String>(), any<Int>(), capture(busquedas)) } just runs
            every { resolveService(any(), capture(resoluciones)) } just runs
        }
        val otro = mockk<NsdServiceInfo>(relaxed = true) {
            every { serviceType } returns "._avoqado-pos._tcp."
            every { serviceName } returns "Avoqado-POS-otro-a"
            every { attributes } returns mapOf("did" to "otro-aaa".toByteArray(), "venue" to "venue-1".toByteArray(), "hub" to "1".toByteArray())
            every { host } returns InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
            every { port } returns 4321
        }
        val t = TransporteLan(mockk<Context>(relaxed = true) { every { getSystemService(Context.NSD_SERVICE) } returns nsd }, outbox, printConfig)
        try {
            t.iniciar("venue-1")
            t.conectarHub(LeaseServer()::respondTo)
            esperarPuerto(t)
            busquedas.first().onServiceFound(otro)

            t.desconectarHub() // la red local se apaga con el resolve todavía en vuelo
            withTimeout(10_000) { while (t.puerto > 0) delay(25) }
            resoluciones.single().onServiceResolved(otro)

            t.conectarHub(LeaseServer()::respondTo)
            esperarPuerto(t)
            assertEquals(listOf("tablet-prueba"), t.peers.value.map { it.deviceId })
        } finally {
            t.detener()
        }
    }
}
