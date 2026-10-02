package com.avoqado.escritorio

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * La carpeta del usuario donde viven preferencias, base de datos, bitácora y candado. Se crea si no existe.
 *
 * En PRODUCCIÓN, Windows usa `%USERPROFILE%\.avoqado-pos`: fuera de AppData, que el MSIX redirige a una carpeta privada
 * que se borra al desinstalar. Producción y prueba NUNCA comparten carpeta (la cola de una prueba no debe llegar a producción):
 * cada una vive en la suya y, por si `-Davoqado.datos` las cruza, la marca [MARCA_DE_MODO] lo impide ([comprobarModo]).
 */
object CarpetaDeDatos {
    fun resolver(
        produccion: Boolean,
        propiedad: (String) -> String? = System::getProperty,
        entorno: (String) -> String? = System::getenv,
    ): Path {
        // Una ruta relativa (USERPROFILE=perfil, "C:") caería en la carpeta actual —la del paquete—: sólo valen las ABSOLUTAS.
        fun absoluta(ruta: String?): Path? = ruta?.ifBlank { null }?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.isAbsolute }
        val casa = absoluta(propiedad("user.home"))
        val so = propiedad("os.name").orEmpty()
        val propia = propiedad("avoqado.datos")?.ifBlank { null }?.let { Path.of(it) }
        val carpeta = propia ?: when {
            so.startsWith("Windows") && produccion ->
                (absoluta(entorno("USERPROFILE")) ?: casaAbsoluta(casa)).resolve(".avoqado-pos")
            so.startsWith("Windows") ->
                (absoluta(entorno("APPDATA")) ?: casaAbsoluta(casa).resolve("AppData").resolve("Roaming")).resolve("Avoqado POS")
            so.startsWith("Mac") -> casaAbsoluta(casa).resolve("Library/Application Support/" + if (produccion) "Avoqado POS Produccion" else "Avoqado POS")
            else -> casaAbsoluta(casa).resolve(if (produccion) ".avoqado-pos-produccion" else ".avoqado-pos")
        }
        val creada = Files.createDirectories(carpeta)
        if (propia == null && produccion && so.startsWith("Windows")) {
            runCatching { Files.setAttribute(creada, "dos:hidden", true) }
        }
        return creada
    }

    /** El archivo que dice de qué build es la carpeta: `produccion` o `prueba`. */
    const val MARCA_DE_MODO = ".modo-de-datos"

    /**
     * 🔴 Producción y prueba NUNCA abren la carpeta del otro, tampoco con `-Davoqado.datos` (un `JAVA_TOOL_OPTIONS`
     * heredado basta para apuntar ahí): se abrirían su sesión y su cola de cobros. Se llama DESPUÉS de tomar el candado
     * (nadie más la está marcando) y ANTES de abrir preferencias o la base. Lanza [CarpetaDeOtroModo] sin tocar nada.
     * - con marca: igual ⇒ sigue; distinta (o que no se entiende) ⇒ no arranca;
     * - sin marca y sin datos (`shared_prefs/` y `databases/` sin nada dentro) ⇒ la escribe con el modo del build;
     * - sin marca y CON datos ⇒ son de un build de prueba anterior a la marca: prueba la escribe y sigue; producción no arranca.
     * Lo que no se puede leer (la marca, el contenido de una carpeta) lanza su IOException: no se adivina el modo.
     */
    fun comprobarModo(carpeta: Path, produccion: Boolean) {
        val propio = if (produccion) PRODUCCION else PRUEBA
        val marca = carpeta.resolve(MARCA_DE_MODO)
        if (!Files.notExists(marca)) {   // existe, o no se puede saber: se lee (y si no se puede, lanza)
            val dice = String(Files.readAllBytes(marca), StandardCharsets.UTF_8).trim()
            if (dice == propio) return
            throw CarpetaDeOtroModo(
                when (dice) {
                    PRODUCCION, PRUEBA -> "Esta carpeta es de la versión de ${nombreDe(dice)} de Avoqado POS; no se abre con ésta."
                    else -> "Esta carpeta trae una marca de modo que no se entiende («${dice.take(40)}»); no se abre con esta versión."
                } + "\nCarpeta: $carpeta",
            )
        }
        if (produccion && tieneDatos(carpeta)) {
            throw CarpetaDeOtroModo("Esta carpeta es de una versión de prueba de Avoqado POS; no se abre con ésta.\nCarpeta: $carpeta")
        }
        escribirAtomico(marca, propio.toByteArray(StandardCharsets.UTF_8))
    }

    private const val PRODUCCION = "produccion"
    private const val PRUEBA = "prueba"

    private fun nombreDe(modo: String) = if (modo == PRODUCCION) "producción" else "prueba"

    /** Hay datos si `shared_prefs/` o `databases/` tienen algo dentro (o existen y no son carpetas: no se adivina). */
    private fun tieneDatos(carpeta: Path): Boolean = listOf("shared_prefs", "databases").any { nombre ->
        val dir = carpeta.resolve(nombre)
        when {
            Files.notExists(dir, LinkOption.NOFOLLOW_LINKS) -> false
            Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) -> Files.list(dir).use { it.findFirst().isPresent }
            else -> true
        }
    }

    private fun casaAbsoluta(casa: Path?): Path =
        checkNotNull(casa) { "No hay una carpeta de usuario absoluta (user.home / USERPROFILE / APPDATA): no se arriesga a guardar datos junto al programa." }
}

/** La carpeta de datos es del OTRO modo (producción ↔ prueba): no se abre. [motivo] ya trae la ruta. */
class CarpetaDeOtroModo(val motivo: String) : RuntimeException(motivo)
