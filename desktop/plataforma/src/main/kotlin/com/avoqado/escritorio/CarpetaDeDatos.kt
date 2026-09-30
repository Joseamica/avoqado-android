package com.avoqado.escritorio

import java.nio.file.Files
import java.nio.file.Path

/** La carpeta del usuario donde viven preferencias, base de datos, bitácora y candado. Se crea si no existe. */
object CarpetaDeDatos {
    fun resolver(
        propiedad: (String) -> String? = System::getProperty,
        entorno: (String) -> String? = System::getenv,
    ): Path {
        val casa = Path.of(checkNotNull(propiedad("user.home")))
        val so = propiedad("os.name").orEmpty()
        val carpeta = propiedad("avoqado.datos")?.ifBlank { null }?.let { Path.of(it) } ?: when {
            so.startsWith("Windows") ->
                (entorno("APPDATA")?.ifBlank { null }?.let { Path.of(it) } ?: casa.resolve("AppData").resolve("Roaming")).resolve("Avoqado POS")
            so.startsWith("Mac") -> casa.resolve("Library/Application Support/Avoqado POS")
            else -> casa.resolve(".avoqado-pos")
        }
        return Files.createDirectories(carpeta)
    }
}
