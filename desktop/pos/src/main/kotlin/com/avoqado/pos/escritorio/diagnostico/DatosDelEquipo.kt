package com.avoqado.pos.escritorio.diagnostico

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.User32
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** Lee el equipo (Windows: JNA; fuera: lo que se pueda). Nunca lanza. */
fun leerDatosDelEquipo(carpetaDeDatos: Path, motor: String?, primerCuadroMs: Long?, toque: String?): DatosDelEquipo {
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    val pantalla = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
    }.getOrNull()
    return DatosDelEquipo(
        windows = windows,
        versionSO = System.getProperty("os.version"),
        ramGb = runCatching {
            (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean)
                .totalMemorySize / 1_073_741_824.0
        }.getOrNull(),
        nucleos = runCatching { Runtime.getRuntime().availableProcessors() }.getOrNull(),
        procesador = if (!windows) null else runCatching {
            Advapi32Util.registryGetStringValue(
                WinReg.HKEY_LOCAL_MACHINE, "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "ProcessorNameString",
            ).trim()
        }.getOrNull(),
        discoLibreGb = runCatching {
            Files.getFileStore(Files.createDirectories(carpetaDeDatos)).usableSpace / 1_073_741_824.0
        }.getOrNull(),
        anchoPx = pantalla?.width, altoPx = pantalla?.height,
        puntosTactiles = if (!windows) null else runCatching { User32.INSTANCE.GetSystemMetrics(95) }.getOrNull(),
        motorDeDibujo = motor, primerCuadroMs = primerCuadroMs, toqueConElDedo = toque,
    )
}
