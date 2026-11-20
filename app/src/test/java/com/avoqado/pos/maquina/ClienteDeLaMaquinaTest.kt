package com.avoqado.pos.maquina

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * El cliente WebSocket contra un servidor de verdad (MockWebServer con `withWebSocketUpgrade`): cada conexión nueva
 * sube la generación, cada mensaje sale con la de la conexión que lo recibió y `enviar` no sale por una conexión que ya
 * no es la de la generación pedida (R3#2). Tiempo real con intervalos cortos: con tiempo virtual el ping sería un
 * timer infinito.
 */
class ClienteDeLaMaquinaTest {

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val okHttp = OkHttpClient()
    private val ahora = 1_763_481_000_000L

    /** Una conexión del lado de la máquina: [numero] empieza en 1, en el orden en que llegan. */
    private inner class LadoMaquina(val numero: Int) : WebSocketListener() {
        lateinit var socket: WebSocket
        val recibidos = CopyOnWriteArrayList<String>()
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            alAbrir(this)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            recibidos += text
            alRecibir(this, text)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private val conexiones = CopyOnWriteArrayList<LadoMaquina>()
    @Volatile private var alAbrir: (LadoMaquina) -> Unit = {}
    @Volatile private var alRecibir: (LadoMaquina, String) -> Unit = { _, _ -> }

    @Before
    fun arrancar() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val lado = synchronized(conexiones) { LadoMaquina(conexiones.size + 1).also { conexiones += it } }
                return MockResponse().withWebSocketUpgrade(lado)
            }
        }
        server.start()
    }

    @After
    fun apagar() {
        scope.cancel()
        runCatching { server.shutdown() }
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
    }

    private fun cliente(pingMs: Long = 60_000) =
        ClienteDeLaMaquina(okHttp, scope, { ahora }, reconexionMs = 100, pingMs = pingMs)

    private fun ClienteDeLaMaquina.conectarAlServidor() = conectar(server.hostName, server.port)

    /** Se suscribe ANTES de conectar: `recibidos` no repite lo que ya pasó. */
    private fun escuchar(c: ClienteDeLaMaquina): List<Recibido> {
        val lista = CopyOnWriteArrayList<Recibido>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { c.recibidos.collect { lista += it } }
        return lista
    }

    private fun esperar(que: String, ms: Long = 5_000, condicion: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!condicion()) {
            check(System.currentTimeMillis() < fin) { "No pasó a tiempo: $que" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `conecta y entrega el sockets con la generación 1`() {
        alAbrir = { it.socket.send(SOCKETS) }
        val c = cliente()
        val lista = escuchar(c)

        c.conectarAlServidor()

        esperar("el sockets") { lista.isNotEmpty() }
        assertEquals(Recibido(1, MensajeDeLaMaquina.Sockets(false, 0, 0, "022210221000")), lista.single())
        assertEquals(Conexion.CONECTADA, c.conexion.value)
        assertEquals(1, c.generacion.value)
    }

    @Test
    fun `P1 si la máquina cierra, reconecta solo con la generación siguiente`() {
        alAbrir = { lado ->
            lado.socket.send(SOCKETS)
            if (lado.numero == 1) lado.socket.close(1000, "adiós")
        }
        val c = cliente()
        val lista = escuchar(c)

        c.conectarAlServidor()

        esperar("la segunda conexión") { lista.any { it.generacion == 2 } && c.conexion.value == Conexion.CONECTADA }
        assertEquals(listOf(1, 2), lista.map { it.generacion })
        assertEquals(2, c.generacion.value)
        assertEquals(2, conexiones.size)
    }

    @Test
    fun `P1 enviar con la generación vieja tras reconectar no manda nada`() {
        alAbrir = { lado ->
            lado.socket.send(SOCKETS)
            if (lado.numero == 1) lado.socket.close(1000, "adiós")
        }
        val c = cliente()
        val lista = escuchar(c)
        c.conectarAlServidor()
        esperar("la segunda conexión") { lista.any { it.generacion == 2 } && c.conexion.value == Conexion.CONECTADA }

        assertFalse(c.enviar(ping("vieja"), generacion = 1))
        assertTrue(c.enviar(ping("nueva"), generacion = 2))

        esperar("el ping nuevo") { conexiones[1].recibidos.isNotEmpty() }
        Thread.sleep(200)
        val fechas = conexiones.flatMap { it.recibidos }.map { JSONObject(it).getString("fecha") }
        assertEquals(listOf("nueva"), fechas)
    }

    @Test
    fun `P1 enviar sin conexión devuelve false`() {
        val c = cliente()
        assertFalse(c.enviar(ping("x"), generacion = 0))
        assertFalse(c.enviar(ping("x"), generacion = 1))

        alAbrir = { it.socket.send(SOCKETS) }
        val lista = escuchar(c)
        c.conectarAlServidor()
        esperar("conectada") { lista.isNotEmpty() && c.conexion.value == Conexion.CONECTADA }
        c.desconectar()

        assertEquals(Conexion.DESCONECTADA, c.conexion.value)
        assertFalse(c.enviar(ping("tarde"), generacion = 1))
        Thread.sleep(200)
        assertTrue(conexiones.single().recibidos.isEmpty())
    }

    @Test
    fun `un mensaje mal formado no tumba la conexión ni el flujo`() {
        alAbrir = { lado ->
            lado.socket.send("{no es json")
            lado.socket.send("[1,2]")
            lado.socket.send(SOCKETS)
        }
        val c = cliente()
        val lista = escuchar(c)

        c.conectarAlServidor()

        esperar("el sockets") { lista.isNotEmpty() }
        Thread.sleep(200)
        assertEquals(1, lista.size)
        assertEquals(1, c.generacion.value)
        assertEquals(Conexion.CONECTADA, c.conexion.value)
        assertEquals(1, conexiones.size)
    }

    @Test
    fun `P1 un mensaje atrasado de la conexión vieja conserva su generación`() {
        alAbrir = { lado ->
            if (lado.numero == 1) {
                lado.socket.send(statusDe("AQ0000000000AA"))
                lado.socket.close(1000, "adiós")
            } else {
                lado.socket.send(statusDe("AQ0000000000BB"))
            }
        }
        val c = cliente()
        // Quien escucha va lento: procesa todo hasta que la conexión 2 ya está arriba.
        val compuerta = CompletableDeferred<Unit>()
        val vistos = CopyOnWriteArrayList<Pair<Recibido, Int>>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            c.recibidos.collect {
                compuerta.await()
                vistos += it to c.generacion.value
            }
        }

        c.conectarAlServidor()
        esperar("la segunda conexión") { c.generacion.value == 2 && c.conexion.value == Conexion.CONECTADA }
        Thread.sleep(200)
        compuerta.complete(Unit)

        esperar("los dos status") { vistos.size == 2 }
        val (primero, generacionAlProcesarlo) = vistos[0]
        assertEquals(2, generacionAlProcesarlo)
        assertEquals(1, primero.generacion)
        assertEquals("AQ0000000000AA", (primero.mensaje as MensajeDeLaMaquina.Status).referenceCC)
        assertEquals(2, vistos[1].first.generacion)
        assertEquals("AQ0000000000BB", (vistos[1].first.mensaje as MensajeDeLaMaquina.Status).referenceCC)
    }

    @Test
    fun `manda ping con fecha cada intervalo y la conexión que contesta sigue`() {
        alAbrir = { it.socket.send(SOCKETS) }
        alRecibir = { lado, texto -> if (JSONObject(texto).getString("comando") == "ping") lado.socket.send(PONG) }
        val c = cliente(pingMs = 200)
        val lista = escuchar(c)

        c.conectarAlServidor()

        esperar("tres pongs") { lista.count { it.mensaje is MensajeDeLaMaquina.Pong } >= 3 }
        Thread.sleep(500)
        val ping = JSONObject(conexiones.single().recibidos.first())
        assertEquals("ping", ping.getString("comando"))
        assertEquals(Instant.ofEpochMilli(ahora).toString(), ping.getString("fecha"))
        assertEquals(1, c.generacion.value)
        assertEquals(Conexion.CONECTADA, c.conexion.value)
    }

    @Test
    fun `P1 si la máquina deja de contestar, la conexión se da por muerta y reconecta`() {
        alAbrir = { lado -> if (lado.numero > 1) lado.socket.send(SOCKETS) }   // la 1 se queda callada
        val c = cliente(pingMs = 100)
        val lista = escuchar(c)

        c.conectarAlServidor()

        esperar("la segunda conexión") { lista.any { it.generacion == 2 } }
        assertTrue(conexiones[0].recibidos.isNotEmpty())   // le mandó ping y no contestó
        assertFalse(c.enviar(ping("vieja"), generacion = 1))
    }

    private companion object {
        val SOCKETS = """
            {
                "fecha": "2026-11-18 15:48:19",
                "comando": "sockets",
                "conexiones": "1",
                "cobrando": false,
                "Due": 0.0,
                "Credit": 0.0,
                "changeBar": "022210221000",
                "tarjeta": true,
                "formaPago": ""
            }
        """.trimIndent()

        val PONG = """{"fecha":"2026-11-18 15:48:19","comando":"pong","conexiones":"1","tarjeta":true}"""

        fun statusDe(referencia: String) =
            """{"fecha":"2026-11-18 15:48:19","comando":"status","conexiones":"1","cobrando":true,"Status":null,""" +
                """"referenceCC":"$referencia","Credit":0.0,"Due":125.00,"changeDispensed":0.0,"changeBar":"022210221000"}"""
    }
}
