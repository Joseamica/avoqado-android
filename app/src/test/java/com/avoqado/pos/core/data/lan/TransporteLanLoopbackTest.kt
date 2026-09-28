package com.avoqado.pos.core.data.lan

import android.content.Context
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
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
import java.net.InetSocketAddress
import java.net.Socket

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

    private suspend fun esperarPuerto(): Int {
        withTimeout(3_000) { while (transporte.puerto <= 0) delay(25) }
        return transporte.puerto
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
}
