package com.avoqado.pos.maquina

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.domain.RoleManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/**
 * C6 (R5#2, R5#3, R5#10): lo que la hoja «Máquina de efectivo» necesita sin pantalla — la IP, las preferencias del aparato,
 * el permiso de la sesión, el freno de los cobros sin subir, «Probar conexión» y la llamada de emparejamiento.
 */
class AjustesDeLaMaquinaTest {

    private val servidor = MockWebServer()
    private val okHttp = OkHttpClient()

    @After
    fun apagar() {
        runCatching { servidor.shutdown() }
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
    }

    // MARK: - IP

    @Test
    fun `la IP de la maquina es una IPv4 escrita como la pide el servidor`() {
        listOf("192.168.1.50", " 10.0.0.7 ", "0.0.0.0", "255.255.255.255").forEach { assertTrue(it, esIpv4(it)) }
        listOf(
            "", "192.168.1", "192.168.1.256", "192.168.01.5", "a.b.c.d", "192.168.1.50:8080", "1.2.3.4.5", "::1",
            "192.168.1.-1", "192.168..1", "１９２.168.1.1",
        ).forEach { assertFalse(it, esIpv4(it)) }
    }

    // MARK: - Preferencias del aparato

    @Test
    fun `las preferencias guardan la IP y si la maquina esta activa, y otra instancia las lee`() {
        val contexto = contextoConPreferencias()
        val prefs = MaquinaDeEfectivoPrefs(contexto)
        assertNull(prefs.ip)
        assertFalse(prefs.activa)

        prefs.guardar("192.168.1.50", activa = true, venueId = "venue-1", nombreDeLaSucursal = "La Galeterie Centro")

        val otra = MaquinaDeEfectivoPrefs(contexto)
        assertEquals("192.168.1.50", otra.ip)
        assertTrue(otra.activa)
        assertEquals("venue-1" to "La Galeterie Centro", otra.venueId to otra.nombreDeLaSucursal)
        assertTrue("Codex código r1 #10: activa en SU sucursal", otra.activaEn("venue-1"))
        assertFalse("y no en otra", otra.activaEn("venue-2"))
        otra.guardar("192.168.1.50", activa = false)
        assertFalse(MaquinaDeEfectivoPrefs(contexto).activa)
        assertEquals("apagar no olvida la sucursal", "venue-1", MaquinaDeEfectivoPrefs(contexto).venueId)
    }

    // MARK: - Permiso (R5#10)

    @Test
    fun `configurar la maquina pide cash-machine configure de la sesion, sin override de PIN`() {
        val storage = mockk<SecureStorage>()
        val roles = RoleManager(storage)
        every { storage.userRole } returns "CASHIER"
        every { storage.venuePermissions } returns listOf("payments:create", "cash-machine:configure")
        assertTrue(roles.canConfigureCashMachine)
        every { storage.venuePermissions } returns listOf("payments:create", "payment:create-manual")
        assertFalse(roles.canConfigureCashMachine)
        every { storage.venuePermissions } returns listOf("cash-machine:*")
        assertTrue(roles.canConfigureCashMachine)
    }

