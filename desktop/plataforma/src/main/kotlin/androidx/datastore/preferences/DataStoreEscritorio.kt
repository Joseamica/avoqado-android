package androidx.datastore.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty

private val abiertos = ConcurrentHashMap<File, DataStore<Preferences>>()

/**
 * Sustituto de `preferencesDataStore`: el mismo archivo que en Android (`files/datastore/<nombre>.preferences_pb`),
 * abierto UNA vez por archivo (DataStore truena si dos instancias vivas comparten archivo).
 */
fun preferencesDataStore(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> = ReadOnlyProperty { contexto, _ ->
    val archivo = File(contexto.applicationContext.filesDir, "datastore/$name.preferences_pb").absoluteFile
    abiertos.computeIfAbsent(archivo) { f -> PreferenceDataStoreFactory.create { f } }
}
