package com.avoqado.pos.auth.data

import androidx.fragment.app.FragmentActivity
import com.avoqado.pos.auth.data.model.GoogleConfigResponse
import com.avoqado.pos.auth.data.model.PaseDeGoogle
import com.avoqado.pos.core.data.lan.LineaAcotada
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

internal const val TEXTO_APARATO = "Entrar con Google no está disponible en este aparato. Entra con tu correo y contraseña."
internal const val TEXTO_SERVIDOR_SIN_GOOGLE = "Entrar con Google no está disponible por ahora. Entra con tu correo y contraseña."
internal const val TEXTO_SIN_EDGE = "No pudimos abrir Microsoft Edge. Entra con tu correo y contraseña."
internal const val TEXTO_SIN_RESPUESTA = "No recibimos respuesta de Google. Vuelve a intentarlo."
private const val PAGINA_LISTA = "Listo. Ya puedes volver al POS de Avoqado."
private const val ESPERA_DE_GOOGLE_MS = 180_000L
private const val ESPERA_POR_CONEXION_MS = 5_000
/** Conexiones que se leen a la vez; las demás esperan su turno sin ocupar un hilo (Codex r2 #6). */
private const val CONEXIONES_A_LA_VEZ = 4

private const val PREFIJO_PERFIL = "avoqado-google-"

/**
 * Reemplazo de escritorio (la Auth Tab de Chrome no existe en Windows), con la misma forma pública que el de Android
 * (hz:6bfcd3a1:D1): abre Microsoft Edge con un perfil desechable (nada de la sesión de Google queda en la PC) con PKCE y recibe la vuelta de Google en 127.0.0.1 (puerto libre).
 * Devuelve el `code` con su verificador y su dirección; el SERVIDOR lo canjea (aquí no hay secreto).
 */
@Singleton
class EntrarConGoogle internal constructor(
    private val plazoMs: Long = ESPERA_DE_GOOGLE_MS,
    /** Abre Edge con la carpeta de perfil del intento y devuelve su proceso para cerrarlo al terminar (null en pruebas). */
    private val abrirNavegador: (URI, File) -> Process?,
) {
    @Inject constructor() : this(ESPERA_DE_GOOGLE_MS, ::abrirEnEdge)

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
        val verificador = nuevoVerificador()
        val state = nuevoState()
        // Sólo la interfaz local (nunca 0.0.0.0) y un puerto libre que elige el sistema. Cola de 50, no de 1: el navegador
        // abre varias conexiones a la vez y en Windows una cola llena rechaza la buena.
        barrerRestos()
        val perfil = Files.createTempDirectory(PREFIJO_PERFIL).toFile()
        var proceso: Process? = null
        val intento = try {
            Intento(ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")))
        } catch (e: Exception) {
            borrarPerfil(perfil)
            throw e
        }
        enCurso = intento
        try {
            val redirectUri = "http://127.0.0.1:${intento.servidor.localPort}"
            // El plazo cuenta desde ANTES de abrir el navegador: abrirlo también gasta de los 3 min (Codex r2 #6).
            val pase = withTimeoutOrNull(plazoMs) {
                try {
                    proceso = abrirNavegador(URI(urlDeGoogle(clientId, redirectUri, state, retoPkce(verificador))), perfil)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val causa = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
                    return@withTimeoutOrNull PaseDeGoogle.Fallo(TEXTO_SIN_EDGE, causa)
                }
                when (val vuelta = intento.esperar(state)) {
                    is Vuelta.Codigo -> PaseDeGoogle.Codigo(vuelta.code, verificador, redirectUri)
                    else -> PaseDeGoogle.Cancelado
                }
            }
            return pase ?: PaseDeGoogle.Fallo(TEXTO_SIN_RESPUESTA, "3 min sin vuelta del navegador")
        } finally {
            intento.cerrar()
            if (enCurso === intento) enCurso = null
            limpiar(proceso, perfil)   // por cualquier camino: código, cancelar, plazo o error
        }
    }
}

