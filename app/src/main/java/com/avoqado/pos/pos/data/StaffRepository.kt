package com.avoqado.pos.pos.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StaffMember(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val role: String? = null,
    /**
     * El nombre del rol COMO SE VE (default en español, o el custom del venue —
     * p.ej. VIEWER renombrado a "Investor"). Ausente en servers viejos ⇒ se
     * traduce el enum en local. Aditivo.
     */
    val roleDisplayName: String? = null,
    /**
     * ¿Este rol aparece en el selector de "Vendedor"? Es la PERILLA por rol del
     * editor de roles del dashboard (VenueRoleConfig.showAsSeller) — default
     * prendida: todos salen, y el venue apaga los que no venden (p.ej. un
     * VIEWER renombrado a "Investor"). Ausente (server viejo) ⇒ null ⇒ NO se
     * filtra. Founder 2026-09-01.
     */
    val showAsSeller: Boolean? = null,
    val active: Boolean = true,
) {
    val fullName: String
        get() = listOfNotNull(firstName, lastName)
            .joinToString(" ")
            .trim()
            .ifEmpty { email ?: "Staff" }

    /** Lo que se PINTA como rol — nunca el enum crudo si hay algo mejor. */
    val roleLabel: String?
        get() = com.avoqado.pos.core.util.RoleDisplay.label(roleDisplayName, role)
}

/**
 * Sólo los roles con la perilla "aparece como vendedor" prendida (founder
 * 2026-09-01: por default TODOS salen y el venue apaga los que no venden desde
 * el editor de roles). `null` (server viejo que no manda `showAsSeller`) NO se
 * filtra: fail-open — un server desactualizado enseña a todos, como hoy, nunca
 * esconde al equipo entero. 🔴 Las pantallas de RESERVAS usan la lista COMPLETA
 * a propósito (un instructor no necesita vender): no les apliques esta extensión.
 */
fun List<StaffMember>.soloVendedores(): List<StaffMember> = filter { it.showAsSeller != false }

@Serializable
private data class StaffListResponse(
    val success: Boolean = false,
    val data: List<StaffMember> = emptyList(),
)

@Singleton
class StaffRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val payloadCache: com.avoqado.pos.core.data.local.PayloadCache,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun getActiveStaff(): Result<List<StaffMember>> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/staff?active=true")
                .get()
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                val decoded = json.decodeFromString<StaffListResponse>(body)
                // Offline-first: el picker de "Asignar" necesita esta lista sin red.
                payloadCache.save("staff", venueId, body)
                Result.success(decoded.data.filter { it.active })
            } else {
                Log.e("👥", "Fetch staff failed: $code - $body")
                Result.failure(Exception("Error al cargar staff ($code)"))
            }
        } catch (e: Exception) {
            // Sin red: la lista cacheada sigue sirviendo (snapshot honesto).
            val cached = payloadCache.load("staff", venueId)
            if (cached != null) {
                runCatching { json.decodeFromString<StaffListResponse>(cached.json) }.getOrNull()?.let { decoded ->
                    Log.w("👥", "⚠️ Staff sin red — usando cache (hace ${cached.ageMinutes} min)")
                    return Result.success(decoded.data.filter { it.active })
                }
            }
            Log.e("👥", "Fetch staff error: ${e.message}")
            Result.failure(e)
        }
    }
}
