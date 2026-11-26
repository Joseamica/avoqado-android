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
    /** El archivo pesa menos de 200 bytes: uno más grande no es nuestro y ni se lee. */
    const val TAMANO_MAXIMO_BYTES = 4_096
    // encodeDefaults: el archivo dice todo, no depende de los valores por defecto de esta versión de la app. Perezoso: sin
    // archivo, abrir en automático no carga el lector de JSON antes de la ventana.
    private val json by lazy { Json { ignoreUnknownKeys = true; encodeDefaults = true } }

    /**
     * Ausente, dañado, de más de [TAMANO_MAXIMO_BYTES] o incoherente ⇒ `Ajuste()` (automático). Incoherente: la marca de intento
     * en automático, o el fallo en rápido (el fallo sólo nace al regresar a automático y elegir lo limpia: sólo pasa a mano).
     */
    fun leer(carpeta: Path): Ajuste = try {
        val ruta = carpeta.resolve(NOMBRE)
        if (!Files.isRegularFile(ruta) || Files.size(ruta) > TAMANO_MAXIMO_BYTES) {   // sin archivo: una comprobación y nada más
            Ajuste()
        } else {
            val a = json.decodeFromString(Ajuste.serializer(), Files.readString(ruta))
            if ((a.intentando && a.modo == Modo.AUTO) || (a.fallo && a.modo == Modo.RAPIDO)) Ajuste() else a
        }
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
