package com.avoqado.pos.pos.data

import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.pos.data.model.PromotionsPayload
import com.avoqado.pos.pos.data.model.PromotionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * En qué situación está el catálogo de ESTE venue.
 *
 * 🔴 Existe para que la UI pueda distinguir **"todavía no sé"** de **"sé que no
 * hay"**. Sin esta señal, el panel enseña "Aún no hay promociones. Créalas desde
 * el dashboard" durante toda la ventana de carga — mintiéndole al cajero de un
 * local que sí las tiene.
 *
 * Vive en el repositorio, y no como una bandera del ViewModel, porque el cambio
 * de venue entra por aquí (`clearCache()` + `refresh()` desde
 * `AuthRepository.switchVenue`) sin pasar NUNCA por el ViewModel. Una bandera
 * local se quedaría en "ya cargué" con el catálogo del local anterior recién
 * borrado.
 */
enum class EstadoCatalogo {
    /** Nadie ha traído nada todavía, o se acaba de limpiar por cambio de local. */
    SIN_CARGAR,
    CARGANDO,

    /**
     * Ya sabemos qué hay — aunque lo que haya sea nada. Es el ÚNICO estado en el
     * que se puede afirmar "este local no tiene promociones".
     */
    CARGADO,

    /**
     * Se intentó y no se pudo: falló el fetch **y** no había nada en disco.
     *
     * 🔴 No es lo mismo que [CARGADO] con el catálogo vacío, y colapsarlos es un
     * defecto de la misma familia que este enum vino a matar: afirmar un negativo
     * con confianza cuando el estado real es "no sé". Peor aún, el texto de
     * "vacío" manda a RECREAR promociones que probablemente ya existen, cuando lo
     * que toca es reintentar con red. Ver §2.3 de
     * `.claude/rules/offline-first-y-hub-lan.md`: sin red es estado normal, no
     * error, y se le dice al usuario lo que es.
     */
    NO_SE_PUDO,
}

/**
 * Catálogo de promociones (combos, paquetes, 2x1) — el panel de cobro.
 *
 * Plan: .superpowers/sdd/2026-08-15-promociones-pos-cliente/task-2-brief.md
 *
 * Calcado de `UpsellRepository`: cache-first, se baja la tabla, se guarda, y
 * el panel la resuelve LOCALMENTE. Un apagón de WiFi no apaga el panel.
 */
