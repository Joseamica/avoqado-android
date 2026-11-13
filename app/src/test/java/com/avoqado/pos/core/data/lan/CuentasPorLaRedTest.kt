package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * D12 · Opción D, pieza 3: el anuncio (`d=1` y el nombre de la caja), el cable de las ops `cuenta_*`, el enrutador y el plazo ABSOLUTO
 * de 5 s con reintento que cierra el socket (R5, R7). Mesas no cambia: sus pruebas siguen en LanTxtTest, EnrutadorLanTest y compañía.
 */
class CuentasPorLaRedTest {

    // MARK: - Anuncio (R5)

    @Test fun `el TXT dice d=1 y el nombre de la caja sólo con las cuentas enganchadas`() {
        val sin = LanTxt.construir("t1", "v", false, 1, emptySet(), hub = true)
        assertFalse(sin.containsKey("d"))
        assertFalse(sin.containsKey("caja"))
        val con = LanTxt.construir("t1", "v", false, 1, emptySet(), hub = true, cajaConD = "  Caja 1 · Barra  ")
        assertEquals("1", con["d"])
        assertEquals("Caja 1 · Barra", con["caja"])
        assertEquals(60, LanTxt.construir("t1", "v", false, 1, emptySet(), true, cajaConD = "x".repeat(300))["caja"]!!.length)
    }

    @Test fun `sólo participa una caja de MI sucursal que anuncia d=1`() {
        val txt = mapOf("did" to "otro", "venue" to "v", "d" to "1", "caja" to "Caja 2")
        val peer = LanTxt.peerDesde(txt, "10.0.0.2", 9000, "yo", "v")!!
        assertTrue(peer.participaEnD)
        assertEquals("Caja 2", peer.nombreCaja)
        assertFalse(LanTxt.peerDesde(txt - "d", "10.0.0.2", 9000, "yo", "v")!!.participaEnD) // app vieja
        assertFalse(LanTxt.peerDesde(txt - "venue", "10.0.0.2", 9000, "yo", "v")!!.participaEnD) // sin venue explícito
        assertFalse(LanTxt.peerDesde(txt + ("d" to "2"), "10.0.0.2", 9000, "yo", "v")!!.participaEnD)
    }

    // MARK: - Cable

    @Test fun `cada respuesta de la dueña viaja y regresa igual, y lo demás es no contestó`() {
        val todas = listOf(Respuesta.Concedida, Respuesta.Ocupada("Ana", "Caja 2"), Respuesta.Cerrada("Caja 2", true), Respuesta.Cerrada("", false), Respuesta.Rechazada)
        for (r in todas) {
            val ida = LeaseProtocol.decodeResponse(LeaseProtocol.encode(CuentasPorLaRed.aCable(r)))
            assertEquals(r, CuentasPorLaRed.desdeCable(ida))
        }
        assertNull(CuentasPorLaRed.desdeCable(CuentasPorLaRed.aCable(null)))
        assertNull(CuentasPorLaRed.desdeCable(null))
        assertNull(CuentasPorLaRed.desdeCable(LeaseResponse(status = LeaseProtocol.STATUS_ERROR)))
    }

    @Test fun `una petición vieja sin los campos nuevos se sigue leyendo`() {
        val vieja = """{"v":1,"op":"acquire","tableId":"m1","deviceId":"d","staffId":"s","staffName":"Ana","epoch":0}"""
        val r = LeaseProtocol.decodeRequest(vieja)!!
        assertEquals("", r.intento)
        assertFalse(r.enLinea)
    }

