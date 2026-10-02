package com.avoqado.escritorio

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A dónde van los `Log.x` de la app: consola + `<carpeta>/logs/avoqado-AAAA-MM-DD.log`.
 *
 * 🔴 Todo renglón pasa por [sanear] antes de salir, en prueba y en producción: la app registra `e.message` de errores
 * de deserialización (`TokenRefreshAuthenticator`), y kotlinx.serialization mete ahí el cuerpo («JSON input: …») con
 * los tokens. Se tapa el secreto y se conserva el motivo del error.
 */
object Bitacora {
    @Volatile private var carpeta: Path? = null
    /** Producción: el `Log.d` (detalle de depuración) no se escribe ni en consola ni en archivo; I, W y E sí. */
    @Volatile var soloInformativo: Boolean = false
    private val hora = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun iniciar(carpetaDeDatos: Path) { carpeta = Files.createDirectories(carpetaDeDatos.resolve("logs")) }

    @Synchronized
    fun escribir(nivel: String, tag: String?, msg: String?, error: Throwable?): Int {
        if (soloInformativo && nivel == "D") return 0
        val linea = sanear(
            buildString {
                append(LocalTime.now().format(hora)).append(' ').append(nivel).append('/').append(tag).append(": ").append(msg)
                if (error != null) append('\n').append(pila(error))   // mensaje, causas y suprimidas: también se sanean
                append('\n')
            },
        )
        print(linea)
        // ponytail: un archivo por día sin tope de tamaño; rotar por tamaño cuando un local lo llene.
        carpeta?.let { runCatching { Files.writeString(it.resolve("avoqado-${LocalDate.now()}.log"), linea, CREATE, APPEND) } }
        return linea.length
    }

    fun pila(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

    /**
     * Tapa lo que pueda ser una credencial; lo demás sale idéntico. En este orden:
     * - lo que sigue a `JSON input:` hasta el fin de esa línea (el cuerpo que kotlinx copia en su mensaje);
     * - `Authorization: Bearer …` hasta el fin de la línea;
     * - el valor de `"accessToken" | "refreshToken" | "token" | "password" | "pin"` en un JSON (sin distinguir
     *   mayúsculas; también escapado `\"…\"` o sin comilla de cierre, como lo deja un cuerpo recortado);
     * - cualquier JWT (`eyJ….….…`).
     */
    internal fun sanear(texto: String): String = texto
        .replace(JSON_INPUT, "JSON input: <omitido>")
        .replace(AUTORIZACION, "Authorization: <omitido>")
        .replace(LLAVE_SECRETA, "\$1\"<omitido>\"")
        .replace(JWT, "<jwt>")

    private val JSON_INPUT = Regex("""JSON input:[^\r\n]*""")
    private val AUTORIZACION = Regex("""Authorization:\s*Bearer[^\r\n]*""", RegexOption.IGNORE_CASE)
    private val LLAVE_SECRETA = Regex(
        """(\\?"(?:accessToken|refreshToken|token|password|pin)\\?"\s*:\s*)(?:\\?"(?:[^"\\\r\n]|\\.)*"?|-?\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val JWT = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")
}
