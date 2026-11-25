package com.avoqado.pos.escritorio.dibujo

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.json.Json

/**
 * `<carpeta de datos>/dibujo.json`, la única autoridad del modo de dibujo (hz:a5fa2439:D2). Se lee antes de la ventana, sin
 * red ni sesión: ante cualquier duda, automático. Nada de aquí lanza.
 */
object ArchivoDeDibujo {
    private const val NOMBRE = "dibujo.json"
    // encodeDefaults: el archivo dice todo, no depende de los valores por defecto de esta versión de la app.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Ausente, dañado o incoherente ⇒ `Ajuste()` (automático). */
    fun leer(carpeta: Path): Ajuste = try {
        val a = json.decodeFromString(Ajuste.serializer(), Files.readString(carpeta.resolve(NOMBRE)))
        if (a.intentando && a.modo == Modo.AUTO) Ajuste() else a
    } catch (e: Exception) {
        Ajuste()
    }

    /** Se escribe entero o no se escribe (temporal + ATOMIC_MOVE, como `BovedaEnArchivo`). `false` = no se pudo. */
    fun guardar(carpeta: Path, a: Ajuste): Boolean {
        var temporal: Path? = null
        return try {
            Files.createDirectories(carpeta)
            temporal = Files.createTempFile(carpeta, ".dibujo", ".tmp")
            Files.writeString(temporal, json.encodeToString(Ajuste.serializer(), a))
            Files.move(temporal, carpeta.resolve(NOMBRE), ATOMIC_MOVE, REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            false
        } finally {
            temporal?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
