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
    /** `null` sólo en la reimpresión armada con el movimiento (sin papel guardado en este aparato): no se sabe y no se inventa. */
    val autorizadoPor: String?,
    /** El id que le dio el SERVIDOR al confirmar el retiro: la fila de Caja cambia a ése y la reimpresión lo busca por los dos. */
    val idDelServidor: String? = null,
    /** Cuándo se imprimió (milisegundos). Sólo en los ya impresos; sirve para tirarlos a los [DIAS_GUARDADO] días. */
    val impresoEn: Long = 0L,
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

    /** Los ya impresos van aparte, con prefijo: [pendientes] nunca los ve y no se vuelven a imprimir solos. */
    private fun llaveDelImpreso(venueId: String, localId: String) = stringPreferencesKey("$IMPRESO$venueId:$localId")

    private fun leer(valor: Any?): ComprobanteDeRetiro? =
        (valor as? String)?.let { runCatching { jsonDeComprobantes.decodeFromString(ComprobanteDeRetiro.serializer(), it) }.getOrNull() }

    private fun escribir(c: ComprobanteDeRetiro) = jsonDeComprobantes.encodeToString(ComprobanteDeRetiro.serializer(), c)

    suspend fun anotar(c: ComprobanteDeRetiro) {
        dataStore.edit { it[llave(c.venueId, c.localId)] = jsonDeComprobantes.encodeToString(ComprobanteDeRetiro.serializer(), c) }
    }

    suspend fun quitar(venueId: String, localId: String) {
        dataStore.edit { it.remove(llave(venueId, localId)) }
    }

    /** Los de ESE negocio. Uno ilegible se salta (no tumba a los demás). */
    suspend fun pendientes(venueId: String): List<ComprobanteDeRetiro> =
        dataStore.data.first().asMap()
            .filterKeys { !it.name.startsWith(IMPRESO) }
            .values.mapNotNull(::leer)
            .filter { it.venueId == venueId }

    /**
     * Salió el papel: deja de estar pendiente y se guarda como impreso, en una sola escritura, para que «Reimprimir» saque el mismo
     * papel (con quién lo autorizó). De paso tira los impresos de más de [DIAS_GUARDADO] días.
     */
    suspend fun marcarImpreso(c: ComprobanteDeRetiro, ahora: Long = System.currentTimeMillis()) {
        val vencido = ahora - DIAS_GUARDADO * 24L * 60 * 60 * 1000
        dataStore.edit { prefs ->
            // El pendiente puede traer el id del servidor que [ligarAlServidor] le puso después de que se armó [c].
            val guardado = prefs[llave(c.venueId, c.localId)]?.let(::leer)
            prefs.remove(llave(c.venueId, c.localId))
            prefs[llaveDelImpreso(c.venueId, c.localId)] =
                escribir(c.copy(idDelServidor = c.idDelServidor ?: guardado?.idDelServidor, impresoEn = ahora))
            // Copia antes de borrar: `asMap()` es una vista de lo que se está editando.
            val viejos = prefs.asMap().filter { (k, v) -> k.name.startsWith(IMPRESO) && (leer(v)?.impresoEn ?: 0L) < vencido }.keys.toList()
            @Suppress("UNCHECKED_CAST")
            viejos.forEach { prefs.remove(it as Preferences.Key<Any>) }
        }
    }

    /** El retiro [localId] ya tiene id del servidor: lo anota en su papel, pendiente o impreso. Sin papel guardado no hace nada. */
    suspend fun ligarAlServidor(venueId: String, localId: String, idDelServidor: String) {
        dataStore.edit { prefs ->
            for (k in listOf(llave(venueId, localId), llaveDelImpreso(venueId, localId))) {
                val c = prefs[k]?.let(::leer) ?: continue
                if (c.idDelServidor != idDelServidor) prefs[k] = escribir(c.copy(idDelServidor = idDelServidor))
            }
        }
    }

    /** El papel guardado del movimiento [idDelMovimiento] de Caja (su id local o el del servidor), impreso o pendiente. */
    suspend fun buscar(venueId: String, idDelMovimiento: String): ComprobanteDeRetiro? =
        dataStore.data.first().asMap().values.mapNotNull(::leer)
            .firstOrNull { it.venueId == venueId && (it.localId == idDelMovimiento || it.idDelServidor == idDelMovimiento) }

    companion object {
        private const val IMPRESO = "impreso:"
        /** Cuánto se guarda un papel ya impreso para «Reimprimir». Una caja abierta varios días sigue cubierta. */
        const val DIAS_GUARDADO = 45
    }
}
