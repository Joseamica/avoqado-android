package com.avoqado.pos.escritorio

import com.avoqado.pos.escritorio.diagnostico.leerDatosDelEquipo
import com.avoqado.pos.escritorio.diagnostico.veredicto
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** `<carpeta>/logs/diagnostico.txt`: lo que el founder manda en foto desde Windows (Diagnostico.bat lo abre). */
object Diagnostico {
    fun arranqueMs(): Long = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime

    fun escribir(carpeta: Path, motor: String, arranqueMs: Long, toque: String) {
        val rt = Runtime.getRuntime()
        Files.writeString(
            Files.createDirectories(carpeta.resolve("logs")).resolve("diagnostico.txt"),
            veredicto(leerDatosDelEquipo(carpeta, motor, arranqueMs, toque)).comoTexto() + "\n\n" + """
            |Fecha: ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}
            |Avoqado POS (${if (com.avoqado.pos.BuildConfig.PRODUCCION) "producción" else "prueba"} de escritorio) ${com.avoqado.pos.BuildConfig.VERSION_NAME}
            |Primer cuadro dibujado: $arranqueMs ms desde que arrancó Java (después vienen 1.35 s de splash, igual que en Android)
            |Motor de dibujo (resuelto): $motor
            |Toque con el dedo: $toque
            |Sistema: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})
            |Java: ${System.getProperty("java.version")} · procesadores: ${rt.availableProcessors()} · memoria máx: ${rt.maxMemory() / 1_048_576} MB
            |Backend: ${com.avoqado.pos.BuildConfig.BASE_URL}
            |""".trimMargin(),
        )
    }

    /** Una línea más al final (p. ej., el puente táctil se apagó solo después de arrancar). */
    fun anotar(carpeta: Path, linea: String) {
        Files.writeString(
            Files.createDirectories(carpeta.resolve("logs")).resolve("diagnostico.txt"),
            linea + System.lineSeparator(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }
}
