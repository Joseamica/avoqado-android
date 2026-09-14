package com.avoqado.pos.core.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "avoqado_settings")

@Singleton
class PreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.dataStore

    suspend fun setString(key: String, value: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    /**
     * Lee y reescribe llaves de texto en UNA transacción de DataStore: [transform] recibe lo que hay y
     * devuelve qué escribir (null = borrar esa llave). Si el archivo no se puede leer, DataStore lanza y no se
     * escribe nada: nadie fusiona contra un vacío que no leyó.
     */
    suspend fun updateStrings(keys: List<String>, transform: (Map<String, String?>) -> Map<String, String?>) {
        dataStore.edit { prefs ->
            val current = keys.associateWith { prefs[stringPreferencesKey(it)] }
            for ((key, value) in transform(current)) {
                if (value == null) prefs.remove(stringPreferencesKey(key)) else prefs[stringPreferencesKey(key)] = value
            }
        }
    }

    fun getString(key: String): Flow<String?> =
        dataStore.data.map { it[stringPreferencesKey(key)] }

    suspend fun setBoolean(key: String, value: Boolean) {
        dataStore.edit { it[booleanPreferencesKey(key)] = value }
    }

    fun getBoolean(key: String, default: Boolean = false): Flow<Boolean> =
        dataStore.data.map { it[booleanPreferencesKey(key)] ?: default }

    fun getBooleanOrNull(key: String): Flow<Boolean?> =
        dataStore.data.map { it[booleanPreferencesKey(key)] }

    suspend fun remove(key: String) {
        dataStore.edit { it.remove(booleanPreferencesKey(key)) }
    }

    suspend fun removeString(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
