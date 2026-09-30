package com.avoqado.escritorio

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet

/**
 * SharedPreferences en un JSON por nombre. Cada commit/apply escribe a disco de inmediato y de forma atómica
 * (archivo temporal + force + rename): un POS prefiere durabilidad a la escritura diferida de Android.
 * Formato: {"llave": {"t": "s|b|i|l|f|set", "v": valor}} para no perder el tipo.
 *
 * 🔴 Aquí viven el token y los cobros con tarjeta pendientes: un fallo de disco NUNCA lanza — `commit()` devuelve
 * false (quien llama decide con ese booleano) y queda en la bitácora. Un archivo que no se puede LEER lanza; uno
 * cuyo contenido no se entiende se aparta (nunca se tira) antes de arrancar vacío.
 */
class PreferenciasEnArchivo(private val archivo: Path) : SharedPreferences {
    private val candado = Any()
    private val valores: MutableMap<String, Any> = leer()
    private val oyentes = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = synchronized(candado) { HashMap(valores) }
    override fun getString(key: String?, defValue: String?): String? = obtener(key) ?: defValue
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = obtener<Set<String>>(key)?.toSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = obtener(key) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = obtener(key) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = obtener(key) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = obtener(key) ?: defValue
    override fun contains(key: String?): Boolean = synchronized(candado) { key in valores }
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { oyentes += l }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { oyentes -= l }

    @Suppress("UNCHECKED_CAST")
    private fun <T> obtener(key: String?): T? = synchronized(candado) { valores[key] as? T }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val cambios = LinkedHashMap<String, Any?>()   // null = borrar
        private var limpiar = false
        override fun putString(key: String, value: String?) = also { cambios[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = also { cambios[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = also { cambios[key] = value }
        override fun putLong(key: String, value: Long) = also { cambios[key] = value }
        override fun putFloat(key: String, value: Float) = also { cambios[key] = value }
        override fun putBoolean(key: String, value: Boolean) = also { cambios[key] = value }
        override fun remove(key: String) = also { cambios[key] = null }
        override fun clear() = also { limpiar = true }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            val llaves: List<String>
            val escrito: Boolean
            synchronized(candado) {
                if (limpiar) valores.clear()                   // como Android: clear() va antes que los put del mismo editor
                cambios.forEach { (k, v) -> if (v == null) valores.remove(k) else valores[k] = v }
                llaves = cambios.keys.toList()
                cambios.clear()                                // como Android: un Editor reusado no repite lo ya aplicado
                limpiar = false
                escrito = escribir()
            }
            llaves.forEach { k -> oyentes.forEach { it.onSharedPreferenceChanged(this@PreferenciasEnArchivo, k) } }
            return escrito
        }
    }

    /**
     * Un archivo que no se puede LEER (antivirus, respaldo) no es un archivo corrupto: se reintenta y, si sigue, se
     * lanza. Sólo un contenido que no se entiende se aparta como `.corrupto-…json`, y sólo si quedó a salvo.
     */
    private fun leer(): MutableMap<String, Any> {
        // notExists y no !exists: «no se pudo saber» (antivirus) NO es «no existe»; eso cae en la lectura, que reintenta y lanza.
        if (Files.notExists(archivo)) return HashMap()
        val bytes = reintentando({ it is IOException }) { Files.readAllBytes(archivo) }
        return try {
            interpretar(bytes)
        } catch (e: Exception) {
            if (e !is JSONException && e !is CharacterCodingException && e !is ClassCastException) throw e
            apartar(e)
            HashMap()
        }
    }

    /** Sólo trabaja en memoria: lo que falle aquí es del contenido, nunca del disco. */
    private fun interpretar(bytes: ByteArray): MutableMap<String, Any> {
        val json = JSONObject(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        return json.keySet().associateWithTo(HashMap<String, Any>()) { k ->
            val e = json.getJSONObject(k)
            when (e.getString("t")) {
                "s" -> e.getString("v")
                "b" -> e.getBoolean("v")
                "i" -> e.getInt("v")
                "l" -> e.getLong("v")
                "f" -> e.getDouble("v").toFloat()
                else -> e.getJSONArray("v").map { it as String }.toSet()
            }
        }
    }

    /** Aparta el contenido ilegible para no perderlo. Si no queda a salvo (ni movido ni copiado), lanza: no se arranca encima. */
    private fun apartar(causa: Exception) {
        val copia = archivo.resolveSibling(
            "${archivo.fileName.toString().removeSuffix(".json")}.corrupto-${LocalDateTime.now().format(SELLO)}.json",
        )
        try {
            Files.move(archivo, copia)
            Log.e(TAG, "Preferencias ilegibles: se apartaron en $copia y se arranca vacío", causa)
        } catch (alMover: IOException) {
            try {
                Files.copy(archivo, copia)
            } catch (alCopiar: IOException) {
                throw IOException("Preferencias ilegibles en $archivo y no se pudieron apartar: no se arranca encima", alCopiar)
                    .apply { addSuppressed(alMover); addSuppressed(causa) }
            }
            Log.e(TAG, "Preferencias ilegibles: se copiaron a $copia (no se pudieron mover: ${alMover.message}) y se arranca vacío", causa)
        }
    }

    /** true si quedó en disco. Nunca lanza: un fallo va a la bitácora y no deja el temporal tirado. */
    private fun escribir(): Boolean {
        var temporal: Path? = null
        return try {
            val json = JSONObject()
            valores.forEach { (k, v) ->
                val (t, valor) = when (v) {
                    is String -> "s" to v
                    is Boolean -> "b" to v
                    is Int -> "i" to v
                    is Long -> "l" to v
                    is Float -> "f" to v.toDouble()
                    is Set<*> -> "set" to JSONArray(v.toList())
                    else -> error("tipo no soportado en preferencias: ${v::class}")
                }
                json.put(k, JSONObject().put("t", t).put("v", valor))
            }
            Files.createDirectories(archivo.parent)
            val tmp = Files.createTempFile(archivo.parent, ".prefs", ".tmp").also { temporal = it }
            Files.writeString(tmp, json.toString())
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }   // en el disco antes del rename
            mover(tmp)
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo guardar $archivo: ${e.message}", e)
            temporal?.let { runCatching { Files.deleteIfExists(it) } }
            false
        }
    }

    /** En Windows un antivirus o el indexador sostienen el archivo un instante: hasta 3 reintentos de 50 ms. */
    private fun mover(temporal: Path) {
        reintentando({ it is AccessDeniedException }) { Files.move(temporal, archivo, ATOMIC_MOVE, REPLACE_EXISTING) }
    }

    /** Hasta 3 reintentos de 50 ms ante lo que [reintentable] acepte; después relanza. Una interrupción no se traga. */
    private fun <R> reintentando(reintentable: (Exception) -> Boolean, accion: () -> R): R {
        var reintentos = 0
        while (true) {
            try {
                return accion()
            } catch (e: Exception) {
                if (!reintentable(e) || ++reintentos > 3) throw e
                try {
                    Thread.sleep(50)
                } catch (interrumpido: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e.apply { addSuppressed(interrumpido) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Preferencias"
        val SELLO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