    // MARK: - Contestar con el registro de la dueña

    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private val cache = mockk<PayloadCache> {
        coEvery { loadDurable(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { saveDurable(any(), any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0) }
    }
    private val abiertas = mockk<CuentasAbiertasAqui> { coEvery { todas(any()) } returns setOf("o1") }
    private val dueno = DuenoDeCuentas(cache, abiertas, { _, _, _ -> false }, { _, _ -> false }, { null }, { "caja-1" }, { "Caja 1" })
        .also { it.reloj = { Instante("b", 1_000) }; it.horaDePared = { 1L } }

    private suspend fun preguntar(
        op: String, orden: String, intento: String = "i-ana", enLinea: Boolean = false, subida: Boolean = false, sucursal: String = "v1",
    ): LeaseResponse = LeaseProtocol.decodeResponse(CuentasPorLaRed.contestar(LeaseProtocol.encode(
        CuentasPorLaRed.pedido(op, orden, "caja-2", "Caja 2", "Ana", intento, enLinea, subida, venueId = sucursal)), "v1", dueno))!!

    /** Ronda 1 de la tubería, Menor 2: el pedido lleva la sucursal de quien pregunta; una caja de OTRA contesta error, nunca «no es mía». */
    @Test fun `una caja de otra sucursal contesta error, nunca no es mía`() = runTest {
        for (op in listOf(LeaseProtocol.OP_CUENTA_APARTAR, LeaseProtocol.OP_CUENTA_SOLTAR, LeaseProtocol.OP_CUENTA_COBRADA)) {
            assertEquals(op, LeaseProtocol.STATUS_ERROR, preguntar(op, "o-ajena", sucursal = "otra-sucursal").status)
            assertEquals(op, LeaseProtocol.STATUS_ERROR, preguntar(op, "o1", sucursal = "otra-sucursal").status)
        }
        assertEquals(LeaseProtocol.STATUS_NO_ES_MIA, preguntar(LeaseProtocol.OP_CUENTA_APARTAR, "o-ajena").status)
    }

    @Test fun `la dueña contesta apartar, comprometer, suelta y cobrada, y por una que no abrió dice no_es_mia`() = runTest {
        assertEquals(LeaseProtocol.STATUS_GRANTED, preguntar(LeaseProtocol.OP_CUENTA_APARTAR, "o1").status)
        assertEquals(LeaseProtocol.STATUS_GRANTED, preguntar(LeaseProtocol.OP_CUENTA_COMPROMETER, "o1").status)
        val otra = preguntar(LeaseProtocol.OP_CUENTA_APARTAR, "o1", intento = "i-beto")
        assertEquals(Respuesta.Ocupada("Ana", "Caja 2"), CuentasPorLaRed.desdeCable(otra))
        assertEquals(LeaseProtocol.STATUS_OK, preguntar(LeaseProtocol.OP_CUENTA_COBRADA, "o1", subida = false).status)
        assertEquals(Respuesta.Cerrada("Caja 2", true), CuentasPorLaRed.desdeCable(preguntar(LeaseProtocol.OP_CUENTA_APARTAR, "o1", intento = "i-beto")))
        assertEquals(LeaseProtocol.STATUS_NO_ES_MIA, preguntar(LeaseProtocol.OP_CUENTA_APARTAR, "o-ajena").status)
        assertEquals(LeaseProtocol.STATUS_NO_ES_MIA, preguntar(LeaseProtocol.OP_CUENTA_SOLTAR, "o-ajena").status)
    }

    @Test fun `línea rota, otra versión, llave de mesa u op desconocida dan error`() = runTest {
        assertEquals(LeaseProtocol.STATUS_ERROR, LeaseProtocol.decodeResponse(CuentasPorLaRed.contestar("{", "v1", dueno))!!.status)
        val otraVersion = LeaseProtocol.encode(LeaseRequest(version = 2, op = LeaseProtocol.OP_CUENTA_APARTAR, tableId = "cuenta:o1"))
        assertEquals(LeaseProtocol.ERROR_VERSION_MISMATCH, LeaseProtocol.decodeResponse(CuentasPorLaRed.contestar(otraVersion, "v1", dueno))!!.message)
        val mesa = LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_CUENTA_APARTAR, tableId = "mesa-5"))
        assertEquals(LeaseProtocol.STATUS_ERROR, LeaseProtocol.decodeResponse(CuentasPorLaRed.contestar(mesa, "v1", dueno))!!.status)
        val rara = LeaseProtocol.encode(LeaseRequest(op = "cuenta_otra", tableId = "cuenta:o1"))
        assertEquals(LeaseProtocol.STATUS_ERROR, LeaseProtocol.decodeResponse(CuentasPorLaRed.contestar(rara, "v1", dueno))!!.status)
    }

