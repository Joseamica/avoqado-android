package com.avoqado.pos.customerdisplay

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Lo último que el server mandó para UNA terminal. `ficha == null` = fábrica. `shownAt`/`surface` = la última vez que
 * ESTA ficha se dibujó; se borran al guardar otra huella. Todo acuse se arma desde aquí, así que uno nuevo nunca
 * regresa «Mostrado» a «Guardado».
 */
@Serializable
data class DesignManifest(
    val ficha: FichaDto? = null,
    val imageOrigins: List<String> = emptyList(),
    val storedAt: String,
    val unsupportedFingerprint: String? = null,
    val shownAt: String? = null,
    val surface: String? = null,
    val pendingAck: CustomerDisplayAckBody? = null,
)

/** Todo el estado de la pantalla del cliente en el aparato. Llaves: `venueKey` y `manifestKey`. */
@Serializable
data class StoredDesignState(
    val installationId: String,
    val nextSeq: Long = 1,
    val otherInstallationPending: Boolean = false,
    val otherInstallationReportAtMs: Long? = null,
    val lastTerminalByVenue: Map<String, String> = emptyMap(),
    val manifests: Map<String, DesignManifest> = emptyMap(),
)

/**
 * Un solo archivo JSON (`state.json`) escrito de forma atómica: temporal en la misma carpeta → `fd.sync()` →
 * `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` (en Windows `renameTo` sobre un archivo existente falla). Una muerte a
 * medias deja, cuando mucho, un `*.tmp` que la siguiente lectura borra; el estado anterior sigue entero.
 */
@Singleton
class CustomerDisplayDesignStore internal constructor(private val dir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "customer-display"))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state get() = File(dir, "state.json")

    /** Nunca lanza: sin archivo (o ilegible) nace una instalación nueva y se persiste en ese momento. */
    fun load(): StoredDesignState = synchronized(diskLock) {
        try {
            read()
        } catch (_: IOException) {
            // ponytail: una falla PASAJERA al leer no es un archivo corrupto: se sigue con una instalación en memoria SIN
            // tocar el disco, y el archivo bueno (instalación, seq, acuse pendiente) se vuelve a leer la próxima vez.
            StoredDesignState(installationId = UUID.randomUUID().toString())
        }
    }

    /**
     * Lee, aplica `change`, escribe y devuelve lo escrito. Si no se pudo leer o escribir, lanza: nada quedó a medias (y
     * nunca se escribe encima de un archivo que no se pudo leer).
     */
    fun update(change: (StoredDesignState) -> StoredDesignState): StoredDesignState = synchronized(diskLock) {
        val before = read()
        // Sin cambios no se escribe: el poll pasa por aquí cada 15-20 s y casi nunca cambia nada.
        change(before).also { if (it != before) write(it) }
    }

    internal fun venueKey(env: String, venueId: String) = "$env|$venueId"
    internal fun manifestKey(env: String, venueId: String, terminalId: String) = "$env|$venueId|$terminalId"

    private fun read(): StoredDesignState {
        dir.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
        if (state.exists()) {
            val text = state.readText()   // una IOException sube: no se pudo LEER, no está corrupto
            try {
                return json.decodeFromString(StoredDesignState.serializer(), text)
            } catch (_: IllegalArgumentException) {
                // SerializationException es una IllegalArgumentException: el contenido no se entiende.
            }
            // Ilegible: se aparta (para poder investigarlo) y se arranca de cero.
            runCatching {
                Files.move(state.toPath(), File(dir, "state.corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val fresh = StoredDesignState(installationId = UUID.randomUUID().toString())
        // ponytail: si el disco no deja escribir, se sigue con la instalación en memoria (la próxima lectura nace otra).
        runCatching { write(fresh) }
        return fresh
    }

    private fun write(snapshot: StoredDesignState) {
        check(dir.isDirectory || dir.mkdirs()) { "No se pudo guardar la pantalla del cliente en este aparato" }
        val temporary = File.createTempFile("state-", ".tmp", dir)
        try {
            FileOutputStream(temporary).use { it.write(json.encodeToString(snapshot).toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), state.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private companion object {
        // Como TableRoundDraftStore: un candado para cualquier instancia sobre el disco.
        val diskLock = Any()
    }
}