@Singleton
class PromotionsRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val payloadCache: PayloadCache,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _promotions = MutableStateFlow(PromotionsPayload())
    val promotions: StateFlow<PromotionsPayload> = _promotions.asStateFlow()

    private val _estado = MutableStateFlow(EstadoCatalogo.SIN_CARGAR)

    /** Ver [EstadoCatalogo]: deja a la UI distinguir "no sé" de "no hay". */
    val estado: StateFlow<EstadoCatalogo> = _estado.asStateFlow()

    suspend fun refresh(venueId: String) {
        // Sin sesión no va a llegar nada NUNCA: se marca cargado (vacío) en vez
        // de dejar la pantalla girando para siempre. Un "Cargando…" eterno es
        // otra forma de mentir, y encima no dice qué hacer.
        val token = secureStorage.accessToken ?: run {
            _estado.value = EstadoCatalogo.CARGADO
            return
        }

        _estado.value = EstadoCatalogo.CARGANDO
        // Pesimista a propósito: si algo revienta por un camino que no previmos,
        // el estado que queda es "no sé", nunca "sé que no hay".
        var resultado = EstadoCatalogo.NO_SE_PUDO
        try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/promotions")
                .header("Authorization", "Bearer $token")
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            }

            when {
                code in 200..299 -> {
                    val result = json.decodeFromString<PromotionsResponse>(body)
                    _promotions.value = result.data
                    payloadCache.save(TYPE, venueId, body)
                    // El server contestó: éste es el único camino que puede
                    // afirmar "este local no tiene promociones".
                    resultado = EstadoCatalogo.CARGADO
                    Log.d(TAG, "✅ ${result.data.active.size} activas, ${result.data.upcoming.size} próximas")
                }

                // 🔴 El ÚNICO caso en que se borra el cache. Ver isPlanLock.
                code == 403 && isPlanLock(body) -> {
                    _promotions.value = PromotionsPayload()
                    payloadCache.clear(TYPE, venueId)
                    // También es una respuesta del server, no una falla: el panel
                    // pinta el candado de plan, no un error de conexión.
                    resultado = EstadoCatalogo.CARGADO
                    Log.w(TAG, "🔒 El local ya no tiene el plan de promociones — catálogo borrado")
                }

                else -> {
                    // Cualquier otro rechazo (permisos del mesero, proxy, 500) NO
                    // apaga la función: se queda lo que ya había.
                    Log.e(TAG, "❌ promotions $code — se conserva lo cacheado")
                    hydrateIfEmpty(venueId)
                    resultado = estadoTrasRescate()
                }
            }
        } catch (e: Exception) {
            // Sin red NO se toca el cache: es justo cuando más se necesita.
            Log.e(TAG, "❌ promotions sin red: ${e.message}")
            hydrateIfEmpty(venueId)
            resultado = estadoTrasRescate()
        } finally {
            // Pase lo que pase se sale del estado de carga. Qué queda depende de
            // si de verdad supimos algo — ver `resultado`.
            _estado.value = resultado
        }
    }

    /**
     * Después de un intento fallido: ¿alcanzamos a saber algo?
     *
     * 🔴 **El cache viejo GANA.** Si quedó algo —de esta sesión o del disco— el
     * panel pinta esas promociones y no dice nada de error: un catálogo un poco
     * viejo es infinitamente mejor que uno vacío, y la ley del repo es que el
     * fail-safe nunca puede ser quedarse sin poder vender. Sólo si no hay
     * absolutamente nada se admite que no pudimos preguntar.
     */
    private fun estadoTrasRescate(): EstadoCatalogo {
        val hayAlgo = _promotions.value.active.isNotEmpty() || _promotions.value.upcoming.isNotEmpty()
        return if (hayAlgo) EstadoCatalogo.CARGADO else EstadoCatalogo.NO_SE_PUDO
    }

    /** Arranque en modo avión: levantar el último catálogo bueno del disco. */
    private suspend fun hydrateIfEmpty(venueId: String) {
        if (_promotions.value.active.isNotEmpty() || _promotions.value.upcoming.isNotEmpty()) return
        payloadCache.load(TYPE, venueId)?.let { cached ->
            runCatching { json.decodeFromString<PromotionsResponse>(cached.json) }.getOrNull()?.let {
                _promotions.value = it.data
                Log.w(TAG, "⚠️ Promociones sin red — cache (hace ${cached.ageMinutes} min)")
            }
        }
    }

    /**
     * 🔴 Distinguir el 403 del CANDADO DE PLAN de cualquier otro 403.
     *
     * Sólo el candado de plan trae `featureCode` en el cuerpo (lo pone
     * `checkFeatureAccess` en el server). Un 403 de permisos —el mesero no
     * puede ver algo— o el de un proxy corporativo NO lo trae, y confundirlos
     * apagaría las promociones en un local que sí las paga, sin que nadie
     * entienda por qué.
     */
    private fun isPlanLock(body: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        !obj["featureCode"]?.jsonPrimitive?.content.isNullOrBlank()
    }.getOrDefault(false)

    /**
     * No hay de dónde traer (sesión sin venue activo). Se da por cargado —vacío—
     * para que la UI no se quede girando esperando algo que no va a llegar nunca.
     */
    fun marcarSinVenue() {
        _estado.value = EstadoCatalogo.CARGADO
    }

    /**
     * Al cambiar de venue: borra lo que se ve YA, antes de que llegue el refresh
     * nuevo.
     *
     * 🔴 Y vuelve a `SIN_CARGAR`. Sin esta línea el panel enseñaría "Aún no hay
     * promociones. Créalas desde el dashboard" del local NUEVO usando el "ya
     * cargué" del ANTERIOR — con el catálogo recién vaciado y el fetch todavía
     * en camino.
     */
    fun clearCache() {
        _promotions.value = PromotionsPayload()
        _estado.value = EstadoCatalogo.SIN_CARGAR
    }

    companion object {
        private const val TAG = "🎟️PROMOS"
        const val TYPE = "promotions"
    }
}
