package com.avoqado.pos.auth.data

import androidx.fragment.app.FragmentActivity
import com.avoqado.pos.auth.data.model.GoogleConfigResponse
import com.avoqado.pos.auth.data.model.PaseDeGoogle
import com.avoqado.pos.core.data.lan.LineaAcotada
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

internal const val TEXTO_APARATO = "Entrar con Google no está disponible en este aparato. Entra con tu correo y contraseña."
internal const val TEXTO_SERVIDOR_SIN_GOOGLE = "Entrar con Google no está disponible por ahora. Entra con tu correo y contraseña."
internal const val TEXTO_SIN_NAVEGADOR = "No pudimos abrir tu navegador. Entra con tu correo y contraseña."
internal const val TEXTO_SIN_RESPUESTA = "No recibimos respuesta de Google. Vuelve a intentarlo."
private const val PAGINA_LISTA = "Listo. Ya puedes volver al POS de Avoqado."
private const val ESPERA_DE_GOOGLE_MS = 180_000L
private const val ESPERA_POR_CONEXION_MS = 5_000

/**
 * Reemplazo de escritorio (Credential Manager no existe en Windows), con la misma forma pública que el de Android
 * (hz:6bfcd3a1:D1): abre el navegador del sistema con PKCE y recibe la vuelta de Google en 127.0.0.1 (puerto libre).
 * Devuelve el `code` con su verificador y su dirección; el SERVIDOR lo canjea (aquí no hay secreto).
 */
@Singleton
class EntrarConGoogle internal constructor(private val abrirNavegador: (URI) -> Unit) {
    @Inject constructor() : this(::abrirConElSistema)

    val abreNavegador: Boolean = true

    @Volatile private var enCurso: Intento? = null

    suspend fun pedirPase(activity: FragmentActivity, config: GoogleConfigResponse): PaseDeGoogle {
        val clientId = config.desktopClientId
        if (!config.windows || clientId.isNullOrBlank()) {
            return PaseDeGoogle.Fallo(TEXTO_SERVIDOR_SIN_GOOGLE, "config sin windows/desktopClientId")
        }
        return try {
            withContext(Dispatchers.IO) { pedirPorElNavegador(clientId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            traducirFallo(e)
        }
    }

    /** «Cancelar» y Atrás: cierra el socket y la conexión en curso; el intento devuelve [PaseDeGoogle.Cancelado]. */
    fun cancelar() {
        enCurso?.cancelar()
    }

    private suspend fun pedirPorElNavegador(clientId: String): PaseDeGoogle {
        val verificador = aleatorio(64)
        val state = aleatorio(32)
        // Sólo la interfaz local (nunca 0.0.0.0) y un puerto libre que elige el sistema. Cola de 50, no de 1: el navegador
        // abre varias conexiones a la vez y en Windows una cola llena rechaza la buena.
        val intento = Intento(ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")))
        enCurso = intento
        try {
            val redirectUri = "http://127.0.0.1:${intento.servidor.localPort}"
            try {
                abrirNavegador(URI(urlDeGoogle(clientId, redirectUri, state, retoPkce(verificador))))
            } catch (e: Exception) {
                return PaseDeGoogle.Fallo(TEXTO_SIN_NAVEGADOR, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            }
            return when (val vuelta = withTimeoutOrNull(ESPERA_DE_GOOGLE_MS) { intento.esperar(state) }) {
                null -> PaseDeGoogle.Fallo(TEXTO_SIN_RESPUESTA, "3 min sin vuelta del navegador")
                is Vuelta.Codigo -> PaseDeGoogle.Codigo(vuelta.code, verificador, redirectUri)
                else -> PaseDeGoogle.Cancelado
            }
        } finally {
            intento.cerrar()
            if (enCurso === intento) enCurso = null
        }
    }
}

internal fun traducirFallo(e: Throwable): PaseDeGoogle =
    PaseDeGoogle.Fallo(TEXTO_APARATO, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")

private fun abrirConElSistema(uri: URI) {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        throw UnsupportedOperationException("java.awt.Desktop sin BROWSE")
    }
    Desktop.getDesktop().browse(uri)
}

private const val ALFABETO_PKCE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
private val azar = SecureRandom()

private fun aleatorio(largo: Int): String = String(CharArray(largo) { ALFABETO_PKCE[azar.nextInt(ALFABETO_PKCE.length)] })

/** Un intento: el socket de 127.0.0.1, las conexiones aceptadas y su primer desenlace (Codigo o Cancelado). */
private class Intento(val servidor: ServerSocket) {
    private val desenlace = CompletableDeferred<Vuelta>()
    private val conexiones = mutableSetOf<Socket>()
    private var cerrado = false

    fun cancelar() {
        desenlace.complete(Vuelta.Cancelado)
        cerrar()
    }

    /** Cerrar los sockets es lo que destraba los `accept()` y `read()` bloqueados. */
    @Synchronized fun cerrar() {
        cerrado = true
        runCatching { servidor.close() }
        conexiones.forEach { runCatching { it.close() } }
        conexiones.clear()
    }

    @Synchronized private fun registrar(conexion: Socket): Boolean {
        if (cerrado) runCatching { conexion.close() }
        return !cerrado && conexiones.add(conexion)
    }

    @Synchronized private fun soltar(conexion: Socket) {
        conexiones.remove(conexion)
    }

    /** Cada conexión en su hilo: una que no manda nada (el «preconnect» del navegador) no hace fila delante de la buena. */
    suspend fun esperar(state: String): Vuelta = coroutineScope {
        launch(Dispatchers.IO) {
            try {
                while (true) {
                    val conexion = servidor.accept()
                    if (registrar(conexion)) launch(Dispatchers.IO) { atender(conexion, state) }
                }
            } catch (e: IOException) {
                desenlace.completeExceptionally(e)   // si la cerramos nosotros, ya hubo desenlace y esto no hace nada
            }
        }
        try {
            desenlace.await()
        } finally {
            cerrar()
        }
    }

    private fun atender(conexion: Socket, state: String) {
        try {
            conexion.soTimeout = ESPERA_POR_CONEXION_MS
            val entrada = BufferedInputStream(conexion.getInputStream())
            val vuelta = try {
                LineaAcotada.leer(entrada)?.let { leerVuelta(it, state) } ?: return
            } catch (_: IOException) {
                return   // muda 5 s, cortada o cerrada por nosotros: no decide nada
            }
            try {
                // Los encabezados se leen antes de contestar: cerrar con bytes sin leer manda un RST y el navegador
                // enseñaría «conexión restablecida» en vez de la página.
                var renglones = 0
                while (renglones++ < 100 && !LineaAcotada.leer(entrada).isNullOrEmpty()) Unit
                if (vuelta == Vuelta.Ajena) responder(conexion, "404 Not Found", "No encontrado.")
                else responder(conexion, "200 OK", PAGINA_LISTA)
            } catch (_: IOException) {
                // el navegador se fue antes de la página: el code ya llegó y cuenta igual
            }
            if (vuelta != Vuelta.Ajena) desenlace.complete(vuelta)   // la primera gana; las demás no cambian nada
        } finally {
            soltar(conexion)
            runCatching { conexion.close() }
        }
    }

    private fun responder(conexion: Socket, estado: String, texto: String) {
        val cuerpo = "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><title>Avoqado</title></head>" +
            "<body><p>$texto</p></body></html>"
        val bytes = cuerpo.toByteArray(Charsets.UTF_8)
        val cabeza = "HTTP/1.1 $estado\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        conexion.getOutputStream().apply {
            write(cabeza.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }
}