    @Test fun `el enrutador manda cuenta_ al manejador y sin él dice operación desconocida, sin tocar el hub de mesas`() = runTest {
        val linea = LeaseProtocol.encode(CuentasPorLaRed.pedido(LeaseProtocol.OP_CUENTA_APARTAR, "o1", "caja-2", "Caja 2", "Ana", "i", venueId = "v1"))
        var alHub = 0
        val hub: (String) -> LeaseResponse = { alHub++; LeaseResponse(status = LeaseProtocol.STATUS_OK) }
        assertEquals("CUENTAS", EnrutadorLan.responder(linea, "v1", emptySet(), hub, null, cuentas = { "CUENTAS" }))
        val sin = LeaseProtocol.decodeResponse(EnrutadorLan.responder(linea, "v1", emptySet(), hub, null))!!
        assertEquals("Operación desconocida: cuenta_apartar", sin.message)
        assertEquals(0, alHub)
        // Una op de mesas sigue yendo al hub.
        EnrutadorLan.responder(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_LIST)), "v1", emptySet(), hub, null, cuentas = { "CUENTAS" })
        assertEquals(1, alHub)
    }

    // MARK: - 5 s de verdad (R7), con plazos chicos

    private val pedido = CuentasPorLaRed.pedido(LeaseProtocol.OP_CUENTA_APARTAR, "o1", "caja-2", "Caja 2", "Ana", "i", venueId = "v1")

    private fun servidor(atender: (java.net.Socket) -> Unit): Pair<ServerSocket, AtomicInteger> {
        val ss = ServerSocket(0)
        val conexiones = AtomicInteger()
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                conexiones.incrementAndGet()
                thread(isDaemon = true) { runCatching { atender(s) } }
            }
        }
        return ss to conexiones
    }

    private fun peer(ss: ServerSocket) = LanPeer("caja-1", "127.0.0.1", ss.localPort, participaEnD = true, venueExplicito = true)

    @Test fun `una caja muda - reintenta una vez y se rinde dentro del plazo total, cerrando el socket`() = runBlocking {
        val (ss, conexiones) = servidor { Thread.sleep(5_000) }
        val inicio = System.nanoTime()
        assertNull(LeaseClient().preguntarCuenta(peer(ss), pedido, plazoTotalMs = 700, plazoIntentoMs = 300))
        val ms = (System.nanoTime() - inicio) / 1_000_000
        assertTrue("tardó $ms ms", ms < 1_000)
        assertEquals(2, conexiones.get())
        ss.close()
    }

    @Test fun `una respuesta que gotea sin salto de línea no burla el plazo`() = runBlocking {
        val (ss, _) = servidor { s -> repeat(100) { s.getOutputStream().write('x'.code); s.getOutputStream().flush(); Thread.sleep(50) } }
        val inicio = System.nanoTime()
        assertNull(LeaseClient().preguntarCuenta(peer(ss), pedido, plazoTotalMs = 700, plazoIntentoMs = 300))
        assertTrue((System.nanoTime() - inicio) / 1_000_000 < 1_000)
        ss.close()
    }

    @Test fun `la dueña que contesta, contesta, y nadie escuchando es no contestó`() = runBlocking {
        val (ss, _) = servidor { s ->
            s.getInputStream().bufferedReader().readLine()
            s.getOutputStream().write((LeaseProtocol.encode(LeaseResponse(status = LeaseProtocol.STATUS_GRANTED)) + "\n").toByteArray())
            s.getOutputStream().flush()
            s.close()
        }
        // Prueba inestable en el CI de GitHub (12-nov, PR #220, #223 y #224): el plazo de 2 s por intento podía vencer en un runner
        // cargado, y un puerto recién cerrado lo puede tomar otra prueba. Aquí se prueba la conducta, no la velocidad: plazo amplio para
        // la que contesta, y el puerto 1 (nadie escucha ahí) para «nadie escuchando».
        assertEquals("la dueña contestó", LeaseProtocol.STATUS_GRANTED,
            LeaseClient().preguntarCuenta(peer(ss), pedido, plazoTotalMs = 20_000, plazoIntentoMs = 10_000)?.status)
        ss.close()
        assertNull("nadie escuchando", LeaseClient().preguntarCuenta(LanPeer("x", "127.0.0.1", 1), pedido, plazoTotalMs = 2_000, plazoIntentoMs = 1_000))
    }
}
