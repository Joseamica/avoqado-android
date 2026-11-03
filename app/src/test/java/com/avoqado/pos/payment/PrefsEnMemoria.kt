package com.avoqado.pos.payment

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk

/**
 * `SharedPreferences` que se porta como el de Android (`SharedPreferencesImpl`): `commit()` cambia la MEMORIA primero y luego
 * intenta el disco. Con [discoSano] en false la memoria cambia igual y `commit()` devuelve false (Codex A-2: el doble anterior
 * no tocaba la memoria y escondía ese defecto). [enDisco] es lo que sobreviviría a reiniciar.
 */
class PrefsEnMemoria {
    /** Lo que la app lee. */
    val guardado = java.util.concurrent.ConcurrentHashMap<String, String>()
    val enDisco = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var discoSano = true
    /** Corre dentro de cada `commit()`, con la memoria ya cambiada y antes de saber si el disco la tomó. */
    @Volatile var alEscribir: (() -> Unit)? = null

    private val prefs: SharedPreferences = object : SharedPreferences {
        override fun getAll(): Map<String, *> = HashMap(guardado)
        override fun getString(key: String, defValue: String?): String? = guardado[key] ?: defValue
        override fun contains(key: String): Boolean = guardado.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = error("no se usa")
        override fun getInt(key: String, defValue: Int): Int = error("no se usa")
        override fun getLong(key: String, defValue: Long): Long = error("no se usa")
        override fun getFloat(key: String, defValue: Float): Float = error("no se usa")
        override fun getBoolean(key: String, defValue: Boolean): Boolean = error("no se usa")
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private inner class Editor : SharedPreferences.Editor {
        private val cambios = mutableMapOf<String, String?>()
        private var limpiar = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor { cambios[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { cambios[key] = null; return this }
        override fun clear(): SharedPreferences.Editor { limpiar = true; return this }
        override fun commit(): Boolean {
            synchronized(guardado) {
                if (limpiar) guardado.clear()
                cambios.forEach { (k, v) -> if (v == null) guardado.remove(k) else guardado[k] = v }
            }
            alEscribir?.invoke()
            if (!discoSano) return false
            synchronized(guardado) { enDisco.clear(); enDisco.putAll(guardado) }
            return true
        }
        override fun apply() { commit() }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = error("no se usa")
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = error("no se usa")
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = error("no se usa")
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = error("no se usa")
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = error("no se usa")
    }

    val context: Context = mockk<Context>().also { every { it.getSharedPreferences(any(), any()) } returns prefs }
}
