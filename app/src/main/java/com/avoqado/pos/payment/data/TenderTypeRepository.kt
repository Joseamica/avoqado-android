package com.avoqado.pos.payment.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.payment.domain.TenderTypeOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catálogo de tipos de pago del negocio para la pantalla "¿cómo pagó el cliente?".
 *
 * 🔴 CACHE-FIRST, y nunca borra una lista buena en un refresh fallido. Es la misma
 * lección que costó un bug real en la impresión offline: al fallar se pisaba la
 * config con una vacía y el local se quedaba sin imprimir. Aquí el equivalente
 * sería que el cajero pierda los tipos del negocio justo cuando se cae el WiFi —
 * y entonces no puede registrar la venta de Uber Eats que acaba de entregar.
 *
 * Una lista ligeramente vieja es infinitamente menos dañina que ninguna: el
 * `revision` que se cachea viaja con el cobro y el server sabe honrarlo.
 *
 * 🔴 DOS defectos que se arreglaron probando en el D3 (2026-08-17), y que hay que
 * entender antes de tocar esto:
 *
 * 1. **La lista es un flujo, no un getter.** Antes era `fun cached(): List<…>`, que
 *    Compose no puede observar: al abrir el cobro, la caché estaba vacía, la hoja se
 *    componía sin tipos y la respuesta del refresh llegaba a un getter que nadie
 *    volvía a leer. O sea: **la primera venta después de abrir la app NUNCA veía los
 *    tipos del negocio**, y sólo aparecían en la segunda. Medido en el aparato.
 *
 * 2. **La caché sobrevive al reinicio.** Antes vivía sólo en memoria. Un POS que se
 *    reinicia sin internet —el caso real de una tablet que se quedó sin batería a
 *    media jornada— arrancaba sin ningún tipo del negocio y el cajero no tenía cómo
 *    registrar lo que ya entregó. Es exactamente el agujero de `PrintConfigRepository`.
 */
@Singleton
class TenderTypeRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    /**
     * Caché en DISCO, por venue. Sin datos sensibles (nombres y orden de tipos de
     * pago), así que va en las prefs normales, igual que el resto de la config de UI.
     */
    private val prefs = context.getSharedPreferences("avoqado_tender_types", Context.MODE_PRIVATE)

    /** Última lista buena del venue activo. Compose la observa; por eso es StateFlow. */
    private val _tenderTypes = MutableStateFlow(loadFromDisk(secureStorage.venueId))
    val tenderTypes: StateFlow<List<TenderTypeOption>> = _tenderTypes.asStateFlow()

    /**
     * Refresca desde el server. Ante CUALQUIER fallo conserva lo que ya había —
     * jamás publica una lista vacía que borre lo que el cajero ya podía usar.
     */
    suspend fun refresh(): List<TenderTypeOption> {
        val venueId = secureStorage.venueId ?: return _tenderTypes.value
        // Al cambiar de venue, lo primero es publicar lo que haya EN DISCO de ESE
        // negocio: sin esto la hoja mostraría los tipos del venue anterior mientras
        // llega la red — un cobro atribuido al tipo equivocado.
        val enDisco = loadFromDisk(venueId)
        if (_tenderTypes.value != enDisco && enDisco.isNotEmpty()) _tenderTypes.value = enDisco

        val token = secureStorage.accessToken ?: return _tenderTypes.value

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/tender-types")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val body = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
            } ?: return _tenderTypes.value

            val array = JSONObject(body).optJSONArray("tenderTypes") ?: return _tenderTypes.value
            val parsed = (0 until array.length()).map { i -> parse(array.getJSONObject(i)) }

            _tenderTypes.value = parsed
            saveToDisk(venueId, body)
            Log.d(TAG, "✅ ${parsed.size} tipos de pago para $venueId")
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ No se pudo refrescar tipos de pago; se conserva la lista en caché", e)
            _tenderTypes.value
        }
    }

    // MARK: - Persistencia

    private fun saveToDisk(venueId: String, rawBody: String) {
        prefs.edit { putString(venueId, rawBody) }
    }

    /**
     * Una caché corrupta NO puede tumbar el cobro: si no se puede leer, se devuelve
     * vacío y el refresh la repondrá. Nunca lanza.
     */
    private fun loadFromDisk(venueId: String?): List<TenderTypeOption> {
        val raw = venueId?.let { prefs.getString(it, null) } ?: return emptyList()
        return try {
            val array: JSONArray = JSONObject(raw).optJSONArray("tenderTypes") ?: return emptyList()
            (0 until array.length()).map { i -> parse(array.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Caché de tipos de pago ilegible; se ignora", e)
            emptyList()
        }
    }

    private fun parse(o: JSONObject) = TenderTypeOption(
        id = o.getString("id"),
        revision = o.getInt("revision"),
        name = o.getString("name"),
        isSystem = o.optBoolean("isSystem", false),
        baseMethod = o.optString("baseMethod", "OTHER"),
        captureTip = o.optBoolean("captureTip", true),
        posSection = o.optString("posSection", "MORE"),
        displayOrder = o.optInt("displayOrder", 0),
    )

    private companion object {
        const val TAG = "💳 TenderTypes"
    }
}