    @Test
    fun `sin la lista de permisos el respaldo es gerente para arriba`() {
        val storage = mockk<SecureStorage>()
        val roles = RoleManager(storage)
        every { storage.venuePermissions } returns emptyList()
        listOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN").forEach {
            every { storage.userRole } returns it
            assertTrue(it, roles.canConfigureCashMachine)
        }
        listOf("CASHIER", "WAITER").forEach {
            every { storage.userRole } returns it
            assertFalse(it, roles.canConfigureCashMachine)
        }
    }

    // MARK: - Freno (R5#2): el MISMO SQL que compila Room

    @Test
    fun `P1 frenan los intentos abiertos, las dudas y los cierres sin acuse, y nada mas`() = runBlocking {
        CobroEnMaquinaSobreSqlite().use { dao ->
            assertFalse(dao.hayPendientesDeLaMaquina())
            dao.insertar(fila("AQ000000000001", EstadoDelIntento.PAGADO, subido = true))
            dao.insertar(fila("AQ000000000002", EstadoDelIntento.CANCELADO, subido = true))
            dao.insertar(fila("AQ000000000003", EstadoDelIntento.GUARDADO, subido = true))
            dao.insertar(fila("AQ000000000004", EstadoDelIntento.RESUELTO))
            // Codex código r1 #9: lo que el SERVIDOR ya guardó como incidencia (la duda subida, en conflicto o no) ya no frena.
            dao.insertar(fila("AQ000000000005", EstadoDelIntento.ESPERANDO, dudaSubida = true))
            dao.insertar(fila("AQ000000000006", EstadoDelIntento.ESPERANDO, dudaSubida = true).copy(conflicto = "otra PC", respuestaServidor = "{}"))
            assertEquals(0, dao.pendientes(null))
            assertFalse(dao.hayPendientesDeLaMaquina())

            val frenan = listOf(
                fila("AQ00000000000A", EstadoDelIntento.EN_CURSO),
                fila("AQ00000000000B", EstadoDelIntento.ESPERANDO, dudaSubida = true).copy(cierreTardio = "PAGADO"),   // su cierre no ha subido
                fila("AQ00000000000C", EstadoDelIntento.ESPERANDO),
                fila("AQ00000000000D", EstadoDelIntento.PAGADO),
                fila("AQ00000000000E", EstadoDelIntento.CANCELADO),
                fila("AQ00000000000F", EstadoDelIntento.GUARDADO, venue = "venue-2"),
            )
            frenan.forEachIndexed { i, f ->
                dao.insertar(f)
                assertEquals(f.estado, i + 1, dao.pendientes(null))
            }
            assertTrue(dao.hayPendientesDeLaMaquina())
            assertEquals("por sucursal", 5, dao.pendientes("venue-1"))
            assertEquals(1, dao.pendientes("venue-2"))
            assertEquals(0, dao.pendientes("venue-3"))
        }
    }

    // MARK: - Probar conexión

    @Test
    fun `probar conexion dice que si cuando la maquina manda su sockets`() = runBlocking {
        servidor.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(SOCKETS)
            }
        }))
        servidor.start()

        assertTrue(probarConexion(servidor.hostName, okHttp, puerto = servidor.port))
    }

    @Test
    fun `P1 con una IP que no contesta dice que no a los 5 s, no despues`() = runBlocking {
        val puertoCerrado = ServerSocket(0).use { it.localPort }
        val inicio = System.currentTimeMillis()

        assertFalse(probarConexion("127.0.0.1", okHttp, puerto = puertoCerrado))

        val tardo = System.currentTimeMillis() - inicio
        assertTrue("tardó $tardo ms", tardo in 4_900L..5_600L)
    }

    @Test
    fun `un servidor que acepta pero nunca manda sockets no cuenta como maquina`() = runBlocking {
        servidor.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        servidor.start()

        assertFalse(probarConexion(servidor.hostName, okHttp, puerto = servidor.port, plazoMs = 600))
    }

    // MARK: - Emparejamiento con el SERVIDOR

    @Test
    fun `emparejar manda la IP a la sucursal activa y devuelve la credencial`() = runBlocking {
        servidor.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"bindingId":"b1","credential":"cred-1"}"""))
        servidor.start()

        val emparejado = emparejamiento().emparejar("192.168.1.50").getOrThrow()

        assertEquals(Emparejado("cred-1", "venue-1", "La Galeterie"), emparejado)
        val pedido = servidor.takeRequest()
        assertEquals("POST", pedido.method)
        assertEquals("/api/v1/mobile/venues/venue-1/cash-machine/binding", pedido.path)
        assertEquals("192.168.1.50", JSONObject(pedido.body.readUtf8()).getString("machineIp"))
        // El 403 lo explica la hoja: nunca el diálogo de PIN de gerente (R5#10).
        assertEquals("1", pedido.getHeader(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
    }

    @Test
    fun `desemparejar manda DELETE a la sucursal activa`() = runBlocking {
        servidor.enqueue(MockResponse().setBody("""{"success":true,"revoked":true}"""))
        servidor.start()

        assertTrue(emparejamiento().desemparejar().isSuccess)

        val pedido = servidor.takeRequest()
        assertEquals("DELETE", pedido.method)
        assertEquals("/api/v1/mobile/venues/venue-1/cash-machine/binding", pedido.path)
        assertEquals("1", pedido.getHeader(ForbiddenInterceptor.LOCAL_ERROR_HEADER))
    }

    @Test
    fun `sin permiso el servidor responde 403 y se le pide a un gerente que entre con su PIN`() = runBlocking {
        servidor.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))
        servidor.start()

        val falla = emparejamiento().emparejar("192.168.1.50").exceptionOrNull()

        assertEquals(TEXTO_SIN_PERMISO, falla?.message)
    }

    @Test
    fun `sin red la falla es de red, para que la hoja diga que hace falta internet`() = runBlocking {
        servidor.start()
        val repo = emparejamiento()
        servidor.shutdown()

        assertTrue(repo.emparejar("192.168.1.50").exceptionOrNull() is IOException)
        assertTrue(repo.desemparejar().exceptionOrNull() is IOException)
    }

    private fun emparejamiento() = EmparejamientoDeLaMaquina(
        mockk {
            every { venueId } returns "venue-1"
            every { venueName } returns "La Galeterie"
        },
        okHttp,
    ).apply { baseUrl = servidor.url("/api/v1").toString().trimEnd('/') }

    companion object {
        const val SOCKETS = """{"comando":"sockets","cobrando":false,"Due":0,"Credit":0,"changeBar":"022210221000"}"""

        fun fila(
            ref: String,
            estado: String,
            subido: Boolean = false,
            dudaSubida: Boolean = false,
            venue: String = "venue-1",
        ) = CobroEnMaquinaEntity(
            ref, venue, "venta-$ref", "{}", 18_000, 0, 18_000, null, null, creadoEn = 1, estado = estado,
            subido = subido, dudaSubida = dudaSubida,
        )

        /** `SharedPreferences` en un mapa (patrón `KdsPrefsTest`): sin Robolectric. */
        fun contextoConPreferencias(): Context {
            val guardado = mutableMapOf<String, Any?>()
            val editor = mockk<SharedPreferences.Editor>()
            val prefs = mockk<SharedPreferences>()
            every { prefs.getString(any(), any()) } answers { guardado[firstArg<String>()] as String? ?: secondArg<String?>() }
            every { prefs.getBoolean(any(), any()) } answers { guardado[firstArg<String>()] as Boolean? ?: secondArg<Boolean>() }
            every { prefs.edit() } returns editor
            every { editor.putString(any(), any()) } answers { guardado[firstArg<String>()] = secondArg<String?>(); editor }
            every { editor.putBoolean(any(), any()) } answers { guardado[firstArg<String>()] = secondArg<Boolean>(); editor }
            every { editor.commit() } returns true
            return mockk { every { getSharedPreferences(MaquinaDeEfectivoPrefs.NOMBRE, Context.MODE_PRIVATE) } returns prefs }
        }
    }
}