internal fun traducirFallo(e: Throwable): PaseDeGoogle =
    PaseDeGoogle.Fallo(TEXTO_APARATO, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")

/** Edge en las rutas de siempre: Archivos de programa (x86), Archivos de programa y AppData local. */
internal fun buscarEdge(env: (String) -> String?, existe: (File) -> Boolean): File? =
    listOf("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")
        .mapNotNull { env(it) }
        .map { File(it, "Microsoft\\Edge\\Application\\msedge.exe") }
        .firstOrNull(existe)

/** Sin `cmd` y sin `--inprivate`: con otro `--user-data-dir` Edge arranca un proceso propio que el POS controla. */
internal fun ordenDeEdge(edge: File, perfil: File, url: String): List<String> =
    listOf(edge.path, "--user-data-dir=${perfil.path}", "--no-first-run", "--no-default-browser-check", url)

private fun abrirEnEdge(uri: URI, perfil: File): Process {
    val edge = buscarEdge({ System.getenv(it) }) { it.isFile } ?: throw FileNotFoundException("msedge.exe")
    return ProcessBuilder(ordenDeEdge(edge, perfil, uri.toString()))
        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
}

/** Mata Edge (y lo que lanzó) y borra su carpeta. Nunca lanza. */
internal fun limpiar(proceso: Process?, perfil: File, borrar: (File) -> Boolean = { it.deleteRecursively() }, esperaMs: Long = 500) {
    if (proceso != null) {
        runCatching { proceso.toHandle().descendants().forEach { it.destroyForcibly() } }
        runCatching { proceso.destroyForcibly() }
    }
    borrarPerfil(perfil, borrar, esperaMs)
}

/** Hasta 3 intentos con [esperaMs] entre ellos (Windows suelta los archivos un instante después de morir el proceso). */
internal fun borrarPerfil(perfil: File, borrar: (File) -> Boolean = { it.deleteRecursively() }, esperaMs: Long = 500): Boolean {
    repeat(3) { n ->
        if (runCatching { borrar(perfil) }.getOrDefault(false) || !perfil.exists()) return true
        if (n < 2) runCatching { Thread.sleep(esperaMs) }
    }
    System.err.println("No se pudo borrar ${perfil.path}; se borra en el siguiente intento")
    return false
}

/** Lo que un intento anterior no pudo borrar: el prefijo lo identifica. */
private fun barrerRestos() {
    val tmp = File(System.getProperty("java.io.tmpdir"))
    tmp.listFiles { f -> f.isDirectory && f.name.startsWith(PREFIJO_PERFIL) }?.forEach { runCatching { it.deleteRecursively() } }
}

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

    /**
     * Hasta [CONEXIONES_A_LA_VEZ] conexiones se leen a la vez, cada una con [ESPERA_POR_CONEXION_MS] en TOTAL: una que no
     * manda nada (el «preconnect» del navegador) o que gotea bytes no hace fila para siempre delante de la buena, y un
     * proceso local con muchas abiertas no se come los hilos compartidos del POS. Todas viven dentro de este scope y se
     * cancelan al salir por cualquier camino.
     */
    suspend fun esperar(state: String): Vuelta = coroutineScope {
        val turnos = Semaphore(CONEXIONES_A_LA_VEZ)
        val escucha = launch(Dispatchers.IO) {
            try {
                while (true) {
                    val conexion = servidor.accept()
                    if (registrar(conexion)) {
                        launch(Dispatchers.IO) {
                            turnos.withPermit {
                                val plazo = launch { delay(ESPERA_POR_CONEXION_MS.toLong()); runCatching { conexion.close() } }
                                try {
                                    atender(conexion, state)
                                } finally {
                                    plazo.cancel()
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                desenlace.completeExceptionally(e)   // si la cerramos nosotros, ya hubo desenlace y esto no hace nada
            }
        }
        try {
            desenlace.await()
        } finally {
            cerrar()
            escucha.cancel()
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
        } catch (_: IOException) {
            // cerrada antes de leerla (su plazo, el desenlace o «Cancelar»): no decide nada
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
