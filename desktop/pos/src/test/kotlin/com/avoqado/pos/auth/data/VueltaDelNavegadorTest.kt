package com.avoqado.pos.auth.data

import androidx.fragment.app.FragmentActivity
import com.avoqado.pos.auth.data.model.GoogleConfigResponse
import com.avoqado.pos.auth.data.model.PaseDeGoogle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Entrar con Google en WINDOWS: navegador del sistema + vuelta a 127.0.0.1 con PKCE (hz:6bfcd3a1:D1). */
class VueltaDelNavegadorTest {

    private val config = GoogleConfigResponse(success = true, desktopClientId = "cliente.apps.googleusercontent.com", windows = true)
    private val actividad = FragmentActivity(null)

    // --- Piezas puras ---

    @Test fun `retoPkce con el vector del RFC 7636`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", retoPkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `urlDeGoogle lleva S256, select_account y la dirección codificada`() {
        val url = urlDeGoogle("cliente.apps.googleusercontent.com", "http://127.0.0.1:5123", "estado-1", "reto_2")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), url)
        for (parte in listOf(
            "client_id=cliente.apps.googleusercontent.com",
            "redirect_uri=http%3A%2F%2F127.0.0.1%3A5123",
            "response_type=code",
            "scope=openid%20email%20profile",
            "code_challenge=reto_2",
            "code_challenge_method=S256",
            "state=estado-1",
            "prompt=select_account",
        )) assertContains(url, parte)
    }

    @Test fun `vuelta buena ⇒ Codigo con el code decodificado`() {
        assertEquals(
            Vuelta.Codigo("4/0AbC-d_e"),
            leerVuelta("GET /?state=s1&code=4%2F0AbC-d_e&scope=email%20openid HTTP/1.1", "s1"),
        )
    }

    @Test fun `state ajeno ⇒ Ajena`() {
        assertEquals(Vuelta.Ajena, leerVuelta("GET /?state=otro&code=4%2Fx HTTP/1.1", "s1"))
        assertEquals(Vuelta.Ajena, leerVuelta("GET /?code=4%2Fx HTTP/1.1", "s1"))
    }

    @Test fun `access_denied ⇒ Cancelado`() {
        assertEquals(Vuelta.Cancelado, leerVuelta("GET /?error=access_denied&state=s1 HTTP/1.1", "s1"))
        assertEquals(Vuelta.Ajena, leerVuelta("GET /?error=access_denied&state=otro HTTP/1.1", "s1"))
    }

    @Test fun `favicon, sin parámetros o basura ⇒ Ajena`() {
        for (linea in listOf(
            "GET /favicon.ico HTTP/1.1",
            "GET /favicon.ico?state=s1&code=x HTTP/1.1",
            "GET / HTTP/1.1",
            "POST /?state=s1&code=x HTTP/1.1",
            "",
            "basura",
        )) assertEquals(Vuelta.Ajena, leerVuelta(linea, "s1"), linea)
    }

    // --- De punta a punta, con un socket real en 127.0.0.1 ---

    /** Un «navegador» de prueba: en vez de ir a Google, corre [alAbrir] en su hilo con el puerto y el `state` de la URL. */
    private inner class Navegador(private val alAbrir: (puerto: Int, state: String) -> Unit) {
        @Volatile var url: URI? = null
        @Volatile var puerto = 0
        @Volatile private var hilo: Thread? = null
        val entrar = EntrarConGoogle { uri ->
            url = uri
            val q = parametrosDe(uri.rawQuery)
            puerto = URI(q.getValue("redirect_uri")).port
            hilo = thread(isDaemon = true) { alAbrir(puerto, q.getValue("state")) }
        }

        fun terminar() = hilo?.join(5_000)
    }

    /** Lo que mandaría el navegador: la línea de petición + encabezados; devuelve la respuesta completa. */
    private fun pedir(puerto: Int, ruta: String): String = Socket("127.0.0.1", puerto).use { s ->
        s.soTimeout = 5_000
        s.getOutputStream().write("GET $ruta HTTP/1.1\r\nHost: 127.0.0.1:$puerto\r\nAccept: text/html\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test fun `dos vueltas buenas ⇒ sólo la primera cuenta y el socket queda cerrado`() = runBlocking<Unit> {
        val respuestas = Collections.synchronizedList(mutableListOf<String>())
        val navegador = Navegador { p, state ->
            respuestas += pedir(p, "/?state=$state&code=4%2Fprimero")
            respuestas += runCatching { pedir(p, "/?state=$state&code=4%2Fsegundo") }.getOrElse { "cerrado" }
        }
        val pase = withTimeout(10_000) { navegador.entrar.pedirPase(actividad, config) } as PaseDeGoogle.Codigo
        navegador.terminar()

        assertEquals("4/primero", pase.code)
        assertEquals("http://127.0.0.1:${navegador.puerto}", pase.redirectUri)
        assertEquals(64, pase.codeVerifier.length)
        assertTrue(pase.codeVerifier.all { it.isLetterOrDigit() && it.code < 128 || it in "-._~" }, pase.codeVerifier)
        val q = parametrosDe(navegador.url!!.rawQuery)
        assertEquals(retoPkce(pase.codeVerifier), q["code_challenge"])
        assertEquals(32, q.getValue("state").length)
        assertContains(respuestas[0], "HTTP/1.1 200")
        assertContains(respuestas[0], "Content-Type: text/html; charset=utf-8")
        assertContains(respuestas[0], "Listo. Ya puedes volver al POS de Avoqado.")
        assertFailsWith<IOException> { Socket("127.0.0.1", navegador.puerto).close() }
    }

    @Test fun `state ajeno ⇒ 404 y el intento sigue esperando`() = runBlocking<Unit> {
        val respuestas = Collections.synchronizedList(mutableListOf<String>())
        val navegador = Navegador { p, state ->
            respuestas += pedir(p, "/favicon.ico")
            respuestas += pedir(p, "/?state=otro&code=4%2Fajeno")
            respuestas += pedir(p, "/?state=$state&code=4%2Fbueno")
        }
        val pase = withTimeout(10_000) { navegador.entrar.pedirPase(actividad, config) } as PaseDeGoogle.Codigo
        navegador.terminar()

        assertEquals("4/bueno", pase.code)
        assertContains(respuestas[0], "HTTP/1.1 404")
        assertContains(respuestas[1], "HTTP/1.1 404")
        assertContains(respuestas[2], "Listo. Ya puedes volver al POS de Avoqado.")
    }

    @Test fun `access_denied de punta a punta ⇒ Cancelado`() = runBlocking<Unit> {
        val navegador = Navegador { p, state -> pedir(p, "/?error=access_denied&state=$state") }
        val pase = withTimeout(10_000) { navegador.entrar.pedirPase(actividad, config) }
        navegador.terminar()
        assertEquals(PaseDeGoogle.Cancelado, pase)
    }

    @Test fun `una conexión que no manda nada no frena a la buena`() = runBlocking<Unit> {
        val muda = CountDownLatch(1)
        val navegador = Navegador { p, state ->
            val colgada = Socket("127.0.0.1", p)   // como el «preconnect» del navegador: abre y no manda nada
            Thread.sleep(200)
            pedir(p, "/?state=$state&code=4%2Frapido")
            muda.await(5, TimeUnit.SECONDS)
            colgada.close()
        }
        val inicio = System.nanoTime()
        // Muy por debajo de los 5 s de espera por conexión: la muda no hace fila delante de la buena.
        val pase = withTimeout(3_000) { navegador.entrar.pedirPase(actividad, config) } as PaseDeGoogle.Codigo
        val ms = (System.nanoTime() - inicio) / 1_000_000
        muda.countDown()
        navegador.terminar()
        assertEquals("4/rapido", pase.code)
        assertTrue(ms < 3_000, "tardó $ms ms")
    }

    @Test fun `cancelar con una conexión colgada ⇒ Cancelado y todo cerrado`() = runBlocking<Unit> {
        val conectada = CountDownLatch(1)
        val leyo = AtomicInteger(-2)
        val navegador = Navegador { p, _ ->
            Socket("127.0.0.1", p).use { colgada ->
                colgada.soTimeout = 5_000
                conectada.countDown()
                leyo.set(runCatching { colgada.getInputStream().read() }.getOrDefault(-1))   // -1 o error: la cerró el POS
            }
        }
        thread(isDaemon = true) {
            conectada.await(5, TimeUnit.SECONDS)
            Thread.sleep(200)   // que el POS ya la haya aceptado
            navegador.entrar.cancelar()
        }
        val inicio = System.nanoTime()
        val pase = withTimeout(3_000) { navegador.entrar.pedirPase(actividad, config) }
        val ms = (System.nanoTime() - inicio) / 1_000_000
        navegador.terminar()
        assertEquals(PaseDeGoogle.Cancelado, pase)
        assertTrue(ms < 3_000, "tardó $ms ms")
        assertEquals(-1, leyo.get())
        assertFailsWith<IOException> { Socket("127.0.0.1", navegador.puerto).close() }
    }

    @Test fun `cortar la corrutina con una conexión colgada ⇒ se cancela sin convertirse en Fallo y todo cerrado`() = runBlocking<Unit> {
        val conectada = CountDownLatch(1)
        val navegador = Navegador { p, _ ->
            Socket("127.0.0.1", p).use { colgada ->
                colgada.soTimeout = 5_000
                conectada.countDown()
                runCatching { colgada.getInputStream().read() }
            }
        }
        val resultado = AtomicReference<PaseDeGoogle?>()
        val trabajo = launch(Dispatchers.Default) { resultado.set(navegador.entrar.pedirPase(actividad, config)) }
        withContext(Dispatchers.IO) { conectada.await(5, TimeUnit.SECONDS) }
        delay(200)   // que el POS ya la haya aceptado
        withTimeout(3_000) { trabajo.cancelAndJoin() }
        navegador.terminar()
        assertEquals(null, resultado.get())
        assertFailsWith<IOException> { Socket("127.0.0.1", navegador.puerto).close() }
    }

    @Test fun `sin navegador ⇒ Fallo con su texto y el socket cerrado`() = runBlocking<Unit> {
        val puerto = AtomicInteger()
        val entrar = EntrarConGoogle { uri ->
            puerto.set(URI(parametrosDe(uri.rawQuery).getValue("redirect_uri")).port)
            throw UnsupportedOperationException("sin navegador")
        }
        val pase = entrar.pedirPase(actividad, config) as PaseDeGoogle.Fallo
        assertEquals("No pudimos abrir tu navegador. Entra con tu correo y contraseña.", pase.texto)
        assertFailsWith<IOException> { Socket("127.0.0.1", puerto.get()).close() }
    }

    @Test fun `el servidor sin Google para WINDOWS ⇒ Fallo sin abrir nada`() = runBlocking<Unit> {
        var abrio = false
        val entrar = EntrarConGoogle { abrio = true }
        for (sinGoogle in listOf(config.copy(windows = false), config.copy(desktopClientId = null), config.copy(desktopClientId = " "))) {
            val pase = entrar.pedirPase(actividad, sinGoogle) as PaseDeGoogle.Fallo
            assertEquals("Entrar con Google no está disponible por ahora. Entra con tu correo y contraseña.", pase.texto)
        }
        assertEquals(false, abrio)
    }
}
