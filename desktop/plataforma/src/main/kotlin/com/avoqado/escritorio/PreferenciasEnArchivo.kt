package com.avoqado.escritorio

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
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
 * false, la memoria se queda como estaba (lo que se relea es lo que hay en disco) y queda en la bitácora. Un archivo que no se puede LEER lanza; uno
 * cuyo contenido no se entiende se aparta (nunca se tira) antes de arrancar vacío.
 *
 * Con [codec] (preferencias cifradas, ver [preferenciasCifradas]) el MISMO JSON pasa por el códec al guardar y al leer,
 * y la lectura es ESTRICTA: el archivo tiene que existir y cualquier falla lanza [PreferenciasIlegibles]; nunca se
 * aparta nada ni se arranca vacío.
 */
class PreferenciasEnArchivo internal constructor(
    private val archivo: Path,
    private val codec: CodecDePreferencias?,
    /** Las pruebas lo cambian por uno que falla N veces; la app usa [escribirAtomico]. */
    private val escribirBytes: (Path, ByteArray) -> Unit,
    /** Las pruebas registran las esperas sin dormir; la app duerme. */
    private val esperar: (Long) -> Unit,
) : SharedPreferences {
    constructor(archivo: Path, codec: CodecDePreferencias? = null) : this(archivo, codec, ::escribirAtomico, { Thread.sleep(it) })

    private val candado = Any()
    private val valores: MutableMap<String, Any> = if (codec == null) leer() else leerEstricto(archivo, codec)
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

        /**
         * Todo o nada: si el disco no lo guarda, la memoria vuelve a lo que había y nadie se entera de un cambio que no
         * existe. 🔴 La app ignora el booleano (`SecureStorage`) y comprueba RELEYENDO (`CashDrawerRepository.encolar`):
         * con la memoria cambiada, la relectura diría «guardado» y una operación del cajón se perdería al morir el proceso.
         * Antes de rendirse reintenta ([guardar]); si aun así no queda, se REPORTA a [FallasDeGuardado] y la ventana se lo
         * dice al cajero (ingresos, retiros y cierres de la app tampoco miran el booleano).
         */
        override fun commit(): Boolean {
            val llaves: List<String>
            synchronized(candado) {
                val anterior = HashMap(valores)
                if (limpiar) valores.clear()                   // como Android: clear() va antes que los put del mismo editor
                cambios.forEach { (k, v) -> if (v == null) valores.remove(k) else valores[k] = v }
                llaves = cambios.keys.toList()
                cambios.clear()                                // como Android: un Editor reusado no repite lo ya aplicado
                limpiar = false
                if (!guardar()) {
                    valores.clear()
                    valores.putAll(anterior)
                    FallasDeGuardado.reportar(nombreLogico)
                    return false
                }
            }
            FallasDeGuardado.guardoBien(nombreLogico)
            llaves.forEach { k -> oyentes.forEach { it.onSharedPreferenceChanged(this@PreferenciasEnArchivo, k) } }
            return true
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

    /**
     * Aparta el contenido ilegible para no perderlo. Si no queda a salvo (ni movido ni copiado), lanza: no se arranca encima.
     * 🔴 De [causa] sólo viaja su clase: el mensaje de org.json trae el VALOR (el token) y la bitácora imprime la pila.
     */
    private fun apartar(causa: Exception) {
        val copia = archivo.resolveSibling(
            "${archivo.fileName.toString().removeSuffix(".json")}.corrupto-${LocalDateTime.now().format(SELLO)}.json",
        )
        val porque = causa.javaClass.simpleName
        try {
            Files.move(archivo, copia)
            Log.e(TAG, "Preferencias ilegibles ($porque): se apartaron en $copia y se arranca vacío")
        } catch (alMover: IOException) {
            try {
                Files.copy(archivo, copia)
            } catch (alCopiar: IOException) {
                throw IOException("Preferencias ilegibles en $archivo y no se pudieron apartar: no se arranca encima", alCopiar)
                    .apply { addSuppressed(alMover) }
            }
            Log.e(TAG, "Preferencias ilegibles ($porque): se copiaron a $copia (no se pudieron mover: ${alMover.message}) y se arranca vacío")
        }
    }

    /** «avoqado_secure_prefs» para el `.json` y para el `.cifrado`: lo que se le dice al cajero (nunca la ruta). */
    private val nombreLogico: String = archivo.fileName.toString().substringBeforeLast('.')

    /**
     * true si quedó en disco. Nunca lanza: un fallo va a la bitácora y no deja el temporal tirado.
     * - Serializar y cifrar se hacen UNA vez: si fallan (un texto que no se puede escribir en UTF-8, DPAPI) esperar no lo
     *   arregla, no se reintenta.
     * - Escribir sí se reintenta: un antivirus o el indexador de Windows sostienen el archivo un rato. Esperas de
     *   [ESPERAS] (1.55 s en total; con los reintentos cortos de [escribirAtomico], ~2.5 s en el peor caso). Mientras, el
     *   candado sigue tomado: nadie lee un valor que todavía no se sabe si quedará.
     * 🔴 A la bitácora va el archivo y la CLASE de la causa, nunca su mensaje ni su pila: un error al serializar o al
     * cifrar podría traer valores (el token). De un FileSystemException sí va el mensaje: son rutas y el motivo del sistema.
     */
    private fun guardar(): Boolean {
        val bytes = try {
            (codec ?: SinCifrar).cifrar(serializar(valores))
        } catch (e: Exception) {
            return fallo(e, intentos = 1)
        }
        var ultimo = intentarEscribir(bytes) ?: return true
        for ((i, ms) in ESPERAS.withIndex()) {
            try {
                esperar(ms)
            } catch (interrumpido: InterruptedException) {
                Thread.currentThread().interrupt()   // quien nos interrumpió se entera; no se sigue esperando
                return fallo(ultimo, intentos = i + 1)
            }
            ultimo = intentarEscribir(bytes) ?: run {
                Log.w(TAG, "Se guardó $archivo al reintento ${i + 1} (el archivo estaba ocupado)")
                return true
            }
        }
        return fallo(ultimo, intentos = ESPERAS.size + 1)
    }

    /** null si quedó; si no, la causa (sin dejar el temporal tirado: eso lo cuida [escribirAtomico]). */
    private fun intentarEscribir(bytes: ByteArray): Exception? = try {
        escribirBytes(archivo, bytes)
        null
    } catch (e: Exception) {
        e
    }

    private fun fallo(e: Exception, intentos: Int): Boolean {
        val causa = if (e is FileSystemException) "${e.javaClass.simpleName}: ${e.message}" else e.javaClass.simpleName
        Log.e(TAG, "No se pudo guardar $archivo tras $intentos intentos ($causa): se conserva lo que había en memoria y en disco")
        return false
    }

    internal companion object {
        private const val TAG = "Preferencias"
        /** Espera creciente entre intentos de escritura: 50+100+200+400+800 = 1.55 s. */
        internal val ESPERAS = listOf(50L, 100L, 200L, 400L, 800L)
        val SELLO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

/** Sólo trabaja en memoria: lo que falle aquí es del contenido, nunca del disco. */
internal fun interpretar(bytes: ByteArray): MutableMap<String, Any> {
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

/**
 * El JSON de siempre, en UTF-8 ESTRICTO. Un tipo que no es de SharedPreferences lanza, y también un texto que no se puede
 * escribir en UTF-8 (un surrogate suelto: un emoji cortado a la mitad): como `Files.writeString`, nunca se cambia en
 * silencio por «?», así `commit()` da false en vez de guardar algo distinto de lo que hay en memoria.
 */
internal fun serializar(valores: Map<String, Any>): ByteArray {
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
    val bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(json.toString()))   // REPORT: lanza
    return ByteArray(bytes.remaining()).also { bytes.get(it) }
}

/**
 * Lectura ESTRICTA (preferencias cifradas): cada falla lanza [PreferenciasIlegibles] con su motivo y NUNCA se aparta,
 * renombra ni borra nada. Un archivo que falta tampoco es «vacío»: no se pudo leer.
 */
internal fun leerEstricto(archivo: Path, codec: CodecDePreferencias): MutableMap<String, Any> =
    interpretarEstricto(archivo.fileName, leerBytesEstricto(archivo), codec)

/** Los bytes tal cual (reintenta lo pasajero). Lanza [PreferenciasIlegibles] «no se pudo leer». */
internal fun leerBytesEstricto(archivo: Path): ByteArray = try {
    reintentando({ it is IOException }) { Files.readAllBytes(archivo) }
} catch (e: IOException) {
    throw ilegibles("«${archivo.fileName}» no se pudo leer", e)
}

/** Descifra e interpreta [bytes] ya leídos de [nombre]. Lanza [PreferenciasIlegibles] «no se pudo descifrar» o «dañado». */
internal fun interpretarEstricto(nombre: Path, bytes: ByteArray, codec: CodecDePreferencias): MutableMap<String, Any> {
    val claro = try {
        codec.descifrar(bytes)
    } catch (e: Exception) {
        throw ilegibles("«$nombre» no se pudo descifrar: ¿otro usuario de Windows o se restableció su contraseña?", e)
    }
    return try {
        interpretar(claro)
    } catch (e: Exception) {
        // 🔴 Sin causa: el mensaje de org.json trae el VALOR («JSONObject["v"] is not a int (… : <el token>)») y la
        // bitácora imprime el «Caused by». Basta la clase para saber qué se rompió.
        throw ilegibles("«$nombre» está dañado (${e.javaClass.simpleName})", null)
    }
}

/** Temporal + force + ATOMIC_MOVE: o queda el archivo entero o queda el anterior. Lanza si falla, sin dejar el temporal. */
internal fun escribirAtomico(archivo: Path, bytes: ByteArray) {
    var temporal: Path? = null
    try {
        Files.createDirectories(archivo.parent)
        val tmp = Files.createTempFile(archivo.parent, ".prefs", ".tmp").also { temporal = it }
        Files.write(tmp, bytes)
        FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }   // en el disco antes del rename
        // En Windows un antivirus o el indexador sostienen el archivo un instante: hasta 3 reintentos de 50 ms.
        reintentando({ it is AccessDeniedException }) { Files.move(tmp, archivo, ATOMIC_MOVE, REPLACE_EXISTING) }
    } catch (e: Exception) {
        temporal?.let { runCatching { Files.deleteIfExists(it) } }
        throw e
    }
}

/** Hasta 3 reintentos de 50 ms ante lo que [reintentable] acepte; después relanza. Una interrupción no se traga. */
internal fun <R> reintentando(reintentable: (Exception) -> Boolean, accion: () -> R): R {
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
