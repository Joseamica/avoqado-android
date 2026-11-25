package com.avoqado.pos.escritorio

import com.avoqado.pos.escritorio.diagnostico.DatosDelEquipo
import com.avoqado.pos.escritorio.diagnostico.leerDatosDelEquipo
import com.avoqado.pos.escritorio.dibujo.Estado
import com.avoqado.pos.escritorio.dibujo.Modo
import com.avoqado.pos.escritorio.diagnostico.veredicto
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** `<carpeta>/logs/diagnostico.txt`: lo que el founder manda en foto desde Windows (Diagnostico.bat lo abre). */
object Diagnostico {
    fun arranqueMs(): Long = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime

    /**
     * [equipo]: lo leído de la máquina (sin motor, arranque, toque ni dibujo); se pasa para no leerla dos veces. Devuelve los
     * datos completos con que se escribió (los usa «Más › Diagnóstico de esta computadora»).
     */
    fun escribir(
        carpeta: Path, motor: String, arranqueMs: Long, toque: String, dibujo: Estado? = null,
        equipo: DatosDelEquipo = leerDatosDelEquipo(carpeta, motor = null, primerCuadroMs = null, toque = null),
    ): DatosDelEquipo {
        val rt = Runtime.getRuntime()
        val lineaDibujo = dibujo?.let { "\n" + lineaDeDibujo(it) } ?: ""
        val datos = equipo.copy(motorDeDibujo = motor, primerCuadroMs = arranqueMs, toqueConElDedo = toque, dibujo = dibujo)
        Files.writeString(
            Files.createDirectories(carpeta.resolve("logs")).resolve("diagnostico.txt"),
            veredicto(datos).comoTexto() + "\n\n" + """
            |Fecha: ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}
            |Avoqado POS (${if (com.avoqado.pos.BuildConfig.PRODUCCION) "producción" else "prueba"} de escritorio) ${com.avoqado.pos.BuildConfig.VERSION_NAME}
            |Primer cuadro dibujado: $arranqueMs ms desde que arrancó Java (después vienen 1.35 s de splash, igual que en Android)
            |Motor de dibujo (resuelto): $motor$lineaDibujo
            |Toque con el dedo: $toque
            |Sistema: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})
            |Java: ${System.getProperty("java.version")} · procesadores: ${rt.availableProcessors()} · memoria máx: ${rt.maxMemory() / 1_048_576} MB
            |Backend: ${com.avoqado.pos.BuildConfig.BASE_URL}
            |""".trimMargin(),
        )
        return datos
    }

    /** Cómo dibuja de verdad (hz:a5fa2439:D2), lo que midió DibujoDeLaCaja al pintar. */
    private fun lineaDeDibujo(e: Estado): String = buildList {
        add("Dibujo: ${e.motor ?: "sin medir"}")
        e.pintarMs?.let { add("pintar al abrir $it ms") }
        add("modo ${if (e.modo == Modo.RAPIDO) "rápido" else "automático"}")
        add("modo ligero ${if (e.modoLigero) "sí" else "no"}")
        if (e.aplicaAlReabrir) add("se aplica al volver a abrir")
        if (e.aMano) add("fijado a mano")
        if (e.fallo) add("el modo rápido falló")
    }.joinToString(" · ")

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
