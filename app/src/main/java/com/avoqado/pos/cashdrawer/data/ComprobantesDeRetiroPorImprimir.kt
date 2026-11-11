package com.avoqado.pos.cashdrawer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.comprobantesDeRetiroDataStore by preferencesDataStore(name = "retiro_comprobantes")

private val jsonDeComprobantes = Json { ignoreUnknownKeys = true }

/**
 * El papel de un retiro, con TODO lo que lleva ya resuelto al guardarlo (Codex r3 #9): quién lo hizo y con qué rol, quién lo
 * autorizó, el negocio y el aparato, y la fecha y la hora ya escritas en la zona del negocio. Al recuperarlo tras una muerte de la
 * app se imprime ESTO, nunca los datos de la sesión que esté abierta en ese momento.
 */
@Serializable
data class ComprobanteDeRetiro(
    val venueId: String,
    val localId: String,
    val venueName: String,
    val nombreDelAparato: String?,
    val fechaTexto: String,
    val horaTexto: String,
    val concepto: String,
    val amountCents: Int,
    val hizo: String,
    val rolDeQuienHizo: String?,
    val autorizadoPor: String,
)

/**
 * 🔴 La obligación de imprimir el comprobante de un retiro, en disco (Codex r3 #7). `CashDrawerRepository.addPayOut` la anota
 * DESPUÉS de guardar la fila y la cola y ANTES del primer POST: si la app muere a media red, el retiro sobrevive y su papel
 * también. Se quita al imprimir o cuando el retiro se rechaza al momento. Molde: `RefundPendingStore`.
 */
@Singleton
class ComprobantesDeRetiroPorImprimir internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.comprobantesDeRetiroDataStore)

    private fun llave(venueId: String, localId: String) = stringPreferencesKey("$venueId:$localId")

    suspend fun anotar(c: ComprobanteDeRetiro) {
        dataStore.edit { it[llave(c.venueId, c.localId)] = jsonDeComprobantes.encodeToString(ComprobanteDeRetiro.serializer(), c) }
    }

    suspend fun quitar(venueId: String, localId: String) {
        dataStore.edit { it.remove(llave(venueId, localId)) }
    }

    /** Los de ESE negocio. Uno ilegible se salta (no tumba a los demás). */
    suspend fun pendientes(venueId: String): List<ComprobanteDeRetiro> =
        dataStore.data.first().asMap().values
            .mapNotNull { valor ->
                (valor as? String)?.let { runCatching { jsonDeComprobantes.decodeFromString(ComprobanteDeRetiro.serializer(), it) }.getOrNull() }
            }
            .filter { it.venueId == venueId }
}
