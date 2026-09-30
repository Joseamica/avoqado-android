package com.avoqado.pos.escritorio

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** `<carpeta>/logs/diagnostico.txt`: lo que el founder manda en foto desde Windows (Diagnostico.bat lo abre). */
object Diagnostico {
    fun arranqueMs(): Long = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime

    fun escribir(carpeta: Path, motor: String, arranqueMs: Long) {
        val rt = Runtime.getRuntime()
        Files.writeString(
            Files.createDirectories(carpeta.resolve("logs")).resolve("diagnostico.txt"),
            """
            |Fecha: ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}
            |Avoqado POS (prueba de escritorio) ${com.avoqado.pos.BuildConfig.VERSION_NAME}
            |Primer cuadro dibujado: $arranqueMs ms desde que arrancó Java (después vienen 1.35 s de splash, igual que en Android)
            |Motor de dibujo (resuelto): $motor
            |Sistema: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})
            |Java: ${System.getProperty("java.version")} · procesadores: ${rt.availableProcessors()} · memoria máx: ${rt.maxMemory() / 1_048_576} MB
            |Backend: ${com.avoqado.pos.BuildConfig.BASE_URL}
            |""".trimMargin(),
        )
    }
}
