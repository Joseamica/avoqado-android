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

/** A dónde van los `Log.x` de la app: consola + `<carpeta>/logs/avoqado-AAAA-MM-DD.log`. */
object Bitacora {
    @Volatile private var carpeta: Path? = null
    private val hora = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun iniciar(carpetaDeDatos: Path) { carpeta = Files.createDirectories(carpetaDeDatos.resolve("logs")) }

    @Synchronized
    fun escribir(nivel: String, tag: String?, msg: String?, error: Throwable?): Int {
        val linea = buildString {
            append(LocalTime.now().format(hora)).append(' ').append(nivel).append('/').append(tag).append(": ").append(msg)
            if (error != null) append('\n').append(pila(error))
            append('\n')
        }
        print(linea)
        // ponytail: un archivo por día sin tope de tamaño; rotar por tamaño cuando un local lo llene.
        carpeta?.let { runCatching { Files.writeString(it.resolve("avoqado-${LocalDate.now()}.log"), linea, CREATE, APPEND) } }
        return linea.length
    }

    fun pila(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
}
